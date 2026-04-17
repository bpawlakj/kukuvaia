# T02: ChatModel Factory + Cache

**Status**: `pending`
**Tier**: 1 — Foundation
**Depends On**: T01
**Blocks**: T03, T04, T05, T07, T11
**Source**: `docs/plan/model-routing-and-embabel.md` Phase 2

## Goal

Create `ChatModel` instances dynamically from DB-stored provider/model records. Cache with invalidation. Refactor `LlmProviderService` to delegate to cache. Seed default provider from env vars for backward compatibility.

## Scope

### SecretResolver

```java
public interface SecretResolver {
    String resolve(String reference);
}

@Component
public class EnvVarSecretResolver implements SecretResolver {
    // System.getenv(reference) → actual key value
    // Throws SecretNotFoundException if null/blank
}
```

### ChatModelFactory

Creates `OpenAiChatModel` from DB records:
1. Resolve API key via `SecretResolver`
2. Build `OpenAiApi` with provider's `base_url` + resolved key
3. Build `OpenAiChatOptions` with model's `model_id`, `max_tokens`, temperature
4. Build `OpenAiChatModel`

### ChatModelCache

```java
@Component
public class ChatModelCache {
    ConcurrentHashMap<UUID, ChatModel> modelCache;   // model UUID → ChatModel
    ConcurrentHashMap<String, UUID> roleIndex;        // role name → model UUID

    ChatModel getByModelId(UUID modelId)     // lazy-create via ChatModelFactory
    ChatModel getByRole(String role)          // resolve role → UUID → ChatModel
    void invalidateModel(UUID modelId)
    void invalidateProvider(UUID providerId)  // evict all models for provider
    void refreshRoles()                       // reload role→model from DB
    void warmUp()                             // ApplicationReadyEvent: pre-create role-assigned models
}
```

### LlmProviderService Refactor

Becomes a facade delegating to `ChatModelCache`:
- `resolve(String explicit, ExecutionContext ctx)` — try as role → try as UUID → fallback "default"
- `resolveByRole(String role)` — direct role lookup
- Keep existing `register()` for backward compat

### ProviderSeeder

`ApplicationReadyEvent` listener:
- If `providers` table is empty AND `LLM_BASE_URL`/`LLM_API_KEY` env vars set:
  - Insert "default" provider
  - Insert model with `LLM_MODEL` value
  - Assign as "default" role

## File Inventory

### Create
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/SecretResolver.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/EnvVarSecretResolver.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ChatModelFactory.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ChatModelCache.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ProviderSeeder.java`

### Modify
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/LlmProviderService.java` — delegate to ChatModelCache

## Acceptance Criteria

- [ ] `./gradlew clean build` passes
- [ ] Boot with `LLM_BASE_URL` + `LLM_API_KEY` → seeder creates default provider + model + role
- [ ] `LlmProviderService.resolveByRole("default")` returns working ChatModel
- [ ] `ChatModelCache.invalidateModel(uuid)` evicts cached instance, next access recreates
- [ ] `ChatModelCache.invalidateProvider(uuid)` evicts all models for that provider
- [ ] Register SmartGate via API → assign roles → `resolveByRole("worker")` returns Haiku ChatModel
- [ ] Existing chat functionality works unchanged (backward compat)
- [ ] Unit tests for ChatModelFactory, ChatModelCache, ProviderSeeder
- [ ] All existing tests still pass

## Verification

```bash
./gradlew clean build
# Boot with env vars (backward compat)
LLM_BASE_URL=https://llm.example.com LLM_API_KEY=xxx LLM_MODEL=sonnet \
  ./gradlew :kukuvaia-app:bootRun
# Logs should show: "Seeded default provider from env vars"
# Chat should work normally
```
