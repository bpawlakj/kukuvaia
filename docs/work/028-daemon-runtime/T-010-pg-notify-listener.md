---
id: T-010
title: "PG NOTIFY listener → PgNotifyDebouncer → job"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-008]
blocks: [T-016]
plan_anchor: d3--job-definitions-triggers-notifications
---

# T-010 — PG NOTIFY listener → PgNotifyDebouncer → job

## Scope

- new `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/PgNotifyListener.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/PgNotifyDebouncer.java` (lifecycle)

## Approach

- Dedicated pgjdbc connection; `LISTEN` per channel declared in job triggers; poll `getNotifications(timeout)` on a daemon thread; reconnect with backoff.
- Debounced batch → `DaemonRuntime.submit` with sanitized fields; bind `shutdown()` to the Spring lifecycle.

## Acceptance

- Gated DB test: 50 NOTIFY within 5 s → one job run with batched payload.
- Connection drop → listener reconnects and resumes.
