---
id: T-005
title: "DaemonRuntime core + executor SPI + SpecialistExecutor + V24 migration"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-001, T-002, T-003, T-004]
blocks: [T-006, T-007, T-008, T-012, T-014, T-015]
plan_anchor: d2--runtime-core-executor-spi-persistence-api
---

# T-005 — DaemonRuntime core + executor SPI + SpecialistExecutor + V24 migration

## Scope

- new `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/DaemonRuntime.java`, `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/exec/` (`DaemonExecutor`, `JobInvocation`, `JobLimits`, `ExecutionOutcome`, `SpecialistExecutor`), `OverlapPolicy`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/DaemonAgentService.java` (becomes facade), `DaemonScheduleGuard.java`, `DaemonTaskResult.java`
- `kukuvaia-engine/kukuvaia-app/src/main/resources/db/migration/V24__daemon_runtime.sql`

## Approach

- `DaemonRuntime.submit(job, input, trigger)` → task id (async on bounded virtual-thread pool `kukuvaia.daemon.max-concurrent`, default 2); `runSync` for tests/manual.
- Guard order: kill switch → enabled → budget (token kinds) → overlap (`SKIP` | `ALLOW(max)`) → concurrency; statuses QUEUED → RUNNING → COMPLETED | FAILED | TIMEOUT | SKIPPED; hard deadline per job with cancellation.
- V24 on `kukuvaia_agent.daemon_tasks`: add `kind, target, provider, model, prompt_tokens, completion_tokens, error, output jsonb, parent_task_id, queued_at`; `specialist_type` nullable; status CHECK += QUEUED/TIMEOUT/SKIPPED/CANCELLED; indexes `(name, created_at)`, `(status)`.
- `DaemonAgentService.execute(...)` delegates to the runtime (sync) so `WebhookController` keeps working unchanged.

## Acceptance

- State-machine tests: completed, failed, timeout (cancelled), skipped (budget / overlap), concurrency limit respected.
- `ALLOW` overlap does not grow the schedule-guard map with unique names.
- Migration applies on an existing DB (rows from V23 preserved) and on a fresh DB (`KUKUVAIA_TEST_DB_URL` gated test).
- Existing webhook path still produces a COMPLETED/FAILED row.
