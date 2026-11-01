---
id: T-007
title: "DaemonController API + retention prune"
status: done
plan: ../plan.md
created: 2026-11-01
completed: 2026-11-01
commit: caea9e3
depends_on: [T-005]
blocks: [T-016]
plan_anchor: d2--runtime-core-executor-spi-persistence-api
---

# T-007 — DaemonController API + retention prune

## Scope

- new `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/api/DaemonController.java` (+ DTOs)
- retention job in the daemon package; `application.yaml` (`kukuvaia.daemon.retention-days`)

## Approach

- Endpoints behind `ApiAuthFilter`: `GET /api/daemon/jobs`, `POST /api/daemon/jobs/{name}/run`, `GET /api/daemon/tasks?job&status&since`, `GET /api/daemon/tasks/{id}` (full result), `GET /api/daemon/budget`.
- Manual run and full-result read refused when `kukuvaia.security.dev-mode=true` unless `kukuvaia.daemon.api.allow-in-dev-mode=true` (explicit local opt-in).
- Nightly prune of rows older than `retention-days` (default 30), errors via `ErrorSanitizer`.

## Acceptance

- MockMvc tests: auth required, dev-mode refusal, run returns task id (202), list/filter, budget numbers.
- Prune removes only rows older than retention.
