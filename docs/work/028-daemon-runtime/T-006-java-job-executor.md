---
id: T-006
title: "JavaJobExecutor + DaemonJob/JobContext"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-005]
blocks: [T-008]
plan_anchor: d2--runtime-core-executor-spi-persistence-api
---

# T-006 — JavaJobExecutor + DaemonJob/JobContext

## Scope

- new `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/exec/JavaJobExecutor.java`, `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/DaemonJob.java`, `JobContext.java`, `JobResult.java`

## Approach

- `DaemonJob { String name(); JobResult run(JobContext ctx); }` Spring beans discovered by name.
- `JobContext`: deadline + `isCancelled()`, sanitized input fields, logger tags, and `callSpecialist(specialist, prompt)` that goes through `SubAgentFactory` with DAEMON context and adds usage to the task (no direct ChatClient access).
- Outcome kind `java`, zero tokens unless `callSpecialist` used.

## Acceptance

- Sample test job runs via the runtime → COMPLETED with output JSON.
- Job exceeding its deadline → TIMEOUT and interrupted.
- `callSpecialist` usage is added to the task's token counts and budget.
