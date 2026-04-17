# T04: Embabel Activation + ModelProvider Bridge

**Status**: `pending`
**Tier**: 2 — Capabilities
**Depends On**: T02
**Blocks**: T08
**Source**: `docs/plan/model-routing-and-embabel.md` Phase 4, `docs/architecture/embabel-integration.md`

## Goal

Re-enable Embabel's `AgentPlatformAutoConfiguration`. Bridge DB-backed model roles to Embabel's `LlmOptions.withLlmForRole()` via custom `@Primary ModelProvider`.

## Scope

### Remove Exclusion
- `KukuvaiaApplication.java` — remove `AgentPlatformAutoConfiguration` from `excludeName`
- Keep `OpenAiEmbeddingAutoConfiguration` exclusion (using local ONNX)

### EmbabelModelBridgeConfig (Kotlin)
- `@Configuration` in kukuvaia-agents module
- `@Bean @Primary ModelProvider` wrapping `ChatModelCache`
- Build `SpringAiLlmService` instances from role-assigned models
- Map roles: cheapest → worker, best → advisor, default → supervisor

### Handle Auto-Config Conflicts
- Incremental boot: remove exclusion, see what breaks, fix
- `@Primary ModelProvider` prevents double model registration

## File Inventory

### Create
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/config/EmbabelModelBridgeConfig.kt`

### Modify
- `kukuvaia-app/src/main/java/ai/kukuvaia/KukuvaiaApplication.java` — remove exclusion
- `kukuvaia-app/src/main/resources/application.yaml` — add `embabel.models.default-llm`
- `kukuvaia-agents/build.gradle` — add explicit embabel-agent-ai if needed

## Acceptance Criteria

- [ ] App boots without bean conflicts
- [ ] PingAgent test still passes
- [ ] Embabel `AgentPlatform` bean available in context
- [ ] `ModelProvider.getLlm(ByRoleModelSelectionCriteria("cheapest"))` resolves to Haiku
- [ ] `ModelProvider.getLlm(ByRoleModelSelectionCriteria("best"))` resolves to Opus
- [ ] New integration test: Embabel action calls `ctx.ai().withLlmByRole("cheapest")`
