# T07: SubAgentFactory Tier Integration

**Status**: `pending`
**Tier**: 2 — Capabilities
**Depends On**: T02
**Blocks**: T10
**Source**: `docs/plan/model-routing-and-embabel.md` Phase 7

## Goal

Upgrade SubAgentFactory to resolve models via DB-backed roles instead of hardcoded model names.

## Scope

### SubAgentSpec — add `tier` field
```java
public record SubAgentSpec(
    String name, String description, String provider,
    String tier,  // NEW: "worker", "supervisor", "advisor" — maps to model_roles
    String systemPrompt, List<String> tools, String model,
    int maxTokens, int maxToolRounds, double temperature, long timeoutSeconds
)
```

### SubAgentFactory — tier-based resolution
Resolution order:
1. `spec.tier()` → `LlmProviderService.resolveByRole(tier)` → ChatModel
2. `spec.model()` → `OpenAiChatOptions.model(name)` (existing behavior)
3. `spec.provider()` → existing provider resolution
4. Fallback to "default" role

### SubAgentSpecLoader — parse `tier` from YAML
New field in specialist YAML files.

## File Inventory

### Modify
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentSpec.java` — add tier
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentFactory.java` — tier resolution
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentSpecLoader.java` — parse tier

## Acceptance Criteria

- [ ] SubAgentSpec with `tier: "worker"` resolves to Haiku ChatModel from DB
- [ ] SubAgentSpec with `tier: null` falls back to model/provider/default (backward compat)
- [ ] Existing specialist YAML without `tier` field still works
- [ ] Unit tests for tier resolution priority
- [ ] All existing SubAgentFactory tests pass
