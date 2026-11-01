---
id: T-020
title: "Follow-up to T-012: close D4 security-review findings"
status: done
plan: ../plan.md
created: 2026-11-01
completed: 2026-11-01
commit: a509456
depends_on: [T-012]
blocks: [T-016]
plan_anchor: d4--embabel-executor
---

# T-020 — Follow-up to T-012: close D4 security-review findings

Follow-up to T-012. The D4 phase review (security-reviewer, commit 30e539f) returned PASS WITH FIXES.

## Scope

- `kukuvaia-engine/kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/daemon/` (`DaemonEmbabelRuns`, `EmbabelAgentExecutor`)
- `kukuvaia-engine/kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/config/` (`GuardedChatModel`, `EmbabelModelBridgeConfig`)

## Approach

1. (HIGH) Policy lookup walks the whole `parentId` chain; a call with no visible Embabel process while any
   daemon run is active is treated as daemon (Copilot / unknown provider refused — fail closed).
2. (HIGH) A run stays registered until its process really ends; once killed or past its deadline every
   further model call is refused. Killed / timed-out runs are charged `max(spent, token_budget)`
   (an in-flight HTTP call cannot be cancelled and may still spend).
3. (HIGH) `GuardedChatModel` counts tokens per run and refuses the next call once `token_budget` is
   reached — the cap holds inside a single action (tool loops, retries), not only between actions.
4. Audit error texts are fixed strings (exception class / termination policy); details stay in server logs.
5. Usage is "reported" only if every guarded call was recorded with usage; charge = max(guard count, Embabel usage).
6. Embabel jobs are refused unless every `ModelProvider` bean is `KukuvaiaModelProvider` (all models guarded).
7. Copilot flag resolved per call from `ChatModelCache` (no stale flag after a provider change).
8. (LOW, accepted) the safety boundary is also added to interactive Embabel prompts (intended: AGENTS.md rule).
9. Agents declaring Embabel tool groups are rejected for daemon jobs (no tools in v1).

## Acceptance

- Grandchild process and no-process-while-daemon-active calls are refused for Copilot.
- Calls after kill / deadline are refused; killed runs charge at least `token_budget`.
- An action making several model calls is stopped at `token_budget` (guard counter).
- Stored error contains no exception message text from the agent.
- Full suite green.
