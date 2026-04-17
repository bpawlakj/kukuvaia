# P06: Content Moderation — Input/Output Filtering

**Created**: 2026-04-10
**Status**: Draft
**Module**: kukuvaia-core
**Depends on**: Existing advisor chain, SecurityConfig, PersonaService

---

## Problem

Kukuvaia currently has no content moderation for user input or LLM output. The existing security layer handles prompt injection (ToolResultSanitizingAdvisor), webhook payload sanitization (PayloadSanitizer), and error message scrubbing (ErrorSanitizer) — but none of these address:

- **Harmful or toxic content** in user messages or LLM responses
- **PII leakage** — emails, phone numbers, credit card numbers, SSNs appearing in LLM output
- **Policy violations** — content that violates organizational or compliance policies
- **Per-persona strictness** — a medical assistant persona needs stricter moderation than a general coding assistant

Without content moderation, the platform is unsuitable for multi-user deployments, enterprise environments, or any context where compliance or safety policies apply.

---

## Current State

| Component | What it does | What it does NOT do |
|-----------|-------------|---------------------|
| `ToolResultSanitizingAdvisor` | Prevents prompt injection from tool results | Does not check for harmful/toxic content |
| `PayloadSanitizer` | Filters webhook payloads via allowlisted fields | Does not scan for PII or harmful content |
| `ErrorSanitizer` | Strips credentials, paths, tokens from errors | Does not mask PII in normal responses |
| `SecurityConfig` | Spring Security filter chain | No content-level filtering |
| `PersonaSpec` | name, description, systemPrompt, toolFilter | No strictness level or moderation config |

Advisor chain ordering (current):
```
ProviderAuditLog          (HIGHEST_PRECEDENCE)      — logs provider/model
ToolResultSanitizingAdvisor (HIGHEST_PRECEDENCE + 1) — anti-injection
SmartMemoryAdvisor          (HIGHEST_PRECEDENCE + 5) — memory injection
IntentDetectionAdvisor      (HIGHEST_PRECEDENCE + 10) — intent classification
TokenBudgetAdvisor          (HIGHEST_PRECEDENCE + 20) — budget warnings
MessageChatMemoryAdvisor    (default)                 — conversation history
ToolHookDispatcher          (LOWEST_PRECEDENCE)       — audit hooks
```

---

## Architecture

```
User input
  │
  ▼
┌─────────────────────────────────────────────┐
│           ContentModerationAdvisor           │
│         (HIGHEST_PRECEDENCE + 2)             │
│                                              │
│  before():                                   │
│    ├── ModerationStrategy.check(userMsg)     │
│    │     ├── KeywordBlocklistModerator        │
│    │     ├── PiiDetector                      │
│    │     └── LlmJudgeModerator (optional)     │
│    ├── ModerationResult: PASS / FLAG / BLOCK │
│    ├── BLOCK → throw ModerationException     │
│    ├── FLAG  → log + inject warning          │
│    └── PASS  → continue chain                │
│                                              │
│  after():                                    │
│    ├── ModerationStrategy.check(llmOutput)   │
│    ├── PiiDetector.mask(llmOutput)           │
│    ├── BLOCK → replace response with safe msg│
│    ├── FLAG  → log + pass through            │
│    └── PASS  → pass through                  │
│                                              │
│  Strictness resolved per persona via config  │
└─────────────────────────────────────────────┘
  │
  ▼
(rest of advisor chain → LLM → response)
```

### Strategy Pattern

```
ModerationStrategy (interface)
  │
  ├── KeywordBlocklistModerator   — fast, regex-based, zero latency
  ├── PiiDetector                 — regex-based PII detection + masking
  └── LlmJudgeModerator          — LLM-as-judge (slow, optional, high accuracy)
```

All strategies run in sequence. First BLOCK wins. Results are aggregated.

---

## Implementation

### Step 1: ModerationResult and ModerationDecision model

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ModerationResult.java`

```java
package ai.kukuvaia.moderation;

import java.util.List;

/**
 * Aggregated moderation decision from all strategies.
 */
public record ModerationResult(
        Decision decision,
        List<ModerationFlag> flags
) {
    public enum Decision { PASS, FLAG, BLOCK }

    public record ModerationFlag(
            String strategy,    // "keyword_blocklist", "pii_detector", "llm_judge"
            String category,    // "toxic", "pii_email", "pii_phone", "harmful", "policy"
            String detail,      // human-readable reason
            Decision severity   // this flag's individual severity
    ) {}

    public static ModerationResult pass() {
        return new ModerationResult(Decision.PASS, List.of());
    }

    public static ModerationResult blocked(ModerationFlag flag) {
        return new ModerationResult(Decision.BLOCK, List.of(flag));
    }
}
```

### Step 2: ModerationStrategy interface

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ModerationStrategy.java`

```java
package ai.kukuvaia.moderation;

/**
 * Pluggable content moderation strategy.
 * Implementations must be stateless and thread-safe.
 */
public interface ModerationStrategy {

    /** Unique name for audit logging. */
    String name();

    /** Check content. Called for both input and output. */
    ModerationResult check(String content, ModerationContext context);

    /** Mask sensitive content in output. Default: no-op. */
    default String mask(String content, ModerationContext context) {
        return content;
    }
}
```

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ModerationContext.java`

```java
package ai.kukuvaia.moderation;

/**
 * Context passed to moderation strategies for per-session decisions.
 */
public record ModerationContext(
        String sessionId,
        String userId,
        String personaName,
        StrictnessLevel strictness,
        Direction direction       // INPUT or OUTPUT
) {
    public enum StrictnessLevel { RELAXED, STANDARD, STRICT }
    public enum Direction { INPUT, OUTPUT }
}
```

### Step 3: KeywordBlocklistModerator

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/KeywordBlocklistModerator.java`

Regex-based moderator loaded from a YAML blocklist file. Categories: `toxic`, `harmful`, `illegal`, `policy`. Each category has a list of patterns and a severity (FLAG or BLOCK).

Configuration loaded from `classpath:moderation/blocklist.yaml`:

```yaml
categories:
  toxic:
    severity: BLOCK
    patterns:
      - "\\b(slur1|slur2)\\b"    # example — actual list loaded from file
  harmful:
    severity: FLAG
    patterns:
      - "(?i)(how to (hack|exploit|bypass))"
  policy:
    severity: BLOCK
    patterns: []  # organization-specific, loaded from .kukuvaia/moderation/
```

Implementation:
- Compile patterns at startup (immutable `List<CompiledCategory>`)
- `check()`: iterate patterns, return first BLOCK or accumulate FLAGs
- Hot-reload via `.kukuvaia/moderation/blocklist.yaml` overlay (ExtensionLoader pattern)
- Zero external dependencies, sub-millisecond execution

### Step 4: PiiDetector

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/PiiDetector.java`

Regex-based PII detection and masking for common patterns:

```java
private static final Map<String, Pattern> PII_PATTERNS = Map.of(
    "email",       Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
    "phone_us",    Pattern.compile("\\b(\\+1[-.\\s]?)?\\(?\\d{3}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b"),
    "phone_intl",  Pattern.compile("\\b\\+\\d{1,3}[-.\\s]?\\d{4,14}\\b"),
    "credit_card", Pattern.compile("\\b\\d{4}[-.\\s]?\\d{4}[-.\\s]?\\d{4}[-.\\s]?\\d{4}\\b"),
    "ssn",         Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
    "iban",        Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{4,30}\\b")
);
```

Behavior:
- `check()`: detect PII presence, return FLAG with category (e.g., `pii_email`)
- `mask()`: replace detected PII with placeholder (`[EMAIL]`, `[PHONE]`, `[CREDIT_CARD]`, etc.)
- Input: FLAG only (user knowingly typed it)
- Output: mask always (LLM should not leak PII from tool results or training data)
- Strictness override: `STRICT` → BLOCK on any PII in output; `STANDARD` → mask; `RELAXED` → FLAG only

### Step 5: LlmJudgeModerator (optional, Phase 2)

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/LlmJudgeModerator.java`

Uses a separate LLM call (cheapest model via `LlmProviderService.resolveByRole("worker")`) to classify content:

```java
String judgePrompt = """
    Classify the following content. Respond with JSON:
    {"safe": true/false, "categories": ["toxic","harmful","pii","policy"], "reason": "..."}

    Content: %s
    """.formatted(truncated);
```

Guards:
- Only triggered when `kukuvaia.moderation.strategy` includes `llm_judge`
- Timeout: 5 seconds (fail-open: if LLM times out, PASS with warning log)
- Cached: hash-based dedup for repeated content within a session
- Cost: tracked via separate MDC context (`kukuvaia.specialist=moderation_judge`)

### Step 6: ContentModerationAdvisor

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ContentModerationAdvisor.java`

```java
@Component
public class ContentModerationAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContentModerationAdvisor.class);

    private final List<ModerationStrategy> strategies;
    private final ModerationConfig config;
    private final PersonaService personaService;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2; // After ProviderAuditLog, before SmartMemoryAdvisor
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!config.isEnabled()) return request;

        String sessionId = (String) request.context().getOrDefault("chat_memory_conversation_id", "default");
        String userId = (String) request.context().getOrDefault("kukuvaia.userId", "default");
        String userMessage = extractUserMessage(request);

        if (userMessage == null || userMessage.isBlank()) return request;

        ModerationContext ctx = buildContext(sessionId, userId, ModerationContext.Direction.INPUT);
        ModerationResult result = runStrategies(userMessage, ctx);

        logModerationDecision(result, sessionId, "input");

        if (result.decision() == ModerationResult.Decision.BLOCK) {
            throw new ModerationBlockedException(result.flags());
        }

        return request; // PASS or FLAG — continue
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (!config.isEnabled()) return response;
        // Extract LLM output text, run strategies with Direction.OUTPUT
        // PiiDetector.mask() applied to output
        // BLOCK → replace content with safe message
        // FLAG → log, pass through
        return response; // possibly with masked content
    }
}
```

### Step 7: ModerationConfig (configuration properties)

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ModerationConfig.java`

```java
@ConfigurationProperties(prefix = "kukuvaia.moderation")
public record ModerationConfig(
        boolean enabled,
        List<String> strategies,          // ["keyword_blocklist", "pii_detector", "llm_judge"]
        String defaultStrictness,         // "standard"
        Map<String, String> personaStrictness  // persona_name → strictness level override
) {
    public ModerationConfig {
        if (strategies == null) strategies = List.of("keyword_blocklist", "pii_detector");
        if (defaultStrictness == null) defaultStrictness = "standard";
        if (personaStrictness == null) personaStrictness = Map.of();
    }

    public ModerationContext.StrictnessLevel strictnessFor(String personaName) {
        String level = personaStrictness.getOrDefault(personaName, defaultStrictness);
        return ModerationContext.StrictnessLevel.valueOf(level.toUpperCase());
    }
}
```

### Step 8: ModerationBlockedException and error handling

**File**: `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ModerationBlockedException.java`

```java
public class ModerationBlockedException extends RuntimeException {
    private final List<ModerationResult.ModerationFlag> flags;

    public ModerationBlockedException(List<ModerationResult.ModerationFlag> flags) {
        super("Content blocked by moderation: " + flags.stream()
                .map(ModerationResult.ModerationFlag::category)
                .distinct().toList());
        this.flags = List.copyOf(flags);
    }

    public List<ModerationResult.ModerationFlag> flags() { return flags; }
}
```

Handled in `ErrorSanitizer`:
```java
@ExceptionHandler(ModerationBlockedException.class)
public ResponseEntity<Map<String, Object>> handleModeration(ModerationBlockedException ex) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of(
                    "error", "content_moderated",
                    "message", "Your message was blocked by content moderation.",
                    "categories", ex.flags().stream()
                            .map(ModerationResult.ModerationFlag::category).distinct().toList()
            ));
}
```

### Step 9: Audit logging

All moderation decisions are logged via SLF4J with MDC context:

```java
MDC.put("moderation.decision", result.decision().name());
MDC.put("moderation.categories", flagCategories);
MDC.put("moderation.direction", direction);
log.info("Moderation: decision={}, direction={}, flags={}", result.decision(), direction, flagCount);
```

For BLOCK decisions, a WARNING-level log includes the blocked categories (never the raw content).

### Step 10: Wire into advisor chain

**Modify**: `kukuvaia-core/src/main/java/ai/kukuvaia/config/ChatClientConfig.java`

Add `ContentModerationAdvisor` to `defaultAdvisors()` list between `toolResultSanitizingAdvisor` and `smartMemoryAdvisor`.

---

## Configuration

### application.yaml additions

```yaml
kukuvaia:
  moderation:
    enabled: ${KUKUVAIA_MODERATION_ENABLED:false}
    strategies:
      - keyword_blocklist
      - pii_detector
      # - llm_judge     # uncomment for LLM-based moderation (adds latency + cost)
    default-strictness: standard    # relaxed | standard | strict
    persona-strictness:
      medical-assistant: strict
      coding-assistant: relaxed
```

### Per-persona override via persona YAML

```yaml
# .kukuvaia/personas/medical.yaml
name: medical-assistant
description: Medical information assistant
systemPrompt: "You are a medical information assistant..."
toolFilter: []
moderationStrictness: strict    # overrides default-strictness
```

---

## Dependencies

| Dependency | Purpose | New? |
|-----------|---------|------|
| Spring AI BaseAdvisor | Advisor interface | Existing |
| PersonaService | Resolve active persona for strictness | Existing |
| LlmProviderService | LlmJudgeModerator needs cheap model | Existing (Phase 2 only) |
| Jackson YAML | Parse blocklist.yaml | Existing |

No new external dependencies. All moderators use JDK regex and existing Spring components.

---

## File Inventory

### New files (10)

| File | Description |
|------|-------------|
| `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ModerationResult.java` | Decision record (PASS/FLAG/BLOCK) |
| `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ModerationStrategy.java` | Strategy interface |
| `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ModerationContext.java` | Context record (session, persona, strictness) |
| `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ModerationConfig.java` | @ConfigurationProperties |
| `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ContentModerationAdvisor.java` | BaseAdvisor — main orchestrator |
| `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/KeywordBlocklistModerator.java` | Regex-based keyword blocker |
| `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/PiiDetector.java` | PII detection + masking |
| `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/LlmJudgeModerator.java` | LLM-as-judge (Phase 2) |
| `kukuvaia-core/src/main/java/ai/kukuvaia/moderation/ModerationBlockedException.java` | Typed exception |
| `kukuvaia-core/src/main/resources/moderation/blocklist.yaml` | Default keyword blocklist |

### Modified files (3)

| File | Change |
|------|--------|
| `kukuvaia-core/src/main/java/ai/kukuvaia/config/ChatClientConfig.java` | Add ContentModerationAdvisor to chain |
| `kukuvaia-core/src/main/java/ai/kukuvaia/security/ErrorSanitizer.java` | Add ModerationBlockedException handler |
| `kukuvaia-app/src/main/resources/application.yaml` | Add kukuvaia.moderation.* section |

### Test files (4)

| File | What it tests |
|------|--------------|
| `kukuvaia-core/src/test/java/ai/kukuvaia/moderation/KeywordBlocklistModeratorTest.java` | Pattern matching, category detection, edge cases |
| `kukuvaia-core/src/test/java/ai/kukuvaia/moderation/PiiDetectorTest.java` | Detection of emails, phones, CC numbers, SSNs; masking output |
| `kukuvaia-core/src/test/java/ai/kukuvaia/moderation/ContentModerationAdvisorTest.java` | Strategy orchestration, PASS/FLAG/BLOCK flow, persona strictness |
| `kukuvaia-core/src/test/java/ai/kukuvaia/moderation/ModerationConfigTest.java` | Config parsing, strictness resolution |

---

## Verification

### Unit tests

```bash
./gradlew :kukuvaia-core:test --tests "ai.kukuvaia.moderation.*"
```

Expected:
- `KeywordBlocklistModeratorTest`: blocklisted words → BLOCK, clean text → PASS, edge cases (partial matches, Unicode)
- `PiiDetectorTest`: emails, US phones, international phones, credit cards, SSNs detected and masked correctly
- `ContentModerationAdvisorTest`: disabled config → pass-through; BLOCK input → exception; FLAG input → logged; output PII → masked
- `ModerationConfigTest`: persona strictness override, default fallback, empty config defaults

### Integration test

```bash
./gradlew :kukuvaia-core:test --tests "ai.kukuvaia.moderation.ContentModerationAdvisorIntegrationTest"
```

Verify with mocked ChatModel:
1. Send message with blocklisted keyword → 422 response with `content_moderated` error
2. Send clean message → 200 with normal response
3. LLM returns email address in output → email masked as `[EMAIL]` in response
4. Switch persona to strict → previously flagged content now blocked

### Manual verification

```bash
# Start server with moderation enabled
KUKUVAIA_MODERATION_ENABLED=true ./gradlew :kukuvaia-app:bootRun

# Test blocked content
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"test","message":"<blocked keyword test>"}'
# Expected: 422 with content_moderated error

# Test PII masking in output — ask LLM to generate sample contact info
# Expected: emails/phones in output replaced with [EMAIL], [PHONE]

# Check logs for moderation audit entries
grep "Moderation:" logs/kukuvaia.log
```

---

## Effort Estimate

| Phase | Scope | Effort |
|-------|-------|--------|
| Phase 1 | Model records + strategy interface + ModerationConfig | 0.5 day |
| Phase 2 | KeywordBlocklistModerator + blocklist.yaml | 1 day |
| Phase 3 | PiiDetector (detection + masking) | 1 day |
| Phase 4 | ContentModerationAdvisor (before + after) | 1 day |
| Phase 5 | Wire into ChatClientConfig + ErrorSanitizer | 0.5 day |
| Phase 6 | Tests (unit + integration) | 1.5 days |
| Phase 7 | LlmJudgeModerator (optional) | 1.5 days |
| **Total (without LLM judge)** | | **5.5 days** |
| **Total (with LLM judge)** | | **7 days** |

---

## Priority & Prerequisites

**Priority**: Medium-High — required before multi-user deployment or enterprise adoption.

**Prerequisites**:
- Existing advisor chain operational (already done)
- PersonaService with persona loading (already done)
- For LlmJudgeModerator: DB-backed model registry with "worker" role assigned (P06 Phase 2 depends on model-routing-and-embabel.md Phase 2)

**Blocked by**: Nothing — core moderation (keyword + PII) can be built immediately.

**Blocks**:
- Multi-user deployment readiness
- Compliance certifications (SOC2, HIPAA)
- Enterprise persona configurations
