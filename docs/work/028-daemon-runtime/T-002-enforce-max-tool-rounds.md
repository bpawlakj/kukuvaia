---
id: T-002
title: "Enforce max_tool_rounds with a counting ToolCallback decorator"
status: pending
plan: ../plan.md
created: 2026-11-01
completed: null
commit: null
depends_on: [T-001]
blocks: [T-005]
plan_anchor: d1--fail-closed-controls-no-new-features
---

# T-002 — Enforce max_tool_rounds with a counting ToolCallback decorator

## Scope

- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentFactory.java`
- new `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/ToolRoundLimiter.java` (decorator)
- tests in the subagent test package

## Approach

- Per sub-agent call, wrap each resolved `ToolCallback` in a decorator sharing one `AtomicInteger`; when calls exceed `spec.maxToolRounds()` return a tool result "tool budget exhausted — answer with what you have" (do not throw — lets the model finish) and log WARN.
- Applies to `execute` and `executeWithModel` paths; the limit comes from the specialist spec (default 10).

## Acceptance

- If a stub model requests N+1 tool calls with `max_tool_rounds: N`, then the (N+1)th returns the exhaustion message and the delegate tool is invoked exactly N times.
- Counter is per call (two concurrent sub-agent calls do not share a budget).
- Satisfies AGENTS.md sub-agent DoS rule together with maxTokens + timeout.
