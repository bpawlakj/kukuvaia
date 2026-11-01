---
id: T-004
title: "Daemon tool allowlist + real token accounting (fail-closed)"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-001]
blocks: [T-005]
plan_anchor: d1--fail-closed-controls-no-new-features
---

# T-004 — Daemon tool allowlist + real token accounting (fail-closed)

## Scope

- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/DaemonAgentService.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/DaemonBudgetGuard.java` (usage API if needed)
- `application.yaml` (`kukuvaia.daemon.allowed-tools`)

## Approach

- `kukuvaia.daemon.allowed-tools`: empty/absent = no extra restriction (pass `null` personaTools — note PR #2 made an empty collection mean "no tools"); non-empty list = intersection via `SubAgentGuard.filterTools`.
- Use `SubAgentResult` usage → `budgetGuard.recordUsage(taskId, total)` and fill `DaemonTaskResult.tokenUsage`.
- If an LLM run reports no usage, record the specialist's `max_tokens` (conservative, fail-closed) and log WARN.
- Remove the false "updated by ProviderAuditLog" comment.

## Acceptance

- Daemon run with stub usage 123 → `daemon_tasks.token_usage = 123` and remaining budget decreases (extend `DaemonTaskMigrationTest` or a unit test with JdbcTemplate mock).
- Missing usage → `max_tokens` recorded.
- With `allowed-tools: [read_file]` a specialist listing `save_memory, read_file` gets only `read_file`.
- Budget exhausted → task skipped without calling the sub-agent (existing behaviour kept).
