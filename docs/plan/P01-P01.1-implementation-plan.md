# P01 + P01.1 — Implementation Plan & Honest Review

**Created:** 2026-04-18
**Status:** Draft for approval
**Scope:** Combined implementation plan for P01 (engine observability) and P01.1 (CLI activity tracker).
**Prerequisite order:** P01 core → P01 `SpanEventBlock` extension → P01.1 Phase 1 → P01.1 Phase 2.

---

## Executive summary

P01 and P01.1 look tidy on paper. On close reading against the current codebase, the plans contain **two architectural blockers**, **three integration risks**, and **several underspecified corner cases**. None are fatal, but each lands real work that is not yet estimated. Total honest effort is ~25–30 h (P01 estimated ~14.5 h is optimistic by roughly 40%).

This document:

1. Lays out the implementation sequence with the concrete files and gates.
2. Flags each gap found during review, by severity, with a concrete fix or decision request.
3. States what the plans **do not** cover and should be added before start.

---

## Implementation sequence

```
Stage A — P01 core metrics (2–3 days, can ship alone)
Stage B — P01 SpanEventBlock SSE extension (2 days)  ← unlocks P01.1
Stage C — P01.1 Phase 1 (CLI consumer + state)      (2–3 days)
Stage D — P01.1 Phase 2 (CLI view + theming)         (2 days)
Stage E — Hardening, observability of observability  (1 day)
```

Each stage is independently mergeable. Early stages provide value without later ones: Stage A alone gives Prometheus + Grafana. Stage B unlocks Stage C.

---

## Stage A — P01 core metrics

### Deliverables

- `kukuvaia-app` depends on `spring-boot-starter-actuator` + `micrometer-registry-prometheus`.
- `kukuvaia-core` and `kukuvaia-memory` declare `micrometer-core` API.
- `kukuvaia-core` adds `spring-boot-starter-aop`.
- `ProviderAuditLog`, `SmartMemoryAdvisor`, `MemoryExtractionService`, `DaemonAgentService`, `SubAgentFactory` receive a `MeterRegistry` parameter and emit the metrics listed in P01 §Metric naming convention.
- New: `advisors/ToolCallMetrics.java` (AOP aspect timing `@Tool` methods).
- New: `api/SessionMetrics.java` (gauge over `kukuvaia.sessions WHERE status='active'`).
- `application.yaml` exposes `/actuator/prometheus`, `/actuator/health`, `/actuator/metrics`.
- Existing unit tests updated to inject `SimpleMeterRegistry`.

### Acceptance gates

- `./gradlew :kukuvaia-core:test :kukuvaia-memory:test` — green (after test fixes for the constructor change).
- `curl http://localhost:8080/actuator/prometheus | grep -c '^kukuvaia_'` ≥ 8 after one chat turn.
- `/actuator/health` returns 200 with components list.
- No high-cardinality tag exceeds ~50 distinct values on first run.

### Prerequisites

- `CommandRouterTest` and `ChatClientConfigTest` compile errors (pre-existing) must be fixed first. `MeterRegistry` changes extend the test-update surface, not practical while core tests cannot compile at all.

---

## Stage B — P01 SpanEventBlock SSE extension

### Deliverables

- `output/block/SpanEventBlock.java` — new sealed-member of `OutputBlock`.
- `Sinks.Many<OutputBlock>`-per-session registry (new class `output/SessionOutputSink.java` replacing or wrapping the current `Flux<OutputBlock>` approach).
- `advisor/SpanEventEmittingAdvisor.java` — subscribes to span lifecycle.
- Engine-side emission at span endpoints (`role:*`, `tool:*`, `llm:*`, `memory:*`).
- SSE wire format `type: "span_event"` shipped on `/api/chat`.
- Round-trip integration test.

### Acceptance gates

- Given a `/plan` turn, SSE stream contains at least one `role:*` start, N `tool:*` pairs, one `role:*` end — in that structural order.
- Concurrent sub-agent test: two parallel `delegateToSubAgent` calls produce well-parented, non-interleaved JSON frames.
- Token delta throttling: never more than 10 `delta` frames per second per span in a smoke test.
- Cancellation: client disconnect → in-flight spans close with `status="cancelled"`.

### Blockers (see review §1–§2)

Two architectural gaps must be resolved here before code starts.

---

## Stage C — P01.1 Phase 1 (CLI consumer)

### Deliverables

- `kukuvaia-cli/internal/tui/activity/` package per plan (`model.go`, `messages.go`, `node.go`, `categorize.go`, `update.go`, `verbs.go`, `view.go`, `styles.go`).
- SSE decoder extended to dispatch `SpanStartMsg`, `SpanEndMsg`, `SpanAttributeDeltaMsg`, `SseDisconnectedMsg`.
- Span-tree assembly with orphan buffering, reset on top-level span end, hard reset on `SseDisconnectedMsg`.
- Unit tests with synthetic event streams covering flat, nested, out-of-order, and cancelled scenarios.

### Acceptance gates

- Unit test: tree reconstruction for the 4 scenarios above.
- `go test ./internal/tui/activity/...` — green.
- No goroutine leaks (`goleak` check).

---

## Stage D — P01.1 Phase 2 (CLI view)

### Deliverables

- Collapsed layout (summary + animator).
- Expanded tree with box-drawing, depth cap 3, error / cancelled symbols per plan.
- Lipgloss styles sourced from theme via `go generate`.
- `ctrl+o` keybinding wired; idle-state hides component.
- Verb override loader (`~/.kukuvaia/verbs.txt`).

### Acceptance gates

- Manual smoke test: run `/plan` turn, press `ctrl+o`, verify expand/collapse.
- Error-path smoke test: trigger a tool that fails; verify `✗` rendering.
- Resize smoke test: change terminal width mid-turn; re-render does not break layout.

---

## Stage E — Hardening

- OTel agent setup doc in `docs/architecture/observability.md`.
- `README.md` gets an "Observability (optional)" section with agent attachment instructions.
- Grafana dashboard JSON committed under `deploy/grafana/dashboards/` (stub acceptable — final dashboards iterate in staging).
- Prometheus scrape config example.

---

## Review — gaps and corner cases

Organized by severity. Each item is a real finding against the current codebase (`Flux<OutputBlock>` reactive pipeline, OTel javaagent model, Spring AI advisor contract, Go Bubbletea loop).

### §1 — BLOCKER: `Flux<OutputBlock>` is pull-based; OTel span callbacks are push-based on arbitrary threads

**Where the plan is thin:** P01 `SpanEventEmittingAdvisor` (§SSE extension Step B) is described as a `SpanProcessor` that calls `sseRegistry.emit(sessionId, new SpanEventBlock(...))`. The existing controller returns `Flux<OutputBlock>` from `AgentService.streamChat()` which is a pull chain. There is no mechanism to push events into a `Flux` from an external thread.

**Fix required before Stage B:**

- Introduce `Sinks.Many<OutputBlock>` per session. `AgentService.streamChat()` returns that sink's `Flux.asFlux()`. Chat-response blocks and span-event blocks are both `tryEmitNext`-ed into the same sink.
- Sink is completed (`tryEmitComplete`) after the chat turn (post `MemoryExtractionService.extract`) AND the chat-turn top-level span has ended.
- `Sinks.many().multicast().onBackpressureBuffer()` with serialized emission (`EmitFailureHandler.busyLooping(...)`) to satisfy the thread-safety requirement P01 mentions but does not implement.

**Effort impact:** +3–4 h beyond the "add an advisor" story in the plan. Touches `AgentService`, `CommandRouter`, `ChatController`, and all places that return `Flux.just(TextBlock)`.

### §2 — BLOCKER: `SpanProcessor` registration vs. OTel javaagent

**Where the plan is thin:** P01 Step 9 recommends attaching OTel javaagent. SpanEventBlock extension (Step B) registers a custom `SpanProcessor`. These two paths **do not compose** — the javaagent builds its own `SdkTracerProvider` and does not expose a public extension point for adding application-authored `SpanProcessor`s.

**Options (choose one — open decision):**

1. **Drop the javaagent, use the OTel SDK directly.** Add `opentelemetry-sdk` + `opentelemetry-exporter-otlp` Gradle deps in `kukuvaia-app`. Build the `SdkTracerProvider` as a Spring bean, register `SpanEventEmittingAdvisor` and the OTLP exporter as processors. Lose auto-instrumentation of JDBC / HTTP client unless we add `opentelemetry-instrumentation-*` libraries manually (more deps, more version coupling).
2. **Keep the javaagent for platform spans, emit `SpanEventBlock` from a Micrometer-level hook**, not an OTel SpanProcessor. Concretely: create a small wrapper that both records the `Timer.Sample` AND emits to the sink. Loses OTel-native lineage but avoids the SDK/agent clash.
3. **Use the OTel javaagent's extension mechanism** (`otel.javaagent.extensions=/path/to/ext.jar`). Requires packaging a separate extension jar; adds build complexity.

**Recommendation:** **Option 2** — decoupled emission. Reserves the javaagent for what it is good at (auto-instrumenting HTTP/JDBC) and keeps application-level emission simple. Cost: "span lineage" in Jaeger does not include custom kukuvaia spans. Acceptable trade-off since live CLI rendering is the primary value.

**Effort impact:** choice affects architecture; locking in Option 2 is +0 vs. plan. Options 1 or 3 add ~1–2 days.

### §3 — HIGH: Session ID propagation across child spans

**Where the plan is thin:** "Every span carries `kukuvaia.session.id`" is stated, but span attributes in OTel do **not** automatically propagate to child spans. Setting the attribute only on the top-level span means sub-agent spans and tool spans will not carry it, and the SSE filter in `SpanEventEmittingAdvisor` (which requires this attribute) will drop them.

**Fix:** use **OTel Baggage**. Set `session.id` in baggage at request entry (`ChatController` or an early advisor); every span reads baggage at start and copies the value to its attribute. This is the idiomatic OTel pattern for request-scoped metadata.

**Effort impact:** +1 h.

### §4 — HIGH: MDC leak on exception path in `ProviderAuditLog`

**Where the plan is thin:** P01 Step 3 `before()` calls `MDC.put(...)` and `after()` calls `MDC.remove(...)`. If a downstream advisor throws before `after()` runs, MDC entries leak to the next request that reuses the thread. Tomcat worker pools reuse threads aggressively.

**Fix:** either

- `try { ... } finally { MDC.remove(...) }` in a Spring AI advisor wrapper. Requires cooperation from Spring AI's advisor chain error handling — verify via test.
- Or clear MDC in a `HandlerInterceptor` at the HTTP layer on request completion (covers all cases including async).

**Effort impact:** +1 h.

### §5 — HIGH: Prometheus cardinality risk (`userId` as a tag)

**Where the plan is thin:** `kukuvaia.memory.injection.*` tagged with `userId`. If the installation has even 100 users, that is 100× every other label combination. `kukuvaia.llm.tokens.session` tagged with `sessionId` — sessions are unbounded. These would explode the Prometheus time series over weeks.

**Fix:**

- Drop `userId` from metric tags. Keep in structured logs and OTel span attributes (which do not have the same cardinality cost).
- Convert `kukuvaia.llm.tokens.session` (gauge per session) into a **sliding-window counter per period**, or move it to logs / OTel only.

**Effort impact:** +0.5 h (removal is easier than addition).

### §6 — MEDIUM: Tool AOP requires Spring proxy

**Where the plan is thin:** AOP `@Around("@annotation(org.springframework.ai.tool.annotation.Tool)")` only intercepts calls made **through the Spring-managed proxy**. Spring AI's `MethodToolCallbackProvider` may invoke the target method via reflection on the unwrapped instance — in which case the aspect never fires.

**Fix:** verify at implementation time with a one-line test: a `@Tool`-annotated method that increments a counter in the aspect; chat that invokes the tool; assert counter > 0. If AOP does not fire, fall back to wrapping the tool callback registration (custom `ToolCallbackProvider` wrapper that times invocations directly).

**Effort impact:** possible +2–3 h if AOP fallback is needed. Flag early.

### §7 — MEDIUM: `SessionMetrics` gauge fires a SQL query on every Prometheus scrape

**Where the plan is thin:** `Gauge.builder(... j -> j.queryForObject("SELECT count(*) ...", ...))` evaluates the lambda on each scrape (every 15 s by default). Cheap query, but a per-scrape DB round-trip is avoidable.

**Fix:** cache the value with `@Scheduled(fixedRate = 30_000L)` or use `MultiGauge` with a refreshing update. Alternative: use `ToDoubleFunction` bound to a cached `AtomicLong` refreshed on session mutations.

**Effort impact:** +0.5 h.

### §8 — MEDIUM: Async memory extraction spans land AFTER chat response

**Where the plan is thin:** `MemoryExtractionService.extract()` runs async via `memoryExtractionExecutor` after the chat response is returned. Its OTel spans — if emitted as `memory:*` children of the top-level turn span — would either:

- Arrive after the top-level span ended → CLI tree would already have reset, leading to orphan nodes that flash briefly after each turn.
- Or arrive as separate top-level spans → they are valid but confusing ("why is the spinner spinning again after I got my answer?").

**Fix (decision needed):**

- **(A)** Do not emit `memory:extract` spans to SSE. Keep them only in Prometheus / Jaeger. CLI stays silent during post-turn extraction.
- **(B)** Emit them as a distinct `category: "background"` that the CLI renders in a separate, subtle indicator (e.g., a footer line "• indexing 3 memories…").

**Recommendation:** **(A)** for first ship — simpler, less risk of UX clutter. Revisit if users ask.

**Effort impact:** +0 for (A), +0.5 day for (B).

### §9 — MEDIUM: Depth cap 3 truncates deep delegation chains

**Where the plan is thin:** P01.1 caps rendered depth at 3 (`supervisor → worker → tool`). Kukuvaia has `SubAgentFactory` supporting nested sub-agents — realistic depth is 4+ for a supervisor that delegates to a worker that delegates to a specialist that calls a tool. The tree would render `… +N deeper` exactly where the interesting work is.

**Fix (decision needed):**

- **(A)** Raise cap to 5. Layout remains readable up to that.
- **(B)** Keep cap 3; on expand, allow `+` to drill deeper into a specific node.

**Recommendation:** **(A)** — simpler, covers real cases.

**Effort impact:** +0.

### §10 — LOW: Pre-existing broken tests block test-update work in Stage A

Already flagged in Stage A prerequisites. Concrete action: `CommandRouterTest.java:36` and `ChatClientConfigTest.java:46` must be fixed (add `mock(PlanningModeService.class)` argument to their constructor invocations) before MeterRegistry propagation makes the test-update surface larger.

**Effort impact:** +0.5 h if done ahead of Stage A.

### §11 — LOW: README does not mention OTel setup

After P01 ships, README's prerequisites need a new optional section for OTel collector (OTLP endpoint, Prometheus/Grafana stack). Without it, a new contributor may miss the whole observability story.

**Effort impact:** +0.5 h in Stage E.

### §12 — LOW: No feature flag / rollback beyond revert

Adding MeterRegistry to N constructors is additive but non-trivial to unwind. Plan has no "disable observability" kill switch if it misbehaves in production (e.g., Prometheus endpoint hogs response time).

**Fix:** `management.metrics.export.prometheus.enabled: ${KUKUVAIA_METRICS_ENABLED:true}` — a single env var to hard-off. Does not remove the code but stops Prometheus export.

**Effort impact:** +0.25 h in Stage A.

### §13 — LOW: CLI test strategy unspecified

P01.1 lists Bubbletea unit tests for state but does not describe harness. Bubbletea `tea.Model.Update()` is pure — can be driven by constructing messages and asserting model state. Stage C acceptance criteria list correct tests; add to the plan a note that the test file layout is `activity/<feature>_test.go` using the standard `go test` runner.

**Effort impact:** +0 (spec only).

### §14 — LOW: SSE rate limit under span storms

A large plan can generate 30+ tool spans + deltas. Start + end + periodic delta per span = 60+ frames per turn. No backpressure mentioned on the SSE channel. Usually not a problem for terminal clients, but worth defining a ceiling.

**Fix:** `Sinks.Many` with bounded buffer (e.g., 500). On overflow, drop oldest `delta` frames (not `start`/`end`). Log a warning. Define in Stage B.

**Effort impact:** +0.5 h.

---

## Honest effort estimate

| Stage | Plan-as-written | Realistic with review findings |
|-------|-----------------|--------------------------------|
| A — Core metrics | 8 h | 10 h (incl. pre-existing test fix, MDC, cardinality trim, kill switch) |
| B — SpanEventBlock | 4.5 h | 8 h (incl. Sinks refactor, baggage, Option 2 emission path, backpressure) |
| C — CLI Phase 1 | ~3 days | 2–3 days (well-scoped) |
| D — CLI Phase 2 | ~2 days | 2 days |
| E — Hardening | — | 1 day |
| **Total** | ~14.5 h + 5 days | **~18 h + 5–6 days (~25–30 h)** |

The plan's 14.5 h figure is ~40% optimistic. This is a normal planning-error magnitude — flagging it explicitly so expectations are set.

---

## Decisions required before starting

These four items must be resolved before Stage B begins. Each is called out in the review.

| # | Decision | Options | Recommendation |
|---|----------|---------|----------------|
| D1 | OTel SDK vs. javaagent | §2: SDK-native / Agent+decoupled / Agent extension | **Agent + decoupled** (Option 2) |
| D2 | Memory-extraction span in CLI | §8: hide / show-as-background | **Hide** for first ship |
| D3 | Depth cap in CLI tree | §9: raise to 5 / keep 3 with drill | **Raise to 5** |
| D4 | High-cardinality tags | §5: drop `userId` from metrics | **Drop** (confirm) |

---

## What this plan does NOT cover

Called out so nothing ships implicitly:

- **Log aggregation** — SLF4J → ELK / Loki is out of scope. P01 keeps SLF4J as-is.
- **Alerting rules** — Prometheus alertmanager config is not written; dashboards only.
- **Tracing backend selection** — Jaeger vs. Tempo vs. Honeycomb not decided. Plan emits OTLP; collector destination is deployment concern.
- **Cost tracking** (P07) and **model fallback** (P03) integrations — both will benefit from P01 metrics, but their implementations are their own plans.
- **Provenance integration with P15 Pillar 1** — `SpanEventBlock` does not carry `sources`. If/when P15 ships, verify whether spans need provenance tagging.

---

## Rollback path

Each stage is revertable by Git history. No data migrations are introduced (all observability is additive: new tables would be avoided; active-session gauge reads existing table).

Hard kill for production incidents:

```
KUKUVAIA_METRICS_ENABLED=false    # disables Prometheus endpoint (§12 fix)
# OTel agent: simply do not attach -javaagent
```

CLI: if the activity tracker misbehaves, `KUKUVAIA_ACTIVITY_DISABLED=true` hides the component (already in P01.1 §Configuration).

---

## Ready-to-start checklist

Before Stage A starts, confirm:

- [ ] D1–D4 decisions made and captured (above).
- [ ] Pre-existing test compilation errors fixed (`CommandRouterTest`, `ChatClientConfigTest`).
- [ ] Stakeholder aware of realistic 25–30 h estimate (not 14.5 h).
- [ ] `Sinks.Many` refactor approach reviewed — touches `AgentService` / `ChatController` signatures.
- [ ] Baggage vs. attribute decision for session.id accepted (§3).

When all five are green, Stage A may begin.
