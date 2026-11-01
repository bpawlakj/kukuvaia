---
id: T-017
title: "Follow-up to T-001..T-003: close D1 security-review findings"
status: done
plan: ../plan.md
created: 2026-11-01
completed: 2026-11-01
commit: c6109f2
depends_on: [T-001, T-002, T-003]
blocks: [T-016]
plan_anchor: d1--fail-closed-controls-no-new-features
---

# T-017 — Follow-up to T-001..T-003: close D1 security-review findings

Follow-up to T-001, T-002 and T-003. The D1 phase review (security-reviewer, diff cf15928..d63839e)
found gaps in already-done work. Budget findings (no charge on non-timeout failures, non-atomic
accounting, check-not-reserve, unsanitized error text) were fixed inside T-005 where that code moved.

## Scope

- `kukuvaia-engine/kukuvaia-provider/src/main/java/ai/kukuvaia/provider/service/LlmProviderService.java`
- `kukuvaia-engine/kukuvaia-provider/src/main/java/ai/kukuvaia/provider/service/ChatModelCache.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentFactory.java`
- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/ToolRoundLimiter.java`
- tests in the matching test packages

## Approach

- Review #1 — parallel workers under DAEMON bypass the Copilot policy: `resolveWorkerModels(context)`
  drops Copilot-backed worker roles under DAEMON (falls back to per-specialist resolution, which is
  policy-checked, when none remain).
- Review #2 — `refuseCopilot` fails open when an assigned role has no provider metadata: throw under DAEMON.
- Review #7 — an explicit name other than a Copilot provider (e.g. `provider: supervisor` mapped to
  Copilot) must not count as the Copilot opt-in: under DAEMON an explicit role that resolves to Copilot
  is allowed only if the requested name itself names Copilot.
- Review #6 — a timed-out call can keep invoking tools: `ToolRoundLimiter.close()` on timeout; the
  limiter refuses after close or when the thread is interrupted.
- Review (nice-to-have) — a model that ignores "budget exhausted" keeps looping LLM rounds: after
  `max_tool_rounds + 3` refused calls the limiter throws, ending the call.
- Review "verify" — prove usage is cumulative across tool rounds (multi-round test).

## Acceptance

- `executeParallel(..., DAEMON, ...)` with a Copilot-backed `worker` role never runs on that model.
- DAEMON + assigned role without provider metadata → `DaemonProviderPolicyException`.
- DAEMON + explicit `supervisor` mapped to Copilot → refused; explicit `copilot` → allowed.
- After a timeout, further tool invocations of that call do not reach the real tool.
- A model that keeps requesting tools after exhaustion gets the call ended with an exception.
- With per-round usage 10/2 over 3 tool rounds + final, `SubAgentResult` reports the summed usage
  (or the test documents and the code compensates if the library does not accumulate).
