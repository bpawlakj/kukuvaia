---
id: T-018
title: "Follow-up to T-005..T-008: close D2 security-review findings"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-005, T-006, T-007, T-008]
blocks: [T-016]
plan_anchor: d2--runtime-core-executor-spi-persistence-api
---

# T-018 — Follow-up to T-005..T-008: close D2 security-review findings

Follow-up to T-005..T-008. The D2 phase review (security-reviewer, diff 903f186..caea9e3) found gaps
in already-done work.

## Scope

- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/DaemonRuntime.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/DaemonTaskStore.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/exec/SpecialistExecutor.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/exec/JavaJobExecutor.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/def/DaemonJobRegistry.java`, `DaemonJobParser.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentFactory.java`
- `kukuvaia-engine/kukuvaia-provider/src/main/java/ai/kukuvaia/provider/service/LlmProviderService.java`
- tests in the matching packages

## Approach

- #1 budget: reserve / conservative charge = the run's `token_budget` (cumulative prompt+completion over all
  rounds), not the per-response `max_tokens`; job `max_tokens` lowers the specialist's cap on the actual call;
  `callSpecialist` reserves `max_tokens × (max_tool_rounds + 1)`; a run exceeding its token_budget is logged.
- #2 overlap/reservation released on every admission path (try/finally around admission after acquire).
- #3 zombie runs: after a deadline/cancel the slot and overlap lock are held until the work really ends
  (released by a completion hook), so a stuck run still counts against concurrency and overlap.
- #4 legacy providers under DAEMON: only a Copilot-named legacy provider may be selected explicitly; any other
  legacy provider (no metadata) is refused; `provider:` in YAML validated against a name pattern.
- #5 startup warning when `kukuvaia.daemon.allowed-tools` is empty (specialist allowlists still apply).
- #6 restart recovery: QUEUED/RUNNING rows left by a previous process are marked CANCELLED ("interrupted by
  restart"); interrupted RUNNING LLM rows are charged a conservative estimate.
- #8 registry: a replacement that fails validation never removes the job it would replace.
- Nice-to-have: atomic queue bound; cleanup also on `Error`; parser rejects integers outside int range.
- #7 (`pg-notify` not wired) is T-010 scope, not changed here.

## Acceptance

- A specialist run with many tool rounds reserves/charges its token_budget; concurrent runs cannot pass
  admission beyond daily budget − Σ token_budget of in-flight runs.
- `tryReserve` throwing does not leave the job's overlap lock held.
- A run that ignores interruption keeps its slot and overlap lock until it actually returns.
- DAEMON + explicit legacy provider not named Copilot → refused.
- After a simulated restart, stale QUEUED/RUNNING rows are CANCELLED with a reason.
- A project job that fails validation leaves the built-in with the same name active.
