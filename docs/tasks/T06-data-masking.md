# T06: Data Masking Advisor

**Status**: `pending`
**Tier**: 2 — Capabilities
**Depends On**: T05
**Blocks**: —
**Source**: `docs/analyzes/data-masking-analysis.md`

## Goal

Spring AI advisor that masks PII (PESEL, email, phone, CC, API keys) before LLM sees the message, and unmasks in the response. Configurable per-provider (external = mask, self-hosted = off).

## Scope

### DataMaskingAdvisor
- `BaseAdvisor` after HarnessAdvisor, before SmartMemoryAdvisor
- **before()**: detect PII via regex, replace with tokens `[TYPE_N]`, store mapping
- **after()**: replace tokens back with original values
- Thread-local `MaskingContext` for request-scoped mapping

### MaskingService
- Regex-based pattern detection (PESEL, email, phone, CC, API keys, IBAN)
- Returns `MaskingContext(maskedText, tokenToOriginal)`

### Configuration
```yaml
kukuvaia:
  masking:
    enabled: true
    mode: auto
    rules:
      pesel: true
      email: true
      phone: true
      credit-card: true
      api-keys: true
```

## File Inventory

### Create
- `kukuvaia-core/src/main/java/ai/kukuvaia/security/DataMaskingAdvisor.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/security/MaskingService.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/security/MaskingContext.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/security/MaskingRule.java`

### Modify
- `kukuvaia-core/src/main/java/ai/kukuvaia/config/ChatClientConfig.java` — add to advisor chain

## Acceptance Criteria

- [ ] Message with PESEL "89012345678" → LLM sees `[ID_1]` → response unmasked
- [ ] Email, phone, CC patterns detected and masked
- [ ] API key patterns detected and masked
- [ ] Masking disabled when `kukuvaia.masking.enabled=false`
- [ ] Unit tests for each regex pattern
- [ ] ~1-3ms overhead (negligible vs LLM call time)
