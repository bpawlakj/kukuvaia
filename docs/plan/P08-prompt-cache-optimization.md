# P08: Prompt Cache Optimization — Cache-Aware Prompt Construction

**Created**: 2026-04-10
**Status**: Draft
**Module**: kukuvaia-core, kukuvaia-memory
**Depends on**: ChatClientConfig, SmartMemoryAdvisor, PersonaService, SystemPromptBuilder

---

## Problem

LLM providers implement prompt caching (Anthropic's cache_control / prefix caching, OpenAI's automatic prefix caching) that dramatically reduces cost and latency for repeated prompt prefixes. Anthropic charges 90% less for cached input tokens and serves them ~5x faster. OpenAI provides 50% discount on cached tokens.

Kukuvaia's current prompt construction is cache-unaware:

- Advisor ordering was designed for correctness, not cache efficiency
- Dynamic content (memories, conversation history) mixes with static content (persona, security rules, planning instructions) in unpredictable order
- No cache_control markers are sent to Anthropic models
- No monitoring of cache hit rates
- The system potentially wastes significant cost by sending the same system prompt prefix across conversations without benefiting from caching

For a typical conversation with a 4K-token system prompt, prompt caching could save $0.001-0.01 per request. At scale (1000+ requests/day), this compounds to meaningful cost reduction.

---

## Current State

### Prompt construction order (actual token sequence sent to LLM)

The system prompt is assembled by multiple advisors in chain order:

```
1. PersonaService.getActivePersona(sessionId).systemPrompt()    [set by AgentService]
   └── "You are Kukuvaia, an intelligent AI assistant..."

2. ToolResultSanitizingAdvisor.before() [HIGHEST_PRECEDENCE + 1]
   └── augmentSystemMessage(SAFETY_SUFFIX)
   └── "--- SECURITY BOUNDARY (always enforced): ..."

3. SmartMemoryAdvisor.before() [HIGHEST_PRECEDENCE + 5]
   └── augmentSystemMessage(memoryBlock)
   └── "--- PERSISTENT MEMORY (from previous sessions) ---"

4. TokenBudgetAdvisor.before() [HIGHEST_PRECEDENCE + 20]
   └── augmentSystemMessage(BUDGET_WARNING)   [only when usage > 80%]

5. MessageChatMemoryAdvisor [default order]
   └── Appends conversation history messages
```

Note: `SystemPromptBuilder` assembles PLANNING_INSTRUCTIONS + VERIFICATION_MANDATE + STATIC_BOUNDARY + persona + rules + skill, but this is currently only used via `AgentService.chat()` calling `persona.systemPrompt()`. The builder itself is not wired as an advisor.

### Why this is cache-hostile

```
Token position:  [persona_prompt | SAFETY_SUFFIX | memories | budget_warning | history | user_msg]
                  ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                  STATIC (same across requests)    ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                                   DYNAMIC (changes every request)
```

The static prefix IS stable across requests within a session, which is good. But:

1. **No cache_control markers** — Anthropic needs explicit `cache_control: {type: "ephemeral"}` breakpoints
2. **Memories change per-request** — semantic retrieval returns different memories per query, breaking cache after the static prefix
3. **No monitoring** — we do not know current cache hit rate (response headers contain this info)
4. **SystemPromptBuilder not used in advisor chain** — the STATIC_BOUNDARY marker exists but is not leveraged for cache hints

---

## Architecture

### Optimal prompt ordering for cache hits

```
┌─────────────────────────────────────────────────────────┐
│  ZONE 1: STATIC PREFIX (identical across all sessions)  │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Planning instructions (SystemPromptBuilder)     │    │
│  │ Verification mandate (SystemPromptBuilder)      │    │
│  │ Security boundary (ToolResultSanitizingAdvisor) │    │
│  └─────────────────────────────────────────────────┘    │
│  ↑ cache_control breakpoint 1                           │
├─────────────────────────────────────────────────────────┤
│  ZONE 2: SEMI-STATIC (changes per persona/session)     │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Persona system prompt                           │    │
│  │ User rules (.kukuvaia/rules/)                   │    │
│  │ Active skill (if any)                           │    │
│  └─────────────────────────────────────────────────┘    │
│  ↑ cache_control breakpoint 2                           │
├─────────────────────────────────────────────────────────┤
│  ZONE 3: SEMI-DYNAMIC (changes infrequently)           │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Persistent memories (SmartMemoryAdvisor)        │    │
│  │ Budget warning (TokenBudgetAdvisor)             │    │
│  └─────────────────────────────────────────────────┘    │
│  ↑ cache_control breakpoint 3 (optional)                │
├─────────────────────────────────────────────────────────┤
│  ZONE 4: DYNAMIC (changes every request)               │
│  ┌─────────────────────────────────────────────────┐    │
│  │ Conversation history (MessageChatMemoryAdvisor) │    │
│  │ User message                                    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

**Key insight**: The LLM sees the system prompt as a single token stream. Cache hits happen on the longest matching prefix. By ordering static content first, we maximize the cacheable prefix length.

### Token budget allocation

| Zone | Typical size | % of context | Cache behavior |
|------|-------------|-------------|----------------|
| Zone 1 (static) | 300-500 tokens | 3-5% | Always cached after first request |
| Zone 2 (semi-static) | 200-2000 tokens | 2-20% | Cached within same persona session |
| Zone 3 (semi-dynamic) | 100-800 tokens | 1-8% | Cached when same memories retrieved |
| Zone 4 (dynamic) | 500-50000 tokens | 70-90% | Never cached |

---

## Implementation

### Step 1: CacheAwarePromptAdvisor — consolidates system prompt construction

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/CacheAwarePromptAdvisor.java`

This advisor replaces the current pattern where multiple advisors independently call `augmentSystemMessage()`. Instead, it builds the complete system prompt in the correct order with cache-control markers.

```java
package ai.kukuvaia.advisors;

import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.agent.SystemPromptBuilder;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Assembles the system prompt in cache-optimal order.
 * Runs early in the chain — before SmartMemoryAdvisor and others
 * that would otherwise augment the system prompt in suboptimal order.
 *
 * Order: HIGHEST_PRECEDENCE + 1 (after ProviderAuditLog)
 *
 * Note: This replaces ToolResultSanitizingAdvisor's augmentSystemMessage behavior.
 * ToolResultSanitizingAdvisor's SAFETY_SUFFIX is now included in Zone 1 by this advisor.
 * ToolResultSanitizingAdvisor should be refactored to only set a context flag,
 * and this advisor incorporates its text.
 */
@Component
public class CacheAwarePromptAdvisor implements BaseAdvisor {

    private static final String CACHE_BREAKPOINT = "\n<!-- cache_control: ephemeral -->\n";

    private final PersonaService personaService;
    private final SystemPromptBuilder systemPromptBuilder;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String sessionId = (String) request.context()
                .getOrDefault("chat_memory_conversation_id", "default");

        var persona = personaService.getActivePersona(sessionId);
        String intent = (String) request.context().getOrDefault("kukuvaia.intent", "");
        String activeSkill = (String) request.context().getOrDefault("kukuvaia.activeSkill", "");

        // Zone 1: Static prefix (always the same)
        var sb = new StringBuilder();
        sb.append(SystemPromptBuilder.PLANNING_INSTRUCTIONS);
        sb.append(SystemPromptBuilder.VERIFICATION_MANDATE);
        sb.append(SystemPromptBuilder.SAFETY_SUFFIX);
        sb.append(CACHE_BREAKPOINT); // Breakpoint 1

        // Zone 2: Semi-static (per persona/session)
        sb.append("\n## Persona\n").append(persona.systemPrompt()).append("\n");
        // Rules and skill appended here
        if (activeSkill != null && !activeSkill.isBlank()) {
            sb.append("\n## Active Skill\n").append(activeSkill).append("\n");
        }
        sb.append(CACHE_BREAKPOINT); // Breakpoint 2

        // Zone 3 and Zone 4 are added by downstream advisors
        // (SmartMemoryAdvisor, MessageChatMemoryAdvisor)

        return request.mutate()
                .prompt(request.prompt().mutateSystemMessage(sb.toString()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }
}
```

### Step 2: Refactor ToolResultSanitizingAdvisor

**Modify**: `kukuvaia-core/src/main/java/ai/kukuvaia/security/ToolResultSanitizingAdvisor.java`

Extract SAFETY_SUFFIX as a public constant so CacheAwarePromptAdvisor can include it in Zone 1. ToolResultSanitizingAdvisor's `before()` becomes a no-op (or sets a context flag) when CacheAwarePromptAdvisor is active.

```java
// Make SAFETY_SUFFIX accessible
public static final String SAFETY_SUFFIX = """
        ...
        """;

@Override
public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    // When CacheAwarePromptAdvisor is active, it handles SAFETY_SUFFIX placement
    if (request.context().containsKey("kukuvaia.cacheAwarePrompt")) {
        return request; // Already included in cache-optimal position
    }
    // Backward compat: if cache-aware advisor is not in chain
    return request.mutate()
            .prompt(request.prompt().augmentSystemMessage(SAFETY_SUFFIX))
            .build();
}
```

### Step 3: Anthropic cache_control markers via Spring AI

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/AnthropicCacheMarkerAdvisor.java`

Spring AI's OpenAI-compatible API may not directly support Anthropic's `cache_control` field. This advisor adds cache control metadata to the request when the active provider is Anthropic:

```java
@Component
@ConditionalOnProperty(name = "kukuvaia.cache.anthropic-markers", havingValue = "true")
public class AnthropicCacheMarkerAdvisor implements BaseAdvisor {

    @Override
    public int getOrder() {
        // Run after CacheAwarePromptAdvisor, before the actual LLM call
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // Detect provider type from context
        String provider = (String) request.context().getOrDefault("kukuvaia.provider", "");
        if (!isAnthropicProvider(provider)) return request;

        // Insert cache_control breakpoints into the prompt
        // Implementation depends on Spring AI's support for Anthropic-specific fields
        // Option A: Custom HTTP header via ChatOptions
        // Option B: Provider-specific ChatOptions subclass
        // Option C: Raw API extension point in Spring AI
        return request;
    }
}
```

**Note**: The exact mechanism depends on Spring AI 1.1's support for provider-specific metadata. If Spring AI does not yet support `cache_control`, this step becomes a provider-level HTTP interceptor that modifies the request body before sending. This is documented as a constraint below.

### Step 4: Cache hit rate monitoring

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/CacheMonitoringAdvisor.java`

Reads cache hit information from LLM response headers/metadata:

```java
@Component
public class CacheMonitoringAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(CacheMonitoringAdvisor.class);

    // Metrics: MeterRegistry for Micrometer (when observability is added)
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong cachedTokens = new AtomicLong(0);

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 5; // Late in chain, reads response
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        var chatResponse = response.chatResponse();
        if (chatResponse == null) return response;

        var usage = chatResponse.getMetadata().getUsage();
        if (usage == null) return response;

        // Anthropic returns cache_creation_input_tokens and cache_read_input_tokens
        // OpenAI returns cached_tokens in usage.prompt_tokens_details
        // Spring AI may expose these via Usage or metadata map

        var metadata = chatResponse.getMetadata();
        // Extract cache metrics from provider-specific usage fields
        long cachedInput = extractCachedTokens(metadata);

        if (cachedInput > 0) {
            cacheHits.incrementAndGet();
            cachedTokens.addAndGet(cachedInput);
            log.debug("Cache HIT: {} cached tokens", cachedInput);
        } else {
            cacheMisses.incrementAndGet();
            log.debug("Cache MISS");
        }

        // Log periodic summary
        long total = cacheHits.get() + cacheMisses.get();
        if (total % 100 == 0 && total > 0) {
            double hitRate = (double) cacheHits.get() / total * 100;
            log.info("Cache stats: hitRate={}%, hits={}, misses={}, cachedTokens={}",
                    String.format("%.1f", hitRate), cacheHits.get(), cacheMisses.get(),
                    cachedTokens.get());
        }

        return response;
    }

    public double getHitRate() {
        long total = cacheHits.get() + cacheMisses.get();
        return total > 0 ? (double) cacheHits.get() / total : 0;
    }

    public long getCachedTokens() {
        return cachedTokens.get();
    }
}
```

### Step 5: SmartMemoryAdvisor stability optimization

**Modify**: `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/advisor/SmartMemoryAdvisor.java`

Improve cache friendliness by stabilizing the memory block between requests in the same session:

```java
// Session-level memory cache: reuse same memory block within a time window
private final Map<String, CachedMemoryBlock> memoryCache = new ConcurrentHashMap<>();

private record CachedMemoryBlock(String block, long timestamp) {}

private static final long MEMORY_CACHE_TTL_MS = 60_000; // 1 minute

@Override
public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    String sessionId = ...; // existing code
    String userId = ...;

    // Check if cached memory block is still valid
    var cached = memoryCache.get(sessionId);
    if (cached != null && System.currentTimeMillis() - cached.timestamp() < MEMORY_CACHE_TTL_MS) {
        // Reuse same memory block — maximizes cache prefix hit
        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(cached.block()))
                .build();
    }

    // Otherwise, retrieve fresh memories (existing semantic search logic)
    // ... existing code ...

    // Cache the memory block for this session
    memoryCache.put(sessionId, new CachedMemoryBlock(memoryBlock.toString(), System.currentTimeMillis()));

    return request.mutate()
            .prompt(request.prompt().augmentSystemMessage(memoryBlock.toString()))
            .build();
}
```

This means within a 1-minute window, the same memory block is injected, keeping the prompt prefix identical and maximizing cache hits.

### Step 6: SystemPromptBuilder refactor — expose static constants

**Modify**: `kukuvaia-core/src/main/java/ai/kukuvaia/agent/SystemPromptBuilder.java`

Make static prompt sections accessible as public constants for CacheAwarePromptAdvisor:

```java
// Change visibility from private to public
public static final String VERIFICATION_MANDATE = ...;
public static final String PLANNING_INSTRUCTIONS = ...;
public static final String STATIC_BOUNDARY = ...;
```

### Step 7: Token budget allocation configuration

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/CacheConfig.java`

```java
@ConfigurationProperties(prefix = "kukuvaia.cache")
public record CacheConfig(
        boolean enabled,                    // master switch
        boolean anthropicMarkers,           // send cache_control markers
        int memoryBlockTtlSeconds,          // SmartMemoryAdvisor cache TTL
        int staticPrefixMaxTokens,          // max tokens for Zone 1+2
        int memoryMaxTokens                 // max tokens for Zone 3
) {
    public CacheConfig {
        if (memoryBlockTtlSeconds <= 0) memoryBlockTtlSeconds = 60;
        if (staticPrefixMaxTokens <= 0) staticPrefixMaxTokens = 2000;
        if (memoryMaxTokens <= 0) memoryMaxTokens = 1000;
    }
}
```

---

## Configuration

### application.yaml additions

```yaml
kukuvaia:
  cache:
    enabled: ${KUKUVAIA_PROMPT_CACHE:true}
    anthropic-markers: ${KUKUVAIA_ANTHROPIC_CACHE_MARKERS:false}
    memory-block-ttl-seconds: 60
    static-prefix-max-tokens: 2000
    memory-max-tokens: 1000
```

---

## Dependencies

| Dependency | Purpose | New? |
|-----------|---------|------|
| Spring AI BaseAdvisor | Advisor interface | Existing |
| SystemPromptBuilder | Static prompt sections | Existing (modified) |
| SmartMemoryAdvisor | Memory block caching | Existing (modified) |
| PersonaService | Active persona for Zone 2 | Existing |

No new external dependencies. Anthropic cache_control support depends on Spring AI's provider extension points.

---

## Constraints and Risks

### Spring AI cache_control support

Spring AI 1.1's `OpenAiApi` is designed for OpenAI-compatible endpoints. Anthropic's `cache_control` field is a non-standard extension. Options:

1. **Best case**: Spring AI 1.1+ supports Anthropic-specific metadata via ChatOptions — use directly
2. **Medium case**: SmartGate (the proxy) handles cache_control transparently — no client changes needed
3. **Worst case**: Need a custom `RestClient` interceptor to inject `cache_control` into the request body

Mitigation: The prompt ordering optimization (Steps 1-5) provides benefits regardless of cache_control markers — providers with automatic prefix caching (OpenAI) benefit immediately. Anthropic-specific markers (Step 3) can be deferred.

### Memory block stability vs freshness

Caching the memory block for 60 seconds means a newly saved memory will not appear in prompts for up to 60 seconds. This is acceptable because:
- Memories are cross-session knowledge, not real-time data
- Users can force-refresh by starting a new session
- The TTL is configurable

---

## File Inventory

### New files (3)

| File | Description |
|------|-------------|
| `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/CacheAwarePromptAdvisor.java` | Consolidates system prompt in cache-optimal order |
| `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/CacheMonitoringAdvisor.java` | Tracks cache hit rates from response metadata |
| `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/CacheConfig.java` | @ConfigurationProperties for cache tuning |

### Modified files (4)

| File | Change |
|------|--------|
| `kukuvaia-core/src/main/java/ai/kukuvaia/security/ToolResultSanitizingAdvisor.java` | Make SAFETY_SUFFIX public, conditional augment |
| `kukuvaia-core/src/main/java/ai/kukuvaia/agent/SystemPromptBuilder.java` | Make constants public |
| `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/advisor/SmartMemoryAdvisor.java` | Add memory block TTL cache |
| `kukuvaia-app/src/main/resources/application.yaml` | Add kukuvaia.cache.* section |

### Conditionally new (1)

| File | Description |
|------|-------------|
| `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/AnthropicCacheMarkerAdvisor.java` | Anthropic-specific cache markers (deferred if Spring AI lacks support) |

### Test files (3)

| File | What it tests |
|------|--------------|
| `kukuvaia-core/src/test/java/ai/kukuvaia/advisors/CacheAwarePromptAdvisorTest.java` | Prompt zone ordering, breakpoint placement |
| `kukuvaia-core/src/test/java/ai/kukuvaia/advisors/CacheMonitoringAdvisorTest.java` | Hit rate calculation, periodic logging |
| `kukuvaia-memory/src/test/java/ai/kukuvaia/memory/advisor/SmartMemoryAdvisorCacheTest.java` | Memory block TTL, cache hit within window |

---

## Verification

### Unit tests

```bash
./gradlew :kukuvaia-core:test --tests "ai.kukuvaia.advisors.CacheAware*"
./gradlew :kukuvaia-core:test --tests "ai.kukuvaia.advisors.CacheMonitoring*"
./gradlew :kukuvaia-memory:test --tests "ai.kukuvaia.memory.advisor.SmartMemoryAdvisorCacheTest"
```

Expected:
- `CacheAwarePromptAdvisorTest`: system prompt starts with Zone 1 (static), Zone 2 (persona) follows, breakpoints at correct positions
- `CacheMonitoringAdvisorTest`: hit rate calculation correct, periodic logging triggers at intervals
- `SmartMemoryAdvisorCacheTest`: same memory block returned within TTL window; fresh retrieval after TTL expires

### Integration test

Verify prompt ordering end-to-end with a mocked ChatModel that captures the full prompt:

```java
@Test
void promptOrder_staticPrefixFirst_thenPersona_thenMemories() {
    // Given: a session with persona and memories
    // When: chat request goes through advisor chain
    // Then: captured system prompt has zones in order:
    //   1. Planning + Verification + Security
    //   2. Persona + Rules
    //   3. Memories
}
```

### Manual verification

```bash
# Enable debug logging for advisors
logging.level.ai.kukuvaia.advisors=DEBUG

# Send multiple messages in same session
# Check logs for:
# 1. "Cache HIT" / "Cache MISS" entries
# 2. Memory block reuse within TTL window
# 3. Prompt ordering (Zone 1 → Zone 2 → Zone 3 → Zone 4)

# After 100+ requests, check aggregate stats:
grep "Cache stats:" logs/kukuvaia.log
# Expected: hitRate >60% for same-session conversations
```

---

## Effort Estimate

| Phase | Scope | Effort |
|-------|-------|--------|
| Phase 1 | CacheAwarePromptAdvisor + SystemPromptBuilder refactor | 1.5 days |
| Phase 2 | ToolResultSanitizingAdvisor refactor (conditional augment) | 0.5 day |
| Phase 3 | SmartMemoryAdvisor TTL cache | 1 day |
| Phase 4 | CacheMonitoringAdvisor | 0.5 day |
| Phase 5 | CacheConfig + application.yaml | 0.5 day |
| Phase 6 | AnthropicCacheMarkerAdvisor (if Spring AI supports it) | 1 day |
| Phase 7 | Tests | 1.5 days |
| **Total (without Anthropic markers)** | | **5.5 days** |
| **Total (with Anthropic markers)** | | **6.5 days** |

---

## Priority & Prerequisites

**Priority**: Medium — cost optimization. Impact scales with usage volume. Most valuable for high-volume deployments.

**Prerequisites**:
- Current advisor chain operational (already done)
- SmartMemoryAdvisor operational (already done)
- SystemPromptBuilder operational (already done)
- For Anthropic markers: understanding of Spring AI's provider extension points

**Blocked by**: Nothing — prompt ordering optimization works immediately.

**Blocks**:
- Nothing directly — this is a pure optimization
- Integrates well with P07 (Cost Tracking) for measuring cost savings

**Synergies**:
- P07 (Cost Tracking): CacheMonitoringAdvisor provides data for cost savings reports
- Model routing plan: cache-aware routing can prefer models with better caching support
