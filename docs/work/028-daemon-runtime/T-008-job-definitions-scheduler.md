---
id: T-008
title: "YAML job loader/registry + dedicated cron scheduler + kill switch"
status: done
plan: ../plan.md
created: 2026-11-01
completed: 2026-11-01
commit: 4e3f672
depends_on: [T-005, T-006]
blocks: [T-009, T-010, T-011]
plan_anchor: d3--job-definitions-triggers-notifications
---

# T-008 — YAML job loader/registry + dedicated cron scheduler + kill switch

## Scope

- new `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/def/` (`DaemonJobDefinition`, `DaemonJobLoader`, `DaemonJobRegistry`), `DaemonCronScheduler`
- `kukuvaia-engine/kukuvaia-core/src/main/resources/daemon/*.yaml` (built-ins)

## Approach

- Load `classpath*:daemon/*.yaml` + `.kukuvaia/daemon/*.yaml` (only in CLI mode — server-controlled in Web API mode per AGENTS.md); strict schema (unknown keys rejected), invalid file logged and skipped, startup summary.
- Fields per plan "Job definition (YAML)"; prompt placeholders limited to `{{fields.x}}`.
- Dedicated `ThreadPoolTaskScheduler` (`kukuvaia.daemon.scheduler-pool-size`, default 2) with a `CronTrigger` per job → `DaemonRuntime.submit`; `kukuvaia.daemon.enabled=false` stops scheduling and submissions.

## Acceptance

- Loader tests: valid, invalid, unknown key, web-mode ignores `.kukuvaia/daemon`.
- Cron registration test (trigger fires → submit called) without blocking `MemoryConsolidationJob`'s scheduler.
- Kill switch test.
