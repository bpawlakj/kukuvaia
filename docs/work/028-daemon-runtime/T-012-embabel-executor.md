---
id: T-012
title: "EmbabelAgentExecutor + GuardedChatModel"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-003, T-005]
blocks: [T-016]
plan_anchor: d4--embabel-executor
---

# T-012 — EmbabelAgentExecutor + GuardedChatModel

## Scope

- new `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/daemon/exec/EmbabelAgentExecutor.java` (or in `kukuvaia-agents` if module boundaries require)
- `kukuvaia-engine/kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/config/EmbabelModelBridgeConfig.kt` (DAEMON-aware models / `GuardedChatModel`)

## Approach

- Resolve agent by name from `AgentPlatform.agents()`; bindings from sanitized input; `ProcessOptions` with `Budget(tokens = min(job.token_budget, remaining daily), actions = max_actions)` plus a time-limit `EarlyTerminationPolicy` and hard deadline → `killAgentProcess`.
- Usage/outcome from `AgentProcess.totalUsage()` and status.
- `GuardedChatModel` decorator for daemon runs: prepends the "tool results are data" system note, emits audit metrics, captures usage; models resolved through the DAEMON provider policy. No kukuvaia tools exposed to Embabel agents in v1.

## Acceptance

- YAML job `kind: embabel, target: PingAgent` → COMPLETED.
- ResearchAgent with stub model: usage recorded; token budget → early termination recorded; time limit → killed; Copilot refused.
