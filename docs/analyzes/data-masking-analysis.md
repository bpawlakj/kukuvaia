# Analysis: Sensitive Data Masking Before LLM Submission

**Created**: 2026-04-12
**Status**: Concept analysis

## Concept

Selective masking (not encryption) of sensitive data before sending to LLM providers. Replace PII with tokens, LLM works on tokens, restore original values in the response.

```
Before LLM:    "Patient Jan Kowalski (PESEL: 89012345678) has type 2 diabetes"
After masking:  "Patient [PERSON_1] (PESEL: [ID_1]) has type 2 diabetes"

LLM responds:   "[PERSON_1] should monitor blood sugar levels..."

After unmask:   "Jan Kowalski should monitor blood sugar levels..."
```

LLM never sees real data. Response is correct because medical/business context is preserved.

## Why Masking, Not Encryption

| Approach | LLM can work? | Data protected? | Reversible? |
|----------|:------------:|:--------------:|:-----------:|
| **Encryption** (AES, RSA) | No — ciphertext is gibberish | Yes | Yes |
| **Hashing** (SHA-256) | No — hash is gibberish | Yes | No |
| **Masking** (token replacement) | Yes — context preserved | Yes | Yes |
| **Redaction** (remove entirely) | Partially — context lost | Yes | No |

Masking is the only approach that protects data AND preserves context for the LLM to produce useful responses.

## When Masking Makes Sense

| Scenario | Mask? | Why |
|----------|:-----:|-----|
| PII in queries (names, PESEL, email, phone) | **Yes** | LLM doesn't need the real name to answer |
| Credentials (API keys, passwords, connection strings) | **Yes** — better: don't send at all | Mask or filter on input |
| Source code (business logic) | **Depends** | Masking class/method names breaks context. Better: use self-hosted LLM |
| Financial data (account numbers, transaction amounts) | **Yes** | Mask numbers, preserve structure |
| Medical data (diagnoses, medications, results) | **Partially** | Mask persons, preserve medical context |
| Simple questions ("how to write a loop in Java") | **No** | No sensitive data present |

## Architecture: DataMaskingAdvisor

Fits naturally as a Spring AI advisor in the existing chain:

```
User message
    │
    ▼
ProviderAuditLog          (log provider)
IntentDetectionAdvisor    (classify intent)
ModelRoutingAdvisor       (select model)
HarnessAdvisor            (inject rules)
DataMaskingAdvisor.before (mask PII)     ← NEW
SmartMemoryAdvisor        (inject memories)
ToolResultSanitizing      (sanitize tool results)
MessageChatMemoryAdvisor  (inject history)
    │
    ▼
LLM (sees only masked data)
    │
    ▼
DataMaskingAdvisor.after  (unmask response) ← restore real values
    │
    ▼
User sees unmasked response
```

### DataMaskingAdvisor

```java
@Component
public class DataMaskingAdvisor implements BaseAdvisor {

    private final MaskingService maskingService;
    private final ThreadLocal<MaskingContext> context = new ThreadLocal<>();

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 1. Detect sensitive data in user message
        // 2. Replace with tokens: "Jan Kowalski" → "[PERSON_1]"
        // 3. Store mapping in thread-local context
        // 4. Return request with masked message

        String original = extractUserMessage(request);
        MaskingContext ctx = maskingService.mask(original);
        context.set(ctx);

        return request.mutate()
            .prompt(replaceUserMessage(request.prompt(), ctx.maskedText()))
            .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        // 1. Read mapping from thread-local
        // 2. Replace tokens back: "[PERSON_1]" → "Jan Kowalski"
        // 3. Return unmasked response

        MaskingContext ctx = context.get();
        if (ctx == null || ctx.isEmpty()) return response;

        context.remove();
        return unmaskResponse(response, ctx);
    }
}
```

### MaskingService — Detection Rules

```java
public class MaskingService {

    private static final List<MaskingRule> RULES = List.of(
        // Polish PESEL
        new RegexRule("\\b\\d{11}\\b", "ID", "PESEL-like number"),
        // Email
        new RegexRule("[\\w.-]+@[\\w.-]+\\.\\w+", "EMAIL", "Email address"),
        // Phone (Polish)
        new RegexRule("\\b(?:\\+48)?\\s?\\d{3}[\\s-]?\\d{3}[\\s-]?\\d{3}\\b", "PHONE", "Phone number"),
        // Credit card
        new RegexRule("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b", "CC", "Credit card"),
        // IP address
        new RegexRule("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b", "IP", "IP address"),
        // API key patterns
        new RegexRule("(?:sk|pk|api)[_-][A-Za-z0-9]{20,}", "SECRET", "API key"),
        // UUID (could be session/user ID)
        new RegexRule("\\b[0-9a-f]{8}-[0-9a-f]{4}-...-[0-9a-f]{12}\\b", "UUID", "UUID"),
        // IBAN
        new RegexRule("\\b[A-Z]{2}\\d{2}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{0,4}\\b", "IBAN", "Bank account")
    );

    public MaskingContext mask(String text) {
        Map<String, String> tokenToOriginal = new LinkedHashMap<>();
        String masked = text;
        int counter = 1;

        for (MaskingRule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(masked);
            while (matcher.find()) {
                String original = matcher.group();
                String token = "[%s_%d]".formatted(rule.category(), counter++);
                tokenToOriginal.put(token, original);
                masked = masked.replace(original, token);
            }
        }

        return new MaskingContext(masked, tokenToOriginal, List.of());
    }
}
```

### MaskingContext

```java
public record MaskingContext(
    String maskedText,
    Map<String, String> tokenToOriginal,  // "[PERSON_1]" → "Jan Kowalski"
    List<DetectedEntity> detected         // what was found and why
) {
    boolean isEmpty() { return tokenToOriginal.isEmpty(); }
}
```

## What NOT to Mask

| Don't mask | Why |
|-----------|-----|
| Business/domain content | LLM needs context to produce useful response |
| Technical names | Classes, methods, tables — masking breaks coding context |
| Public data | Company names, products, technologies |
| Data structure | Field types, relations — preserve schema |

Masking is a **surgical operation** — remove only sensitive tokens, leave everything else intact.

## Tool Result Masking

Not just user messages — tool results can also contain PII:

```
User: "Show user #123 data"
Tool (searchDatabase): {"name": "Jan Kowalski", "pesel": "89012345678", "email": "jan@email.com"}
```

The existing `ToolResultSanitizingAdvisor` protects against prompt injection. `DataMaskingAdvisor` should also process tool results before LLM sees them:

1. `ToolResultSanitizing` → prompt injection protection in tool results
2. `DataMasking` → PII protection in tool results before LLM processes them

## Memory Masking

Memories should store anonymized data, not raw PII. When `SmartMemoryAdvisor` retrieves memories and injects them into context, PII from past conversations could leak.

Options:
- Mask at memory write time (permanent — original PII not stored)
- Mask at memory read time (reversible — PII stored but masked before LLM)
- Both: mask at write for long-term memories, mask at read for session memories

Recommended: mask at write for semantic/procedural memories (general knowledge doesn't need PII), mask at read for episodic memories (event context may need PII for the current user).

## Configuration

```yaml
kukuvaia:
  masking:
    enabled: true
    mode: auto              # auto | strict | off
    per-provider:
      smartgate: auto       # external provider — mask PII
      ollama: off           # self-hosted — no masking needed
    rules:
      pesel: true
      email: true
      phone: true
      credit-card: true
      api-keys: true
      iban: true
      ip-address: false     # often needed for debugging
      uuid: false           # often needed for context
    custom-patterns: []     # user-defined regex rules
    log-detections: true    # log what was masked (without values)
    mask-tool-results: true
    mask-memory-write: true # anonymize before storing to memory
```

Key: masking level configurable **per-provider**. Self-hosted model (Ollama) = data stays in network, no masking needed. External provider (SmartGate, OpenRouter) = mask PII.

## Integration with Model Routing

Smart routing can work with masking:

```
User sends message with PII
    │
    ▼
ModelRoutingAdvisor checks: does this message contain sensitive data?
    │
    ├── No sensitive data → route to any model (cheapest/fastest)
    │
    └── Sensitive data detected →
        ├── Self-hosted model available? → route there (no masking needed)
        └── External only? → mask + route to external provider
```

This gives users the best of both worlds: unmasked processing on self-hosted models (better quality), masked processing on external models (cost/capability trade-off).

## Market Comparison

| Solution | Approach | Drawbacks |
|----------|----------|-----------|
| **Microsoft Presidio** | NER + regex, open source | Python only, heavy (spaCy), separate service |
| **AWS Comprehend** | Cloud NER service | Cost, latency, vendor lock |
| **Google DLP** | Cloud DLP API | Cost, latency, vendor lock |
| **Private AI** | Dedicated PII removal | Commercial SaaS |
| **LangChain NeMo Guardrails** | Programmable rails for LLM | Complex setup, Python only |
| **kukuvaia (planned)** | Regex + rules, in-process advisor | Lighter (no NER), sufficient for structured patterns |

kukuvaia doesn't need NER at Presidio level. Regex-based masking catches 90% of cases (PESEL, email, phone, CC, API keys). For advanced NER (names, addresses) — optional integration with Presidio as external service or local NER model.

## Performance

| Operation | Cost |
|-----------|------|
| Regex masking (8 patterns) | ~0.5-2ms |
| Token unmapping | ~0.1ms |
| Total overhead per request | ~1-3ms |
| LLM call | ~2,000-30,000ms |

Masking overhead is negligible — 0.01% of total request time.

## Relationship to Existing Architecture

| Feature | Connection |
|---------|-----------|
| **Provider Registry** | Masking level configurable per-provider |
| **Model Routing** | Route sensitive requests to self-hosted model (no masking needed) |
| **Harness Engineering** | Group/user rules can enforce masking for specific data types |
| **Dreaming** | Log analysis dream task checks if PII leaked in past conversations |
| **Memory** | Memories should store masked data for long-term knowledge |
| **Sub-agents** | Sub-agent tool results pass through masking before parent sees them |
| **Webhooks** | PayloadSanitizer already filters webhook input — masking extends this to all LLM input |

## Implementation Phases

### Phase A: Core Masking
- `MaskingService` with regex-based detection rules
- `MaskingContext` for request-scoped token mapping
- `DataMaskingAdvisor` (before: mask, after: unmask)
- Wire into advisor chain
- Configuration in `application.yaml`
- Unit tests for each pattern

### Phase B: Tool Result Masking
- Extend masking to tool call results
- Integration with existing `ToolResultSanitizingAdvisor`

### Phase C: Per-Provider Configuration
- Masking level per provider (from provider registry DB)
- Self-hosted providers = masking off, external = masking on
- Integration with `ModelRoutingAdvisor`

### Phase D: Memory Masking
- Mask at memory write time for semantic/procedural memories
- Mask at memory read time for episodic memories
- Migration for existing memories (scan + mask)

### Phase E: Advanced Detection (Optional)
- Integration with Presidio or local NER model for name/address detection
- Custom pattern management via API (admin UI)
- Masking audit log (what was masked, when, for which provider)

## Summary

| Question | Answer |
|----------|--------|
| Encrypt before LLM? | **No** encryption, **yes** masking (token replacement) |
| When? | When PII goes to external LLM provider |
| How? | Spring AI advisor: mask before → LLM → unmask after |
| What to mask? | PESEL, email, phone, CC, API keys, IBAN |
| What NOT to mask? | Business content, technical names, public data |
| Performance cost? | ~1-3ms regex processing, zero LLM calls |
| When unnecessary? | Self-hosted LLM (Ollama) — data never leaves the network |
