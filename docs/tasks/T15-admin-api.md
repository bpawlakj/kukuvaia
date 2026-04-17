# T15: Admin UI API Completion

**Status**: `pending`
**Tier**: 5 — Optimization
**Depends On**: All previous tasks
**Blocks**: —
**Source**: All analyses

## Goal

Ensure all management APIs are complete, consistent, and ready for a future admin UI panel. Consolidate endpoints, add missing CRUD operations, standardize response formats.

## Scope

### API Audit
Review all endpoints from T01-T14 for consistency:
- Standard pagination (`page`, `size` params)
- Standard error responses (ProblemDetail / RFC 9457)
- Consistent filtering/sorting patterns
- OpenAPI documentation (springdoc-openapi)

### Dashboard Endpoints
- `GET /api/dashboard/overview` — system summary (providers, models, roles, agents, health)
- `GET /api/dashboard/costs` — token usage per model/role over time
- `GET /api/dashboard/activity` — recent tasks, dream reports, recommendations

### Missing CRUD
- Provider model manual add (not just sync)
- Bulk operations (enable/disable multiple models)
- Role history (who changed what, when)
- Dream schedule management

### OpenAPI Spec
- springdoc-openapi integration
- Auto-generated API documentation at `/api/docs`
- Swagger UI at `/api/swagger-ui`

## Acceptance Criteria

- [ ] All endpoints follow consistent pagination/error patterns
- [ ] Dashboard overview endpoint returns system summary
- [ ] OpenAPI spec generated and accessible
- [ ] All endpoints documented with descriptions and examples
- [ ] Ready for frontend admin UI consumption
