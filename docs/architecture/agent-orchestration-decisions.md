# Agent Orchestration Decisions

Decisions made on **2026-04-06** about how sub-agents, the daemon runtime, and Spring AI fit together.

## Sub-agent orchestration

**Decision:** sub-agent as `@Tool` of the parent agent (Option 2 from the original trade-off). Moved from deferred (Phase 6+) to Phase 1.

**Implementation:**

- `SubAgentFactory` creates isolated `ChatClient` instances per specialist type.
- `SubAgentTool` is exposed as a Spring AI `@Tool` so the parent LLM decides when to delegate.
- Cost control via `maxTokens` per sub-agent; parallelism capped by a `Semaphore(3)`.
- Sub-agent specs loaded from YAML (`SubAgentSpecLoader`).

**Rationale:** agent orchestration is a first-class capability of the platform, not a deferred feature. Option 2 fits naturally into Spring AI's tool-calling loop — the parent LLM's reasoning picks the right specialist, and the child runs in an isolated context.

**Scope for new work:** all sub-agent capabilities should build on the existing `ai.kukuvaia.agent.subagent.*` package. Do not introduce a parallel orchestration framework.

## Daemon mode

**Decision:** background agent execution built on top of Spring Boot `@Scheduled` as the foundation, extended with event-driven triggers.

**Implementation:**

- `DaemonAgentService` — runs scheduled tasks using sub-agents.
- `DaemonScheduleGuard`, `DaemonBudgetGuard` — safety controls (skip-if-running, daily token budget, 80 % alert threshold).
- `PgNotifyDebouncer` — PostgreSQL `LISTEN/NOTIFY` for event-driven triggers.
- `WebhookController` — webhook triggers (HMAC-SHA256 validated, sliding-window rate-limited).

**Rationale:** the platform needs to run kukuvaia as a background daemon (similar to Claude Code's background agents). Combining cron (`@Scheduled`) with event-driven (PG `NOTIFY`) and webhook triggers covers the three useful activation patterns without adopting a separate scheduler.

**Scope for new work:** new background capabilities use this existing daemon infrastructure. Do not add a separate scheduling mechanism.

## Spring AI as the orchestration layer

**Decision:** Spring AI is sufficient for all current agent needs. Customisation is always possible where required.

**Rationale:** verified during the framework evaluation — `ChatClient`, `ToolCallAdvisor`, advisor chain, `ChatMemory`, `@McpTool`, and `Flux<ChatResponse>` streaming cover conversation, tools, memory, and SSE without any gap that would justify an alternative framework.

**Scope for new work:** Spring AI primitives first; only introduce custom components when a concrete limitation is identified and documented (as was done for sub-agent isolation and advisor ordering).

## Related documentation

- `docs/architecture/embabel-integration.md` — Kotlin/GOAP agent module (complements Spring AI for planning-heavy flows).
- `docs/architecture/memory-architecture.md` — memory subsystem that sub-agents and the daemon share.
- `docs/analyzes/koog-ai-evaluation.md` — rejected alternative (Koog AI), with the ideas that were adopted instead.
