# Plan: DB-Driven Provider/Model Registry + Hierarchical Model Routing + Embabel

**Created**: 2026-04-12
**Status**: Approved, not yet started

## Context

Kukuvaia-engine currently has a single hardcoded LLM provider configured via environment variables (`LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL`). The goal is a **fully dynamic, database-driven** system where:

- Providers (SmartGate, OpenRouter, OpenAI, etc.) are registered in DB with API details
- Models are discovered from provider APIs and stored in DB with capabilities/roles
- Routing roles (advisor, supervisor, worker, etc.) are assigned via API
- Everything is configurable through REST endpoints (future admin UI)
- Embabel agents use per-action model selection based on DB-backed roles

### Hierarchical Routing Architecture

```
                    ┌──────────────────────┐
                    │   Sonnet (Supervisor) │
                    │ - Task classification │
                    │ - Dispatch to workers │
                    │ - Loop detection      │
                    │ - Escalation to Opus  │
                    └───┬───────┬───────┬───┘
                        │       │       │
                   ┌────▼──┐ ┌─▼────┐ ┌▼──────┐
                   │ Haiku │ │ sLLM │ │ sLLM  │
                   │ Easy  │ │Spec-1│ │Spec-2 │
                   └───────┘ └──────┘ └───────┘
                        │       │       │
                   (stuck/loop/complex?)
                        │
                   ┌────▼──────┐
                   │   Opus    │
                   │ Advisor   │
                   └───────────┘
```

- **Haiku** (cheap/fast) handles easy tasks
- **Specialized LLMs** handle domain-specific tasks
- **Sonnet** (supervisor) classifies, dispatches, monitors, helps stuck workers
- **Opus** (strategic advisor) called by Sonnet for complex tasks or loop recovery

This replaces the static `application.yaml` approach with a runtime-configurable registry.

---

## Phase 1: Database Schema + Provider/Model CRUD

**Goal**: Tables for providers, models, roles. REST API for management. No runtime effect yet.

### 1.1 Flyway Migration: `V6__create_providers_and_models.sql`

```sql
CREATE TABLE kukuvaia.providers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    type        VARCHAR(50) NOT NULL
                CHECK (type IN ('smartgate','openrouter','openai','anthropic','ollama','custom')),
    base_url    VARCHAR(500) NOT NULL,
    api_key_ref VARCHAR(255) NOT NULL,  -- env var name, NEVER the raw key
    enabled     BOOLEAN DEFAULT TRUE,
    priority    INT DEFAULT 0,
    config      JSONB DEFAULT '{}',     -- provider-specific: request_timeout, headers, etc.
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE kukuvaia.models (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id     UUID NOT NULL REFERENCES kukuvaia.providers(id) ON DELETE CASCADE,
    model_id        VARCHAR(255) NOT NULL,  -- ID sent to API (e.g., "haiku", "claude-sonnet-4.5")
    display_name    VARCHAR(255),
    capabilities    JSONB DEFAULT '[]',     -- ["text","code","analysis","vision"]
    tier            VARCHAR(20) DEFAULT 'standard'
                    CHECK (tier IN ('economy','standard','premium','enterprise')),
    max_tokens      INT DEFAULT 4096,
    context_window  INT,
    enabled         BOOLEAN DEFAULT TRUE,
    config          JSONB DEFAULT '{}',
    discovered_at   TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(provider_id, model_id)
);

CREATE TABLE kukuvaia.model_roles (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role        VARCHAR(50) NOT NULL UNIQUE,
    model_id    UUID NOT NULL REFERENCES kukuvaia.models(id) ON DELETE RESTRICT,
    description VARCHAR(500),
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_models_provider ON kukuvaia.models(provider_id);
CREATE INDEX idx_models_tier ON kukuvaia.models(tier) WHERE enabled = TRUE;
CREATE INDEX idx_models_capabilities ON kukuvaia.models USING gin(capabilities);
```

Key decisions:
- `api_key_ref` stores env var name (e.g., `"SMARTGATE_API_KEY"`), never the actual key
- `ON DELETE CASCADE` on models (provider deleted = models gone)
- `ON DELETE RESTRICT` on model_roles (can't delete model that serves a role)
- `capabilities` JSONB array with GIN index for `@>` containment queries

### 1.2 New Package: `ai.kukuvaia.provider.registry`

**Records** (value types):
- `ProviderRecord(id, name, type, baseUrl, apiKeyRef, enabled, priority, config, createdAt, updatedAt)`
- `ModelRecord(id, providerId, modelId, displayName, capabilities, tier, maxTokens, contextWindow, enabled, config, discoveredAt, ...)`
- `ModelRoleRecord(id, role, modelId, description, createdAt, updatedAt)`

**Repositories** (JdbcTemplate, parameterized queries — same pattern as `SmartMemoryRepository`):
- `ProviderRepository` — CRUD for providers
- `ModelRepository` — CRUD for models, `findByProviderId()`, `findByCapability()`, `findEnabled()`
- `ModelRoleRepository` — CRUD for model_roles, `findByRole()`, `findAll()`, `upsertRole()`

**DTOs** (request/response records):
- `CreateProviderRequest(@NotBlank name, @NotBlank type, @NotBlank baseUrl, @NotBlank apiKeyRef, config)` 
- `ProviderResponse(id, name, type, baseUrl, enabled, priority, modelCount, createdAt)` — **never returns apiKeyRef**
- `ModelResponse(id, providerId, providerName, modelId, displayName, capabilities, tier, maxTokens, enabled)`
- `UpdateModelRequest(displayName, capabilities, tier, maxTokens, enabled, config)`
- `ModelRoleAssignment(role, modelId)`
- `ModelRolesResponse(assignments: List<ModelRoleResponse>)`

**Service**:
- `ProviderRegistryService` — orchestrates CRUD, triggers cache invalidation on changes

**Controllers**:
- `ProviderController` — `@RestController @RequestMapping("/api/providers")`
- `ModelController` — `@RestController @RequestMapping("/api/models")`

### 1.3 API Endpoints

```
POST   /api/providers                  — register provider
GET    /api/providers                  — list providers
GET    /api/providers/{id}             — get provider details
PUT    /api/providers/{id}             — update provider
DELETE /api/providers/{id}             — delete provider (cascades models)
POST   /api/providers/{id}/test        — test connectivity

GET    /api/models                     — list models (filter: tier, capability, enabled)
GET    /api/models/{id}                — get model details
PUT    /api/models/{id}                — update model config

GET    /api/models/roles               — get current role assignments
POST   /api/models/roles               — bulk assign roles [{role, modelId}]
PUT    /api/models/roles/{role}        — assign single role
DELETE /api/models/roles/{role}        — remove role assignment
```

### Files
- **NEW**: `kukuvaia-app/src/main/resources/db/migration/V6__create_providers_and_models.sql`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ProviderRecord.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelRecord.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelRoleRecord.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ProviderRepository.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelRepository.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelRoleRepository.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ProviderRegistryService.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/api/ProviderController.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/api/ModelController.java`
- **NEW**: DTO records in `ai.kukuvaia.provider.registry` package

### Verification
- Unit tests: repository CRUD, service logic
- Integration test: Testcontainers PostgreSQL, Flyway migration applies, CRUD works end-to-end
- Manual: boot app, `POST /api/providers` registers SmartGate, `GET /api/providers` lists it

---

## Phase 2: Dynamic ChatModel Factory + Cache

**Goal**: Create `ChatModel` instances from DB provider/model records. Cache with invalidation.

### 2.1 SecretResolver

```java
public interface SecretResolver {
    String resolve(String reference);  // "SMARTGATE_API_KEY" → actual key value
}

@Component
public class EnvVarSecretResolver implements SecretResolver {
    // System.getenv(reference) — future: Vault, AWS Secrets Manager
}
```

### 2.2 ChatModelFactory

Creates `OpenAiChatModel` from DB records:
1. Resolve API key via `SecretResolver`
2. Build `OpenAiApi` with provider's `base_url` + resolved key
3. Build `OpenAiChatOptions` with model's `model_id`, `max_tokens`, temperature from `config`
4. Build `OpenAiChatModel`

All providers are OpenAI-compatible, so same factory works for SmartGate, OpenRouter, OpenAI, etc.

### 2.3 ChatModelCache

```java
@Component
public class ChatModelCache {
    private final ConcurrentHashMap<UUID, ChatModel> modelCache;    // model UUID → ChatModel
    private final ConcurrentHashMap<String, UUID> roleIndex;        // role name → model UUID

    ChatModel getByModelId(UUID modelId)      // lazy-creates if not cached
    ChatModel getByRole(String role)           // resolves role → model UUID → ChatModel
    void invalidateModel(UUID modelId)         // evict single model
    void invalidateProvider(UUID providerId)   // evict all models for provider
    void refreshRoles()                        // reload role→model mapping from DB
    void warmUp()                              // startup: pre-create ChatModels for all role-assigned models
}
```

- `computeIfAbsent` for thread-safe lazy creation
- `warmUp()` called on `ApplicationReadyEvent`
- Invalidation called by `ProviderRegistryService` on provider/model CRUD

### 2.4 Refactor LlmProviderService

The existing `LlmProviderService` becomes a **facade** delegating to `ChatModelCache`:

```java
public ChatModel resolve(String explicitProvider, ExecutionContext context) {
    // 1. Try as role name ("advisor", "worker", "cheapest")
    // 2. Try as model UUID
    // 3. Fallback: "default" role
}

public ChatModel resolveByRole(String role) {
    return chatModelCache.getByRole(role);
}
```

### 2.5 Startup Seeding (backward compat)

`ApplicationReadyEvent` listener (`ProviderSeeder`):
- If `providers` table is empty AND `LLM_BASE_URL`/`LLM_API_KEY` env vars are set:
  - Seed a "default" provider from env vars
  - Seed a model with `LLM_MODEL` value
  - Assign it as "default" role
- Existing env-var config works unchanged on first boot

### Files
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/SecretResolver.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/EnvVarSecretResolver.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ChatModelFactory.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ChatModelCache.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ProviderSeeder.java`
- **MODIFY**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/LlmProviderService.java`

### Verification
- Unit test: `ChatModelFactory` creates valid `OpenAiChatModel` with correct options
- Unit test: `ChatModelCache` invalidation, role resolution, concurrent access
- Integration test: boot with env vars → seeder creates default provider → `LlmProviderService.resolve()` returns working ChatModel

---

## Phase 3: Model Discovery

**Goal**: Call provider's `/v1/models` endpoint to discover available models.

### 3.1 ModelDiscoveryClient

```java
@Component
public class ModelDiscoveryClient {
    List<DiscoveredModel> discover(ProviderRecord provider, String resolvedApiKey)
    // GET {base_url}/v1/models, parse OpenAI-format response
    // Handle: 401 (bad key), 404 (endpoint not supported), timeout
}
```

### 3.2 Sync Endpoint

`POST /api/providers/{id}/sync-models`:
1. Call `ModelDiscoveryClient.discover(provider)`
2. Compare with existing models in DB
3. Add new, optionally soft-disable disappeared ones
4. Return `SyncModelsResponse(discovered, added, removed, unchanged)`

### Files
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelDiscoveryClient.java`
- **MODIFY**: `kukuvaia-core/src/main/java/ai/kukuvaia/api/ProviderController.java` (add sync endpoint)

### Verification
- Unit test: mock HTTP response, verify parsing
- Integration test (with real SmartGate): sync models, verify haiku/sonnet/opus/gpt discovered

---

## Phase 4: Embabel Activation + ModelProvider Bridge

**Goal**: Re-enable Embabel, bridge DB-backed model roles to Embabel's `LlmOptions.withLlmForRole()`.

### 4.1 Remove AgentPlatformAutoConfiguration exclusion

Modify `KukuvaiaApplication.java` — remove from `excludeName`. Keep `OpenAiEmbeddingAutoConfiguration` exclusion.

### 4.2 EmbabelModelBridgeConfig (Kotlin, in kukuvaia-agents)

Provides `@Primary ModelProvider` bean that wraps kukuvaia's `ChatModelCache`:

```kotlin
@Configuration
class EmbabelModelBridgeConfig(
    private val chatModelCache: ChatModelCache,
    private val modelRoleRepository: ModelRoleRepository,
) {
    @Bean @Primary
    fun kukuvaiaModelProvider(): ModelProvider {
        // Build SpringAiLlmService instances from all role-assigned models
        // Map role names to Embabel's role system (cheapest, best, etc.)
        // Return ConfigurableModelProvider with these services
    }
}
```

### 4.3 Embabel config in application.yaml

```yaml
embabel:
  models:
    default-llm: default    # matches role name from model_roles table
```

Role mappings come from DB (`model_roles` table), not from yaml.

### 4.4 Handle auto-config conflicts

Strategy: boot incrementally. If specific Embabel auto-config beans require dependencies we don't have, selectively exclude sub-configurations or provide stubs.

### Files
- **MODIFY**: `kukuvaia-app/src/main/java/ai/kukuvaia/KukuvaiaApplication.java`
- **NEW**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/config/EmbabelModelBridgeConfig.kt`
- **MODIFY**: `kukuvaia-app/src/main/resources/application.yaml`
- **MODIFY**: `kukuvaia-agents/build.gradle` (if extra Embabel deps needed)

### Verification
- Boot test: app starts without bean conflicts
- PingAgent test still passes
- New test: Embabel agent calls `ctx.ai().withLlmByRole("cheapest")` → resolves to Haiku model from DB

---

## Phase 5: Interactive Chat Model Routing

**Goal**: Spring AI advisor that routes interactive chat to appropriate model tier based on complexity.

### 5.1 ModelRoutingAdvisor

`BaseAdvisor` at order `HIGHEST_PRECEDENCE + 12`:

**`before()`**:
- Classify complexity: `FAST` (short/simple → worker role), `DEFAULT` (normal → default role), `ESCALATE` (complex/loop → advisor role)
- Heuristics (keyword/pattern, no LLM call): short greetings → FAST, analysis keywords → DEFAULT, loop flag → ESCALATE
- Override model via `OpenAiChatOptions.builder().model(targetModelId).build()` on the prompt
- Set `kukuvaia.routed-model` in context for audit

### 5.2 LoopDetectionAdvisor

Tracks per-session tool-call rounds. Thresholds (configurable):
- Warning at 5 rounds: inject "wrap up" hint
- Escalation at 8 rounds: set flag → `ModelRoutingAdvisor` reads it, switches to next tier
- Hard abort at 15 rounds: return error, reset

### 5.3 Wire into advisor chain

Modify `ChatClientConfig.java` to add both advisors.

### Files
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/ModelRoutingAdvisor.java`
- **NEW**: `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/LoopDetectionAdvisor.java`
- **MODIFY**: `kukuvaia-core/src/main/java/ai/kukuvaia/config/ChatClientConfig.java`
- **MODIFY**: `kukuvaia-core/src/main/java/ai/kukuvaia/advisors/IntentDetectionAdvisor.java` (add SIMPLE_CONVERSATION)

### Verification
- Unit tests: classification logic, loop detection thresholds
- Manual: "hi" → logs show worker/haiku model, complex prompt → sonnet, forced loop → opus escalation

---

## Phase 6: Embabel Multi-Model Agent (PoC)

**Goal**: Real Embabel agent demonstrating per-action model selection from DB roles.

### 6.1 ResearchAgent.kt

```kotlin
@Agent(description = "Research with tiered model routing")
class ResearchAgent {
    @Action
    fun quickScan(query: ResearchQuery, ctx: ProcessContext): InitialFindings =
        ctx.ai().withLlmByRole("cheapest").creating(InitialFindings::class.java)
            .create("Quick scan: ${query.topic}")

    @AchievesGoal @Action
    fun deepAnalysis(findings: InitialFindings, ctx: ProcessContext): AnalysisResult =
        ctx.ai().withLlmByRole("best").creating(AnalysisResult::class.java)
            .create("Deep analysis: ${findings.summary}")
}
```

GOAP plan: `ResearchQuery → quickScan(Haiku) → InitialFindings → deepAnalysis(Opus) → AnalysisResult`

### Files
- **NEW**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/research/ResearchAgent.kt`
- **NEW**: `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/research/ResearchModels.kt`
- **NEW**: `kukuvaia-agents/src/test/kotlin/ai/kukuvaia/agents/research/ResearchAgentTest.kt`

---

## Phase 7: SubAgentFactory Tier Integration

**Goal**: SubAgentFactory resolves models via DB roles instead of hardcoded model names.

### Changes
- `SubAgentSpec` — add `tier` field (role name reference)
- `SubAgentFactory` — resolution order: `tier` → role from DB → `model` → fallback
- `SubAgentSpecLoader` — parse `tier` from YAML

### Files
- **MODIFY**: `kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentSpec.java`
- **MODIFY**: `kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentFactory.java`
- **MODIFY**: `kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentSpecLoader.java`

---

## Implementation Order

```
Phase 1 (DB Schema + CRUD) ──> Phase 2 (ChatModel Factory + Cache) ──┬──> Phase 3 (Discovery)
                                                                      ├──> Phase 4 (Embabel) ──> Phase 6 (Agent PoC)
                                                                      ├──> Phase 5 (Routing Advisors)
                                                                      └──> Phase 7 (SubAgent Tiers)
```

Phases 3-7 are independent after Phase 2 and can be done in any order.

## Security

- `api_key_ref` stores env var name, never the actual key
- `SecretResolver` interface enables future Vault/Secrets Manager integration
- Provider API responses never include `apiKeyRef`
- All SQL parameterized via JdbcTemplate
- Provider/model CRUD protected by existing `ApiAuthFilter`
- No keys in logs (existing `ProviderAuditLog` pattern)

## Risks

1. **Spring AI auto-config vs DB ChatModels** — mitigated by startup seeder that preserves env-var backward compat and gradual transition
2. **Embabel auto-config bean conflicts** — mitigated by `@Primary ModelProvider` bean and incremental approach
3. **ChatModel lifecycle** — old instances garbage collected on invalidation, no explicit close needed (Spring RestClient pools connections)
4. **First boot with empty DB** — mitigated by `ProviderSeeder` that seeds from env vars if tables are empty

## End-to-End Verification

1. `./gradlew clean build` — all tests pass
2. Boot with env vars only → seeder creates default provider/model → chat works
3. `POST /api/providers` register OpenRouter → `POST /api/providers/{id}/sync-models` → models appear
4. `POST /api/models/roles` assign haiku=worker, sonnet=default, opus=advisor
5. Send "hi" → logs show worker model used
6. Send complex analysis → logs show default/advisor model
7. Invoke Embabel ResearchAgent → haiku for scan, opus for analysis
