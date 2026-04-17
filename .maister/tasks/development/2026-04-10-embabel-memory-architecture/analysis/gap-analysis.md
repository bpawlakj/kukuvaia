# Gap Analysis: Embabel & Memory Architecture Documentation

## Files to CREATE
1. `docs/architecture/embabel-integration.md` — Embabel GOAP, blackboard, agent patterns, core integration
2. `docs/architecture/memory-architecture.md` — Memory redesign: 3 types, JSONB, pgvector, Session-User model

## Files to UPDATE
3. `.maister/docs/project/architecture.md` — Multi-module structure, Embabel subsystem, memory subsystem
4. `.maister/docs/project/tech-stack.md` — Add Kotlin, Embabel, pgvector (planned)
5. `.maister/docs/project/roadmap.md` — Embabel milestones, specific memory module design
6. `.maister/docs/project/implementation-plan.md` — Add Phase 4 for kukuvaia-memory + Embabel agents

## Decisions Needed
1. kukuvaia-memory dependency direction → Recommended: Option A (memory as infrastructure, core depends on it)
2. Documentation location → Recommended: docs/architecture/ for detailed docs
3. Implementation plan scope → Recommended: Extend existing plan with Phase 4
4. Current vs future in docs → Recommended: Both states with migration path
