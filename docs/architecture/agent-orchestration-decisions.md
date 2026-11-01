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

**Decision (2026-04-06, extended 2026-11-01):** background execution on Spring primitives
(`TaskScheduler` / `CronTrigger`, `@Scheduled` for housekeeping), with event-driven triggers. Since
initiative 028 the daemon is a **runtime that hosts four kinds of workers under the same guards**:
LLM specialist, deterministic Java job, Embabel agent, and remote MCP tool. Jobs are defined in YAML.

**Implementation:** see `docs/architecture/daemon-runtime.md` (living description).

- `DaemonRuntime` handles admission (kill switch, overlap, queue, budget reservation), a bounded pool,
  the hard deadline with cancellation, fail-closed token accounting, the `daemon_tasks` audit trail and
  notifications.
- `DaemonExecutor` SPI: `SpecialistExecutor`, `JavaJobExecutor`, `EmbabelAgentExecutor` (kukuvaia-agents),
  `McpToolExecutor`.
- Triggers:
  - `DaemonCronScheduler` (its own scheduler pool);
  - `WebhookController` (`POST /api/webhooks/{route}` → 202 + task id);
  - `PgNotifyListener` → `PgNotifyDebouncer`;
  - `DaemonController` (`/api/daemon/*`).
- `DaemonAgentService` stays as a thin facade over the runtime for existing in-process callers.

**Rationale:** the platform needs to run kukuvaia as a background daemon (similar to Claude Code's
background agents). One runtime for every kind of worker means that every DoS, provider and audit
control is implemented once and applies to all of them. Combining cron, PG `NOTIFY` and webhook triggers
covers the useful activation patterns without adopting a separate scheduler.

**Scope for new work:** new background capabilities are a new `DaemonExecutor` kind or a `DaemonJob`
bean plus a YAML job. Do not add a separate scheduling or orchestration mechanism. `kind: a2a` is
deferred (T-015 spike: the Java SDK 1.0 is still alpha).

## Spring AI as the orchestration layer

**Decision:** Spring AI is sufficient for all current agent needs. Customisation is always possible where required.

**Rationale:** verified during the framework evaluation — `ChatClient`, `ToolCallAdvisor`, advisor chain, `ChatMemory`, `@McpTool`, and `Flux<ChatResponse>` streaming cover conversation, tools, memory, and SSE without any gap that would justify an alternative framework.

**Scope for new work:** Spring AI primitives first; only introduce custom components when a concrete limitation is identified and documented (as was done for sub-agent isolation and advisor ordering).

## Related documentation

- `docs/architecture/daemon-runtime.md` — the daemon runtime: executor kinds, YAML schema, triggers, guards, API, config.
- `docs/architecture/embabel-integration.md` — Kotlin/GOAP agent module (complements Spring AI for planning-heavy flows).
- `docs/architecture/memory-architecture.md` — memory subsystem that sub-agents and the daemon share.
- `docs/analyzes/koog-ai-evaluation.md` — rejected alternative (Koog AI), with the ideas that were adopted instead.
