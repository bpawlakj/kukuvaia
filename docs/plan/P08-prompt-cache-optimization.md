# P08: Prompt Cache Optimization — Cache-Aware Prompt Construction

**Created**: 2026-04-10
**Updated**: 2026-04-19 — realigned to current advisor chain, role strategy, provider matrix
**Status**: Draft — ready for implementation
**Module**: kukuvaia-core, kukuvaia-memory
**Depends on**: ChatClientConfig, SmartMemoryAdvisor, PersonaService, SystemPromptBuilder (currently orphan — see §SystemPromptBuilder), SessionContextAdvisor (added 2026-04-19), PlanningModeService

---

## Role strategy — foundational decision

**Supervisor MUST be a cache-supporting model.** The cost and latency of every turn is dominated by the supervisor's full system prompt (~30K tokens with 100-message history). Without caching, each turn re-processes this prefix — unacceptable at production volume.

Consequences:

- **Supervisor-tier model** (role `supervisor` in `kukuvaia.model_roles`): Claude Sonnet 4.5, GPT-4.1, Elephant Alpha, or any model with prefix caching. **Must** advertise cache support.
- **Worker-tier model** (role `worker`): free to be a non-caching small/fast model like `mistralai/mistral-small-2603`. Workers handle narrow delegated subtasks with small, ephemeral prompts where cache payoff is minimal.
- **Advisor-tier model** (role `advisor`, ESCALATE path): cache support preferred but optional — advisor calls are rare and context is smaller.

This plan optimises the supervisor prompt structure. Worker prompts are left unchanged: they are short-lived single-turn interactions where caching overhead exceeds benefit.

## Cache activation — per-model flag (NOT hardcoded)

**Decision**: each model in `kukuvaia.models` gets a `cache_capable BOOLEAN` column, set by the operator when the model is registered. The entire P08 pipeline (prompt ordering breakpoints, interceptor markers, monitoring) is gated by this flag per-request at runtime.

Why this over a hardcoded allow-list:

1. **New models appear faster than we can hardcode** — Gemini 3.x, Elephant Alpha, DeepSeek V4 all launched recently. Hardcoded list rots.
2. **Provider nuances** — same model via different providers may or may not cache (OpenRouter passthrough depends on the underlying provider's configuration).
3. **Operator knows the facts** — when registering a model they have already tested it or read the provider docs. That knowledge belongs in the registration step, not in engine code.
4. **Rollback at model granularity** — if a model's caching turns out to be flaky, flip `cache_capable=false` in one row and everything reverts for that model only, no redeploy.

The `CacheConfig` global switch (`kukuvaia.cache.enabled`) becomes the master kill switch; `cache_capable` on each model is the fine-grained per-model gate. Both must be true for P08 logic to fire.

---

## Problem

LLM providers implement prompt caching (Anthropic's `cache_control` / prefix caching, OpenAI's automatic prefix caching, DeepSeek's context cache) that reduces cost and latency for repeated prompt prefixes. Anthropic charges 90% less for cached input tokens and serves them ~5× faster. OpenAI provides 50% discount on cached tokens.

Kukuvaia's current prompt construction is cache-unaware:

- **Advisor ordering** was designed for correctness, not cache efficiency.
- **Dynamic content** (memories, session context, conversation history) mixes with **static content** (persona, security rules, planning instructions) in an order that breaks prefix caching.
- **No `cache_control` markers** are sent for Anthropic models.
- **No monitoring** of cache hit rates exists.
- **`SystemPromptBuilder` is orphan** — the class exists but is never invoked by any advisor or service. Its `PLANNING_INSTRUCTIONS` and `VERIFICATION_MANDATE` constants are dead code.

At production volume (100+ turns/day, Claude Sonnet), the status quo wastes $5–$10/day in avoidable input token costs.

---

## Current state (2026-04-19)

### Advisor chain order

Verified from `ChatClientConfig.chatClient()`. Lower `@Order` value = runs earlier.

| Order | Advisor | Effect on prompt |
|-------|---------|------------------|
| `HP + 0` | `ProviderAuditLog` | None (logs only, stores Timer.Sample in context) |
| `HP + 5` | `SmartMemoryAdvisor` | Appends memory block via `augmentSystemMessage` |
| `HP + 12` | `ModelRoutingAdvisor` | Swaps model, does NOT modify system prompt text |
| `HP + 13` | `LoopDetectionAdvisor` | None on `before()` path |
| `HP + 15` | `PlanningModeService` | Injects phase-specific prompt + filters tools (DISCOVERY/DRAFTING/APPROVAL) |
| `HP + 20` | `SessionContextAdvisor` (added 2026-04-19) | Injects plans + commitments block |
| `default` | `DataMaskingAdvisor` | Masks PII in-place, does not change prompt structure |
| `default` | `HarnessAdvisor` | Injects user rules from `.kukuvaia/rules/` |
| `default` | `ToolResultSanitizingAdvisor` | Appends `SAFETY_SUFFIX` (static) |
| `default` | `MessageChatMemoryAdvisor` | Prepends conversation history (last N messages, N=100) |
| `default` | `ToolCallAdvisor` | Runs tool loop, feeds tool responses back |

### Why current order is cache-hostile

Because advisors use `augmentSystemMessage` (which inserts at the END of existing system message) and run in decreasing-order priority, the final token stream looks roughly:

```
[persona_prompt]                          ← STATIC
[MessageChatMemory injects history]       ← DYNAMIC
[ToolResultSanitizing SAFETY_SUFFIX]      ← STATIC
[HarnessAdvisor user rules]               ← SEMI-STATIC
[SessionContextAdvisor plans+commitments] ← DYNAMIC per turn
[PlanningModeService phase prompt]        ← DYNAMIC per phase
[SmartMemoryAdvisor memories]             ← DYNAMIC per query (top-K semantic)
[user_message]                            ← DYNAMIC
```

The STATIC content is NOT at the start → prefix cache cannot lock onto it.

### Target ordering

```
┌─────────────────────────────────────────────────────────┐
│  ZONE 1: STATIC PREFIX — identical across all sessions  │
│    - Planning instructions (SystemPromptBuilder)        │
│    - Verification mandate (SystemPromptBuilder)         │
│    - Security boundary (ToolResultSanitizingAdvisor)    │
│  ↑ cache_control breakpoint 1                           │
├─────────────────────────────────────────────────────────┤
│  ZONE 2: SEMI-STATIC — per persona/installation         │
│    - Persona system prompt                              │
│    - User rules (.kukuvaia/rules/) via HarnessAdvisor   │
│    - Active skill (if any)                              │
│  ↑ cache_control breakpoint 2                           │
├─────────────────────────────────────────────────────────┤
│  ZONE 3: SEMI-DYNAMIC — stable within session window    │
│    - Persistent memories (SmartMemoryAdvisor, TTL-cached)│
│    - Session context: plans+commitments                 │
│      (SessionContextAdvisor, TTL-cached)                │
│    - Planning phase prompt (if in planning mode)        │
│  ↑ cache_control breakpoint 3 (optional, Anthropic only)│
├─────────────────────────────────────────────────────────┤
│  ZONE 4: DYNAMIC — changes every turn                   │
│    - Conversation history (MessageChatMemoryAdvisor)    │
│    - User message                                       │
└─────────────────────────────────────────────────────────┘
```

---

## SystemPromptBuilder — current orphan resolution

**Finding**: `SystemPromptBuilder` class exists (`agent/SystemPromptBuilder.java`) with `PLANNING_INSTRUCTIONS`, `VERIFICATION_MANDATE`, `STATIC_BOUNDARY` constants, but is NOT referenced by any other code. `AgentService.chat()` passes `persona.systemPrompt()` directly to `ChatClient.prompt().system(...)`.

**Options**:

1. **Inline the constants** into `CacheAwarePromptAdvisor` (new class from Step 1). Simpler. Deletes `SystemPromptBuilder` entirely.
2. **Keep SystemPromptBuilder as utility**, make constants `public`, and call it from `CacheAwarePromptAdvisor`. Preserves separation of concerns for future rules loader integration.

**Decision (this plan)**: Option 2. Keeps `RulesLoader` integration point intact. `SystemPromptBuilder.build()` becomes an internal helper; new public methods `staticPrefixTokens()`, `semiStaticSection(persona, skill)` split the assembly into zones.

---

## Provider compatibility matrix

| Provider | Automatic prefix cache | Explicit `cache_control` | Monitoring field | Discount |
|----------|------------------------|--------------------------|------------------|----------|
| OpenAI (direct) | ✅ yes | — | `usage.prompt_tokens_details.cached_tokens` | 50% |
| Anthropic (direct) | ❌ | ✅ required | `usage.cache_read_input_tokens`, `usage.cache_creation_input_tokens` | 90% (read), 125% (creation) |
| Anthropic via Spring AI `OpenAiApi` | — | ❌ **not supported** — needs RestClient interceptor | See below | — |
| OpenRouter (passthrough) | Depends on underlying model | Depends on underlying model | Exposes provider fields | Variable |
| Elephant Alpha (OpenRouter) | ✅ (provider page) | Likely ignored | Via `prompt_tokens_details` | Provider-defined |
| DeepSeek | ✅ (automatic context cache) | — | `usage.prompt_cache_hit_tokens` | 90% |
| Mistral Small 2603 | ❌ (no cache at all) | — | — | 0% |
| Claude Sonnet 4.5 | — | ✅ required | See Anthropic | 90% |
| SmartGate (LiteLLM proxy) | **Unknown — probe required** | **Unknown** | **Unknown** | — |

### Spring AI 1.1 verified support

Confirmed by unpacking `spring-ai-openai:1.1.0`:

- ✅ `OpenAiApi.Usage.PromptTokensDetails.cachedTokens` exists — we can read cached-token counts from response
- ❌ No `cache_control` field on `OpenAiApi.ChatCompletionMessage` — Anthropic breakpoints require a RestClient interceptor or custom message JSON

### Implication for this plan

- **Step 3 (cache_control markers)** is **not a no-op rewrite** — it is a genuine RestClient interceptor task. See §Step 3 below.
- **Step 4 (monitoring)** works out of the box via Spring AI — just read `getMetadata().getUsage().getPromptTokensDetails().getCachedTokens()`.
- **Supervisor model selection** becomes a prerequisite: if the operator configures a non-caching model as supervisor, cache optimisation silently does nothing. Add a startup log warning.

---

## Implementation (updated)

### Step 0a: `cache_capable` column in `kukuvaia.models`

**File**: `kukuvaia-app/src/main/resources/db/migration/V14__add_cache_capable_to_models.sql`

```sql
-- P08: Per-model prompt cache capability flag.
-- Set by operator at model registration time; checked at runtime by CacheAwarePromptAdvisor.

ALTER TABLE kukuvaia.models
    ADD COLUMN cache_capable BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN kukuvaia.models.cache_capable IS
    'True when the provider supports prompt/prefix caching for this model. '
    'Source of truth for P08 activation per request. '
    'Operators update this when registering a model based on provider docs.';

-- Backfill known-caching models. Safe defaults: false means "no cache attempt".
UPDATE kukuvaia.models SET cache_capable = TRUE
WHERE model_id IN (
    'eu.anthropic.claude-sonnet-4-5-20250929-v1:0',
    'eu.anthropic.claude-opus-4-6-v1',
    'eu.anthropic.claude-haiku-4-5-20251001-v1:0',
    'openrouter/elephant-alpha',
    'google/gemini-2.5-flash',
    'google/gemini-2.5-pro'
    -- Extend as operator registers more models
);
```

After migration runs, new models default to `cache_capable=false`. Operator flips to `true` when registering a known-caching model — either via admin UI or direct SQL.

### Step 0b: `ModelRecord` + `ModelRepository` carry the flag

**Modify**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelRecord.java`

```java
public record ModelRecord(
        UUID id,
        UUID providerId,
        String modelId,
        String displayName,
        List<String> capabilities,
        String tier,
        int maxTokens,
        Integer contextWindow,
        boolean cacheCapable,          // NEW
        Instant discoveredAt
) {
    public ModelRecord {
        if (maxTokens <= 0) maxTokens = 4096;
    }
}
```

Update `ModelRepository`:
- `save(...)` — INSERT includes `cache_capable` column
- `findAll()` / `findById(...)` — SELECT reads it, RowMapper populates the field
- `update(...)` — UpdateModelRequest gets `Boolean cacheCapable` (optional, so operator can toggle)

Update `ProviderRegistryService.registerModel(...)` to accept `boolean cacheCapable` argument. `ProviderSeeder` (bootstrap) writes the flag for known models.

### Step 0c: Admin UI field (kukuvaia-admin)

**File**: `kukuvaia-admin/src/components/ModelForm.tsx` (or equivalent)

Add a labelled toggle:

```
[ ] Cache capable
    Check when the provider supports prompt/prefix caching for this model
    (Claude, Gemini 2.5+, DeepSeek, Elephant Alpha — not Mistral Small, Qwen,
    Nova-Micro, or plain Gemma).
```

Write through existing `PUT /api/providers/models/{id}` endpoint (`UpdateModelRequest` already covers this once `cacheCapable` is added).

### Step 0: SystemPromptBuilder wiring decision

Make `SystemPromptBuilder` constants `public` and split its `build()` method into two public helpers:

```java
public String staticPrefix();           // Zone 1 content (planning + verification + security)
public String semiStaticSection(PersonaSpec persona, String activeSkill, List<String> rules); // Zone 2
```

Delete the old two-argument `build(...)` methods after wiring — no caller today.

**Note**: if wiring turns out to be larger than 2h, switch to Option 1 (inline in `CacheAwarePromptAdvisor`) and remove `SystemPromptBuilder`.

### Step 1: `CacheAwarePromptAdvisor`

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/CacheAwarePromptAdvisor.java`

Runs at `HIGHEST_PRECEDENCE + 1` — BEFORE all other advisors that augment the system prompt. Replaces the persona-prompt-from-AgentService pattern with a structured Zone 1 + Zone 2 assembly.

**Per-model gating**: the advisor queries `kukuvaia.models.cache_capable` for the model actually routed to this request (`ModelRoutingAdvisor` sets `kukuvaia.routing.model` in context). If the flag is false OR master `kukuvaia.cache.enabled` is false, the advisor **still orders zones correctly** but **skips the CACHE_BREAKPOINT sentinels** — so the interceptor in Step 3 has no triggers and OpenAI-automatic caching simply runs as before. Downstream behaviour is unaffected.

Downstream advisors (`SmartMemoryAdvisor`, `SessionContextAdvisor`, `PlanningModeService`, `HarnessAdvisor`, `MessageChatMemoryAdvisor`) continue to run and append to Zone 3/4 as they do today — we don't rewrite them, we just make sure Zone 1+2 is already in place when they run.

```java
@Component
public class CacheAwarePromptAdvisor implements BaseAdvisor {

    public static final String CACHE_BREAKPOINT_1 = "\n<!-- cache_control: ephemeral #1 -->\n";
    public static final String CACHE_BREAKPOINT_2 = "\n<!-- cache_control: ephemeral #2 -->\n";

    private final PersonaService personaService;
    private final SystemPromptBuilder builder;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String sessionId = (String) request.context().getOrDefault("chat_memory_conversation_id", "default");
        var persona = personaService.getActivePersona(sessionId);
        String skill = (String) request.context().getOrDefault("kukuvaia.activeSkill", "");

        String zone1 = builder.staticPrefix();
        String zone2 = builder.semiStaticSection(persona, skill, List.of()); // rules added by HarnessAdvisor

        // Replace existing system message so downstream advisors' augmentSystemMessage
        // appends to the OUR cache-aware prefix.
        String combined = zone1 + CACHE_BREAKPOINT_1 + zone2 + CACHE_BREAKPOINT_2;

        return request.mutate()
                .prompt(request.prompt().mutateSystemMessage(combined))
                .context("kukuvaia.cacheAwarePrompt", true) // flag for downstream
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }
}
```

### Step 2: Downstream advisors cooperate with Zone 1+2

Each advisor that currently calls `augmentSystemMessage` in its `before()` is audited:

| Advisor | Current behaviour | Change |
|---------|-------------------|--------|
| `ToolResultSanitizingAdvisor` | Appends `SAFETY_SUFFIX` | Check `kukuvaia.cacheAwarePrompt` flag; if set, skip — CacheAware already put SAFETY_SUFFIX in Zone 1 via `SystemPromptBuilder.staticPrefix()` |
| `HarnessAdvisor` | Injects user rules | Check flag; if set, append to Zone 2 (still fine as it runs after CacheAware) |
| `SessionContextAdvisor` | Injects plans+commitments | No change — by design Zone 3 content |
| `PlanningModeService` | Injects phase prompt | No change — Zone 3 |
| `SmartMemoryAdvisor` | Injects memories | No change — Zone 3 |

Net effect: downstream advisors remain untouched except `ToolResultSanitizingAdvisor` which gets a single if-block for its `SAFETY_SUFFIX` duplication.

**Helper**: a small `ModelCacheCapabilityService` wraps `ModelRepository` with an in-memory cache (5 s TTL) keyed by model id — keeps the per-request lookup at nanosecond cost. All advisors that need the flag read from this service, never hit the DB directly.

### Step 3: Anthropic `cache_control` via RestClient interceptor

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/CacheControlInterceptor.java`

Spring AI's OpenAiApi does not expose `cache_control` on `ChatCompletionMessage`. Workaround: intercept the serialised HTTP request body, inject `cache_control` objects into the Anthropic-bound JSON before it leaves the client.

```java
@Component
@ConditionalOnProperty("kukuvaia.cache.anthropic-markers")
public class CacheControlInterceptor implements ClientHttpRequestInterceptor {
    private final ObjectMapper mapper;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution ex) throws IOException {
        if (!looksLikeAnthropic(request)) {
            return ex.execute(request, body);
        }
        byte[] modified = injectCacheBreakpoints(body);
        return ex.execute(request, modified);
    }

    private byte[] injectCacheBreakpoints(byte[] body) throws IOException {
        JsonNode root = mapper.readTree(body);
        ArrayNode messages = (ArrayNode) root.get("messages");
        if (messages == null) return body;
        for (JsonNode msg : messages) {
            if (!"system".equals(msg.path("role").asText())) continue;
            String content = msg.path("content").asText();
            // Find our sentinel CacheAwarePromptAdvisor.CACHE_BREAKPOINT_1/_2
            // Replace system content with an array of blocks with cache_control
            // on blocks ending at each breakpoint.
            // Rewrite msg to match Anthropic's content-blocks schema.
        }
        return mapper.writeValueAsBytes(root);
    }
}
```

Wired into `ChatModelFactory.build()` via `restClientBuilder.interceptors(cacheControlInterceptor)`.

Gated by `kukuvaia.cache.anthropic-markers=true` so it can be toggled per deployment without code change.

### Step 4: `CacheMonitoringAdvisor`

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/CacheMonitoringAdvisor.java`

Reads `chatResponse.getMetadata().getUsage()` in `after()`, casts to `OpenAiApi.Usage`, extracts `PromptTokensDetails.cachedTokens`. Emits Micrometer metrics:

- `kukuvaia.llm.cache.hits` — counter (tagged `provider`, `model`)
- `kukuvaia.llm.cache.misses` — counter
- `kukuvaia.llm.cache.cached_tokens` — counter
- `kukuvaia.llm.cache.hit_ratio` — gauge (5-minute sliding window)

Also emits `SpanEventBlock` attribute on the existing `role:supervisor` span: `kukuvaia.cache.hit=true/false`, `kukuvaia.cache.tokens=N`.

### Step 5: `SmartMemoryAdvisor` TTL cache

**Modify**: `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/advisor/SmartMemoryAdvisor.java`

Add a per-session `CachedMemoryBlock` with TTL (default 60s). Within TTL, re-inject the identical memory block — keeps Zone 3 prefix stable, maximising cross-turn cache hits.

```java
private record CachedMemoryBlock(String block, long timestamp) {}
private final Map<String, CachedMemoryBlock> memoryCache = new ConcurrentHashMap<>();

@Value("${kukuvaia.cache.memory-block-ttl-seconds:60}")
private int ttlSeconds;
```

Applied only when `kukuvaia.cache.enabled=true` (master switch).

### Step 6: `SessionContextAdvisor` TTL cache

**Modify**: `SessionContextAdvisor` — same TTL pattern as `SmartMemoryAdvisor`. Plans and commitments rarely change within a 60s window; re-fetching on every turn defeats the Zone 3 cache boundary.

Invalidate cache when `/plan`, `/todo add|done|drop` slash commands run — they mutate the underlying data.

### Step 7: `CacheConfig` + startup warning (DB-driven)

```java
@ConfigurationProperties(prefix = "kukuvaia.cache")
public record CacheConfig(
        boolean enabled,
        boolean anthropicMarkers,
        int memoryBlockTtlSeconds,
        int sessionContextTtlSeconds
) {}
```

Startup warning is **driven by the `cache_capable` column**, not a hardcoded list.

```java
@EventListener(ApplicationReadyEvent.class)
void checkSupervisorCacheCapability() {
    if (!config.enabled()) return;
    String role = "supervisor";
    ModelRecord supervisor = modelRepository.findByRole(role).orElse(null);
    if (supervisor == null) {
        log.warn("No model assigned to role '{}' — cache optimisation inactive", role);
        return;
    }
    if (!supervisor.cacheCapable()) {
        log.warn("""
                Prompt cache is enabled but the supervisor model '{}' has cache_capable=false.
                P08 optimisation will be a no-op for supervisor turns.
                Either flip the flag (if the provider does support caching) or switch supervisor
                to a cache-capable model.""",
                supervisor.modelId());
    } else {
        log.info("Prompt cache active for supervisor model '{}'", supervisor.modelId());
    }
}
```

Optionally extend the check to `advisor` role too (same rules, lower severity since rare path).

---

## Configuration (final)

```yaml
kukuvaia:
  cache:
    enabled: ${KUKUVAIA_PROMPT_CACHE:true}
    anthropic-markers: ${KUKUVAIA_ANTHROPIC_CACHE_MARKERS:false}
    memory-block-ttl-seconds: 60
    session-context-ttl-seconds: 60
```

---

## File inventory (updated)

### New (6)

| File | Effort |
|------|--------|
| `db/migration/V14__add_cache_capable_to_models.sql` | 0.1 day |
| `provider/ModelCacheCapabilityService.java` (5s TTL cache over ModelRepository) | 0.25 day |
| `advisors/CacheAwarePromptAdvisor.java` | 0.5 day |
| `advisors/CacheMonitoringAdvisor.java` | 0.5 day |
| `advisors/CacheConfig.java` | 0.25 day |
| `provider/CacheControlInterceptor.java` | 1 day (Anthropic only) |

### Modified (9)

| File | Change | Effort |
|------|--------|--------|
| `provider/registry/ModelRecord.java` | Add `boolean cacheCapable` field | 0.1 day |
| `provider/registry/ModelRepository.java` | SELECT/INSERT/UPDATE handle new column | 0.25 day |
| `provider/registry/UpdateModelRequest.java` | Add `Boolean cacheCapable` optional param | 0.1 day |
| `provider/registry/ProviderRegistryService.java` | Register-model signature carries the flag | 0.1 day |
| `provider/registry/ProviderSeeder.java` | Seed known-caching models with true | 0.1 day |
| `agent/SystemPromptBuilder.java` | Make public, add `staticPrefix()` / `semiStaticSection()` | 0.5 day |
| `security/ToolResultSanitizingAdvisor.java` | Conditional `augmentSystemMessage` | 0.25 day |
| `memory/advisor/SmartMemoryAdvisor.java` | TTL cache (gated by model's cache_capable) | 0.5 day |
| `advisors/SessionContextAdvisor.java` | TTL cache + invalidation hooks (gated) | 0.5 day |
| `config/ChatClientConfig.java` | Register new advisor | 0.1 day |
| `app/application.yaml` | `kukuvaia.cache.*` section | 0.1 day |
| `admin/src/components/ModelForm.tsx` | Cache-capable toggle in UI | 0.25 day |

### Test (4)

| File | What it tests | Effort |
|------|--------------|--------|
| `advisors/CacheAwarePromptAdvisorTest.java` | Zone 1+2 ordering, breakpoint placement | 0.5 day |
| `advisors/CacheMonitoringAdvisorTest.java` | Cached-token extraction from `PromptTokensDetails` | 0.5 day |
| `memory/advisor/SmartMemoryAdvisorCacheTest.java` | TTL honored, invalidation correct | 0.5 day |
| `provider/CacheControlInterceptorTest.java` | JSON rewrite targets system messages only | 0.5 day |

---

## Effort (realistic)

| Phase | Effort |
|-------|--------|
| Step 0a: V14 migration + backfill | 0.1 day |
| Step 0b: `ModelRecord` + repository + registry wiring | 0.4 day |
| Step 0c: Admin UI toggle | 0.25 day |
| Step 0: SystemPromptBuilder wiring | 0.5 day |
| Step 1: CacheAwarePromptAdvisor + ModelCacheCapabilityService | 0.6 day |
| Step 2: Downstream advisor audit + conditional | 0.5 day |
| Step 3: Anthropic RestClient interceptor | 1 day |
| Step 4: Monitoring advisor | 0.5 day |
| Step 5: SmartMemory TTL (gated) | 0.5 day |
| Step 6: SessionContext TTL + invalidation (gated) | 0.5 day |
| Step 7: CacheConfig + DB-driven startup warning | 0.25 day |
| Tests + integration (positive + negative) | 1.5 days |
| **Total** | **~6.6 days** |

**AI-paired realistic estimate** (per recent calibration memory): ~**5–7 hours** for core (Steps 0a/0b/0 + 1–2 + 4–7 + basic tests), Step 3 Anthropic interceptor adds ~1–2 h, Step 0c Admin UI adds ~20 min.

---

## Dependencies & blockers

| Item | Status | Blocks implementation? |
|------|--------|-----------------------|
| Spring AI 1.1 `PromptTokensDetails.cachedTokens` | ✅ confirmed | No |
| Spring AI 1.1 `cache_control` native support | ❌ missing | Only Anthropic step — workaround via interceptor |
| `ChatClientConfig` advisor chain operational | ✅ | No |
| `SmartMemoryAdvisor` operational | ✅ | No |
| `SessionContextAdvisor` operational | ✅ (added 2026-04-19) | No |
| `PersonaService` operational | ✅ | No |
| `SystemPromptBuilder` wired | ❌ (orphan) | Resolved by Step 0 |
| Supervisor model set to caching-capable | ⚠️ operator action required | No, but silently no-op if not done |
| P01 observability (for cache metrics) | ✅ Stage A+B shipped | No |

**No hard blockers.** Step 0 (SystemPromptBuilder wiring) and operator model selection are the only gotchas.

---

## Acceptance criteria

- [ ] Step 0a: V14 migration adds `cache_capable BOOLEAN NOT NULL DEFAULT FALSE` column; backfills listed caching models; `\d+ kukuvaia.models` shows the column.
- [ ] Step 0b: `ModelRecord` carries `cacheCapable`; `findById(uuid).cacheCapable()` returns persisted value; UPDATE via `ProviderRegistryService` toggles it.
- [ ] Step 0c: Admin UI model form has `cache_capable` toggle; saving through `PUT /api/providers/models/{id}` persists it.
- [ ] Step 0: `SystemPromptBuilder` has public `staticPrefix()` / `semiStaticSection()` methods, referenced from `CacheAwarePromptAdvisor`. Dead `build()` overloads removed.
- [ ] Step 1: `CacheAwarePromptAdvisor` injects Zone 1+2 as the system message prefix. When routed model has `cache_capable=false`, CACHE_BREAKPOINT sentinels are absent but zone ordering stays correct.
- [ ] Step 2: `ToolResultSanitizingAdvisor.SAFETY_SUFFIX` does not appear twice when `CacheAwarePromptAdvisor` is active.
- [ ] Step 3: With `kukuvaia.cache.anthropic-markers=true`, `cache_capable=true` on a Claude model, AND an Anthropic-routed request → captured HTTP body contains `cache_control` blocks at Zone 1/2 breakpoints.
- [ ] Step 4: After a chat turn with a `cache_capable=true` model, `/actuator/prometheus` exposes `kukuvaia_llm_cache_*` metrics with non-zero values on the second identical prompt. With `cache_capable=false`, metrics report zero hits.
- [ ] Step 5: `SmartMemoryAdvisor` returns the identical memory block within TTL; DB-hit stops after first call. Skipped entirely when active model has `cache_capable=false` (no payoff).
- [ ] Step 6: `SessionContextAdvisor` respects TTL; `/plan`/`/todo` invocations invalidate the session's cached block. Skipped when `cache_capable=false`.
- [ ] Step 7: Startup warning prints when supervisor role model has `cache_capable=false` AND `kukuvaia.cache.enabled=true`.
- [ ] Integration: 10 consecutive chat turns in same session with a `cache_capable=true` model → `kukuvaia_llm_cache_hit_ratio` ≥ 0.7.
- [ ] Integration (negative): 10 turns with `cache_capable=false` model → zero errors, zero cache attempts, zero performance regression vs pre-P08 baseline.

---

## Role selection table (operator guidance)

When operator picks which model goes into which role in `kukuvaia.model_roles`:

| Role | Cache expectation | Recommended models |
|------|-------------------|---------------------|
| `supervisor` | **MUST cache** — high volume, full prompt every turn | Claude Sonnet 4.5, GPT-4.1, Elephant Alpha, DeepSeek V3, Claude Opus 4.6 |
| `advisor` | Preferred but optional — rare ESCALATE path | Claude Opus 4.6 (best reasoning + cache), GPT-4.1 |
| `worker` | Cache not required — delegated subtasks are short-lived | mistralai/mistral-small-2603, qwen3.5-9b, Mistral via any provider |
| `worker-2` | Same as worker — second parallel worker | Different model than worker for diversity (gemma, qwen, etc.) |

Startup warning (Step 7) enforces this only for `supervisor`; workers run without warning.

---

## Rollback

Two levels of gating.

**Global kill switch** (operator action, no restart required for most changes):
- `kukuvaia.cache.enabled=false` → `CacheAwarePromptAdvisor`, `CacheMonitoringAdvisor`, TTL caches all become no-ops; ordering reverts to pre-P08 behaviour via `ToolResultSanitizingAdvisor`'s unconditional path.
- `kukuvaia.cache.anthropic-markers=false` → interceptor not registered; Anthropic-bound requests go through unmodified.

**Per-model kill switch** (DB update, no restart):
- `UPDATE kukuvaia.models SET cache_capable = FALSE WHERE model_id = '…';` → all P08 logic becomes no-op for this specific model across all sessions. `ModelCacheCapabilityService` TTL cache picks up the change in ≤ 5 s.

**Full revert**:
- Git: remove 6 new files + unwind 12 modified files.
- DB: Flyway V14 down migration drops the `cache_capable` column. Safe — column is nullable-defaulted, no app code crashes if it disappears (rollback assumed to happen together with code).

---

## Synergies

- **P01 Observability**: `kukuvaia.llm.cache.*` metrics plug into existing Prometheus exposure and Grafana dashboards.
- **P07 Cost Tracking**: cache hit ratios feed directly into cost-savings reports.
- **P14 Tiered Context**: context budget reduction compounds with cache hits — smaller prompt × higher hit ratio = major cost win.
- **P16 Stepped Reasoning**: reasoning-step spans can report per-step cache hits for fine-grained debugging.

## Non-synergies / explicit out-of-scope

- Does NOT reduce token usage on workers (delegated calls have fresh ephemeral prompts).
- Does NOT cache tool results — those are per-call.
- Does NOT change conversation history truncation logic (`MessageWindowChatMemory.maxMessages=100`).

---

## Trigger for activation

Given the recent AI-paired implementation speed calibration, activate immediately after supervisor model is pinned to a caching-capable one. Trigger check:

```sql
SELECT m.model_id
FROM kukuvaia.model_roles mr
JOIN kukuvaia.models m ON mr.model_id = m.id
WHERE mr.role = 'supervisor';
```

If result ∈ {claude-sonnet-4.5, claude-opus-4.6, gpt-4.1, elephant-alpha, deepseek-v3} → **ACTIVATE P08**. If result ∈ {mistral-small, qwen, nova-micro} → first switch supervisor, THEN activate P08.
