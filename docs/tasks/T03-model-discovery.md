# T03: Model Discovery

**Status**: `pending`
**Tier**: 2 — Capabilities
**Depends On**: T02
**Blocks**: T12
**Source**: `docs/plan/model-routing-and-embabel.md` Phase 3

## Goal

Call provider's `/v1/models` endpoint to discover available models. Sync discovered models to DB.

## Scope

### ModelDiscoveryClient
- `GET {base_url}/v1/models` with resolved API key
- Parse OpenAI-format response: `data[].id`, `data[].owned_by`
- Handle: 401 (bad key), 404 (not supported), timeout
- Return `List<DiscoveredModel>` records

### Sync Endpoint
- `POST /api/providers/{id}/sync-models`
- Compare discovered models with DB
- Add new, soft-disable disappeared
- Return `SyncModelsResponse(discovered, added, removed, unchanged)`

### Provider Test Endpoint
- `POST /api/providers/{id}/test`
- Quick connectivity check (call `/v1/models` or minimal completion)
- Return status + latency

## File Inventory

### Create
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ModelDiscoveryClient.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/DiscoveredModel.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/SyncModelsResponse.java`

### Modify
- `kukuvaia-core/src/main/java/ai/kukuvaia/api/ProviderController.java` — add sync + test endpoints

## Acceptance Criteria

- [ ] `POST /api/providers/{id}/sync-models` discovers models from SmartGate
- [ ] New models added to DB with `discovered_at` timestamp
- [ ] `POST /api/providers/{id}/test` returns connectivity status + latency
- [ ] 401/404/timeout errors handled gracefully with clear messages
- [ ] Unit test with mocked HTTP response
