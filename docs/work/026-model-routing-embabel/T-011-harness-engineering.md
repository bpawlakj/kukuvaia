# T11: Harness Engineering (Rules System)

**Status**: `pending`
**Tier**: 4 — Intelligence
**Depends On**: T02
**Blocks**: T13
**Source**: `docs/analyzes/harness-engineering-analysis.md`

## Goal

Database-driven, API-managed rule system with 5-level hierarchy (platform → group → user → project → session). Rules are injected into LLM context via HarnessAdvisor. Enables per-user/group behavioral customization.

## Scope

### Database Schema
- `kukuvaia.groups` — teams/departments
- `kukuvaia.user_groups` — membership with roles (admin, member, viewer)
- `kukuvaia.rule_sets` — named collections of rules with scope/owner
- `kukuvaia.rules` — individual rules with type, content, tags, activation conditions

### Rule Types
- `instruction` — how to behave ("always use constructor injection")
- `constraint` — what NOT to do ("never use field @Autowired")
- `context` — background knowledge ("this team uses Spring Boot 3.4")
- `preference` — soft guidance ("prefer records for DTOs")
- `persona_modifier` — adjusts persona ("be concise, skip explanations")

### HarnessAdvisor (Spring AI BaseAdvisor)
- Collects applicable rules by hierarchy (platform → group → user → project → session)
- Filters by activation conditions (file patterns, intents, keywords)
- Resolves conflicts (lower scope overrides higher)
- Compiles into system prompt section
- Cache per-user with 5-min TTL

### API Endpoints
- Groups CRUD + membership management
- Rule sets CRUD with scope/owner
- Rules CRUD within sets
- `GET /api/harness/resolve` — preview resolved rules for current user
- `POST /api/harness/import/claude-code` — import from CLAUDE.md format

## File Inventory

### Create
- Migration: `V8__create_harness_tables.sql`
- Records, repositories, DTOs (~10 files)
- `HarnessAdvisor.java`
- `HarnessService.java`
- Controllers for groups, rule-sets, rules
- Import/export service

### Modify
- `ChatClientConfig.java` — add HarnessAdvisor to chain

## Acceptance Criteria

- [ ] Create group "java-team", add user, add rule set with Java rules
- [ ] HarnessAdvisor injects group rules into system prompt
- [ ] User-level rule overrides group-level rule (same key)
- [ ] `GET /api/harness/resolve` shows compiled rules for current user
- [ ] `.kukuvaia/rules/*.md` files imported as project-scope rules
- [ ] Cache invalidation on rule CRUD
