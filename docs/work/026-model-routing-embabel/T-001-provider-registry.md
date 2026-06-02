# T01: Provider Registry + Model CRUD

**Status**: `pending`
**Tier**: 1 — Foundation
**Depends On**: None
**Blocks**: T02, T03, T04, T05, T06, T07
**Source**: `docs/work/026-model-routing-embabel/plan.md` Phase 1

## Goal

Database tables for providers, models, and role assignments. REST API for CRUD management. No runtime effect on LLM calls yet — this is pure data layer.

## Scope

### Database Migration: `V6__create_providers_and_models.sql`

```sql
CREATE TABLE kukuvaia.providers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    type        VARCHAR(50) NOT NULL
                CHECK (type IN ('smartgate','openrouter','openai','anthropic','ollama','custom')),
    base_url    VARCHAR(500) NOT NULL,
    api_key_ref VARCHAR(255) NOT NULL,
    enabled     BOOLEAN DEFAULT TRUE,
    priority    INT DEFAULT 0,
    config      JSONB DEFAULT '{}',
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP DEFAULT NOW()
);

CREATE TABLE kukuvaia.models (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id     UUID NOT NULL REFERENCES kukuvaia.providers(id) ON DELETE CASCADE,
    model_id        VARCHAR(255) NOT NULL,
    display_name    VARCHAR(255),
    capabilities    JSONB DEFAULT '[]',
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
```

### New Package: `ai.kukuvaia.provider.registry`

**Records**:
- `ProviderRecord` — maps to providers table
- `ModelRecord` — maps to models table
- `ModelRoleRecord` — maps to model_roles table

**DTOs**:
- `CreateProviderRequest`, `UpdateProviderRequest`, `ProviderResponse`
- `UpdateModelRequest`, `ModelResponse`
- `ModelRoleAssignment`, `ModelRolesResponse`

**Repositories** (JdbcTemplate, parameterized queries):
- `ProviderRepository` — CRUD for providers
- `ModelRepository` — CRUD for models, findByProviderId, findEnabled
- `ModelRoleRepository` — CRUD for model_roles, findByRole, upsertRole

**Service**:
- `ProviderRegistryService` — orchestrates CRUD operations

**Controllers**:
- `ProviderController` — `@RestController @RequestMapping("/api/providers")`
- `ModelController` — `@RestController @RequestMapping("/api/models")`

### API Endpoints

```
POST   /api/providers                  — register provider
GET    /api/providers                  — list providers
GET    /api/providers/{id}             — get provider details
PUT    /api/providers/{id}             — update provider
DELETE /api/providers/{id}             — delete provider

GET    /api/models                     — list models (filter: tier, enabled)
GET    /api/models/{id}                — get model details
PUT    /api/models/{id}                — update model config

GET    /api/models/roles               — get all role assignments
POST   /api/models/roles               — bulk assign roles
PUT    /api/models/roles/{role}        — assign single role
DELETE /api/models/roles/{role}        — remove role assignment
```

## File Inventory

### Create
- `kukuvaia-app/src/main/resources/db/migration/V6__create_providers_and_models.sql`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ProviderRecord.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelRecord.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelRoleRecord.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ProviderRepository.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelRepository.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelRoleRepository.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ProviderRegistryService.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/CreateProviderRequest.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/UpdateProviderRequest.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ProviderResponse.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/UpdateModelRequest.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelResponse.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelRoleAssignment.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/api/ProviderController.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/api/ModelController.java`

### Modify
- None (pure additive)

## Security Notes

- `api_key_ref` stores env var name, NEVER the raw key
- `ProviderResponse` DTO never includes `apiKeyRef`
- All SQL parameterized via JdbcTemplate
- Endpoints protected by existing `ApiAuthFilter`

## Acceptance Criteria

- [ ] `./gradlew clean build` passes
- [ ] Flyway V6 migration applies without errors
- [ ] `POST /api/providers` creates a provider, `GET /api/providers` returns it
- [ ] `POST /api/models/roles` assigns roles, `GET /api/models/roles` returns them
- [ ] `DELETE /api/providers/{id}` cascades to models
- [ ] `DELETE /api/models/{id}` fails if model has active role (RESTRICT)
- [ ] `ProviderResponse` never contains `apiKeyRef`
- [ ] Repository unit tests pass
- [ ] All existing tests still pass (no regressions)

## Verification

```bash
./gradlew clean build
./gradlew :kukuvaia-app:bootRun
# In another terminal:
curl -X POST localhost:8080/api/providers -H 'Content-Type: application/json' \
  -d '{"name":"smartgate","type":"smartgate","baseUrl":"https://llm.example.com","apiKeyRef":"SMARTGATE_API_KEY"}'
curl localhost:8080/api/providers
```
