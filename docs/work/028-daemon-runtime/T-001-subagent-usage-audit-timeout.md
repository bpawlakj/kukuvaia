---
id: T-001
title: "SubAgentFactory: usage, ProviderAuditLog, own executor, cancel on timeout"
status: done
plan: ../plan.md
created: 2026-11-01
completed: 2026-11-01
commit: 7d2bdb0
depends_on: []
blocks: [T-002, T-004, T-005]
plan_anchor: d1--fail-closed-controls-no-new-features
---

# T-001 — SubAgentFactory: usage, ProviderAuditLog, own executor, cancel on timeout

## Scope

- `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/agent/subagent/SubAgentFactory.java` (+ new `SubAgentResult` record in the same package)
- `kukuvaia-engine/kukuvaia-provider/src/main/java/ai/kukuvaia/provider/service/ProviderAuditLog.java` (only if a hook for sub-agent context is needed)
- Tests under `kukuvaia-engine/kukuvaia-core/src/test/java/ai/kukuvaia/agent/subagent/`

## Approach

- Take `.call().chatResponse()` instead of `.content()`; build `SubAgentResult(content, promptTokens, completionTokens)`; keep the existing `String execute(...)` overloads as thin wrappers so callers (DelegationTools, DaemonAgentService) keep compiling.
- Attach `ProviderAuditLog` to the per-call `ChatClient` next to `ToolResultSanitizingAdvisor` (advisor params `kukuvaia.provider/executionContext/specialist` already set).
- Replace `CompletableFuture.supplyAsync(...)` on `ForkJoinPool.commonPool` with a dedicated bounded virtual-thread executor; on `TimeoutException` call `future.cancel(true)` and throw `SubAgentTimeoutException`.
- Fix the misleading "tool calling handled by the ChatModel" comment (Spring AI 2.x auto-registers `ToolCallingAdvisor`).

## Acceptance

- Unit test with a stub `ChatModel` returning a `ChatResponse` with usage → `SubAgentResult` carries prompt/completion tokens.
- If the stub blocks longer than the spec timeout, then `SubAgentTimeoutException` is thrown and the underlying task is cancelled (interrupt observed).
- `kukuvaia.llm.*` metrics are emitted for a sub-agent call (ProviderAuditLog attached).
- `./gradlew :kukuvaia-core:test` green; existing `SubAgentFactoryParallelTest` and `DelegationToolsTest` unchanged and passing.
