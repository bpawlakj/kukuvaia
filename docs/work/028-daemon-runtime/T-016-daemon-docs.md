---
id: T-016
title: "Docs: daemon-runtime.md, orchestration decisions, security-review statuses"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-007, T-009, T-010, T-011, T-012, T-014]
blocks: []
plan_anchor: critical-files
---

# T-016 — Docs: daemon-runtime.md, orchestration decisions, security-review statuses

## Scope

- new `docs/architecture/daemon-runtime.md`
- `docs/architecture/agent-orchestration-decisions.md` (daemon section)
- `docs/architecture/security-review.md` (#10, #13, #14, #15, #16, #17, #21 statuses)

## Approach

- Living description of the runtime: executor kinds, YAML schema, triggers, guards, API, sinks, config.
- Update statuses to match the code (no aspirational claims).

## Acceptance

- Docs match the merged code; links resolve; English only.
