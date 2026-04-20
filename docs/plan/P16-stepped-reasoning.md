# P16 — Stepped Reasoning: CoT Visibility, Interception & Intervention

**Status:** Draft — document only, phased activation behind a feature flag.
**Created:** 2026-04-19
**Inspired by:** [Knowledge Graphs as Real-Time CoT Correction](https://jmorrissettermdc.substack.com/p/knowledge-graphs-as-real-time-correction) — the concept of intervening in chain-of-thought reasoning rather than only in input context or output review.
**Relationship to other plans:** orthogonal to P14 (context sizing) and complementary to P15 (honest agent). Phase A feeds Pillar 1 (Provenance) with reasoning-step provenance. Phase C is where the P15 Pillar 3 calibration data becomes a correction signal, not just a routing hint.

## Hard rule — feature flag on, opt-in

All three phases below ship **behind a single feature flag** and are **additive** to the existing chat flow. Turning the flag off restores today's behaviour with zero residue.

```yaml
kukuvaia:
  reasoning:
    stepped: ${KUKUVAIA_STEPPED_REASONING:false}   # master switch
    visibility: ${KUKUVAIA_REASONING_VISIBILITY:false}   # Phase A
    interception: ${KUKUVAIA_REASONING_INTERCEPTION:false}   # Phase B
    correction: ${KUKUVAIA_REASONING_CORRECTION:false}   # Phase C
```

Constraint ladder: B requires A, C requires B. When the master switch is off, all three read as off regardless of sub-flags.

## Motivation

Spring AI does not surface chain-of-thought granularity. Today we see an LLM call go in and a final response come out. Between those two events, kukuvaia is blind. Three user-facing consequences:

1. **Thinking models waste information** — reasoning models (arcee-trinity, DeepSeek R1, o1) produce a rich chain that we only fall back on when `content` is empty (see `AgentService.extractResponseText`). The rest of the time we throw it away.
2. **Cross-turn drift** — the agent sometimes repeats a failed approach within a multi-turn dialogue because it has no in-run memory of what it already tried.
3. **No intervention point** — we can constrain the input (system prompt, Pillar 1 grounding) and review the output (Pillar 2 red-team), but the reasoning in between is uninspectable.

This plan gives kukuvaia step-level visibility first, then interception, then optional correction — each level is useful on its own.

## Non-goals

- **Replace or subclass Spring AI classes** — all integration is by composition. No inheritance off `OpenAiChatModel`, `ChatClient`, or advisors. Spring AI bumps versions aggressively; subclassing is a maintenance trap.
- **Rewrite the ToolCallAdvisor loop** — we keep tool execution unchanged. Stepped reasoning lives above the tool call loop, not inside it.
- **Make thinking models the default** — routing stays model-agnostic. Stepped features work whether or not the active model produces `reasoning_content`.
- **Replace P15 Pillar 2 (red-team)** — red-team is a second LLM reviewing the final draft. Stepped reasoning is mid-run. Both compose.

---

## Phase A — Reasoning step visibility (1–2 days)

**Flag:** `kukuvaia.reasoning.visibility`

Parse `reasoning_content` **post-hoc**, split it into steps, emit each as a `llm:reasoning-step` span to the existing `SessionOutputSink`. No behavioural change — the LLM call runs identically, we just report what happened.

### Mechanism

- Location: `AgentService.extractResponseText` already reads `AssistantMessage.getMetadata()` for reasoning fields. Extend it to, when the visibility flag is on:
  1. Pull the reasoning string (already implemented).
  2. Call a new `ReasoningStepParser.split(text) → List<Step>`.
  3. For each step, emit `SpanEventBlock(name="llm:reasoning-step", phase="start"/"end", attributes={index, preview, ...})` via `SpanEventEmitter`.
- The spans attach under the active `role:supervisor` span (use the parent span handle from `runChat`).

### `ReasoningStepParser` — heuristic splitting

One class, pure function. No dependencies. Priority order:

1. Explicit step markers: `Step 1:`, `Step N:`, `## Step`, `### Step`, `<step>...</step>`.
2. Thinking-tag blocks: `<think>…</think>`, `<thinking>…</thinking>`.
3. Double newlines after sentences (paragraph heuristic).
4. Fallback: whole reasoning as a single step.

Capped at 20 steps per response — beyond that, collapse the tail into one `… +N more steps` pseudo-step.

### CLI impact

CLI already has `activity/categorize.go` mapping the `llm:` prefix to `CategoryLLM`. Steps appear as nested nodes under `role:supervisor` in the expanded tree. Zero CLI code changes required beyond making `llm:reasoning-step` render nicely.

### Acceptance

- `KUKUVAIA_REASONING_VISIBILITY=true` + prompt routed to a thinking model → SSE stream contains N `llm:reasoning-step` events interleaved with the supervisor span.
- Flag off → zero `llm:reasoning-step` events in the stream (regression test).
- Non-thinking model (sonnet, gpt-4.1) with flag on → no steps emitted (graceful degradation).

### Cost

- One extra string parse per response (~O(N) in reasoning length, negligible).
- N additional SSE frames per response. Backpressure buffer in `SessionOutputSink` already handles this (capacity 500, drop policy on overflow).

---

## Phase B — Stepped agent loop (3–5 days)

**Flag:** `kukuvaia.reasoning.interception`

Introduce a `SteppedChatModel` that **wraps** (does not subclass) the existing `ChatModel` bean. It consumes the model via `.stream()` rather than `.call()`, groups tokens into steps using the same heuristics as Phase A, and emits `llm:reasoning-step` spans **live** as tokens arrive.

### Composition, not inheritance

```java
@Component
@ConditionalOnProperty("kukuvaia.reasoning.interception")
public class SteppedChatModel implements ChatModel {
    private final ChatModel delegate;
    private final ReasoningStepParser parser;
    private final SpanEventEmitter spanEmitter;
    // ...
    @Override
    public ChatResponse call(Prompt prompt) {
        // Collect .stream() into ChatResponse while emitting steps live.
    }
}
```

When the flag is off, `SteppedChatModel` is not registered and `ChatModel` resolves to the original bean via `@Primary` fallback. Spring AI's `ChatClient` interacts with whichever `ChatModel` is primary — no client code change.

### Why not call the streaming API directly from `AgentService`?

- `ChatClient` handles the `ToolCallAdvisor` loop internally. Bypassing it means reimplementing tool calling — weeks of work and a regression risk.
- A `ChatModel` wrapper slots under the advisor chain transparently. Each LLM call (whether it's the initial user turn or a tool-response-round) goes through the wrapper.

### Live emission

- On each token chunk: feed to parser state machine.
- When a step boundary is detected: emit `llm:reasoning-step` `start`, then `end` with the step text preview attribute.
- After the full stream ends: re-materialise the complete `ChatResponse` and return it to `ChatClient`.
- Backpressure: if `SessionOutputSink.emit` returns a drop signal (buffer full), accumulate the step internally and skip the span event. The final response is unaffected.

### Acceptance

- With the flag on, a multi-paragraph response produces multiple `llm:reasoning-step` events **during** generation (visible live in CLI activity tree), not only after completion.
- Tool-calling works identically — `ToolCallAdvisor` sees the same `ChatResponse` shape.
- With the flag off, `SteppedChatModel` is not wired and the original `OpenAiChatModel` is used directly.
- Thread safety: two concurrent sessions streaming simultaneously produce well-parented, non-interleaved events.

### Cost

- Streaming consumption has a small overhead vs. blocking `.call()` (typically <5%).
- Parser state machine is O(N) in tokens, single-pass.

---

## Phase C — Cognitive intervention (3–4 weeks, largest risk)

**Flag:** `kukuvaia.reasoning.correction`

Once per-step visibility and interception are in place, allow the system to **pause** between steps, query an anti-pattern store, and optionally inject a corrective system message before resuming. This is the article's core idea, scoped for kukuvaia.

### Scope — no full knowledge graph

The article recommends a KG. For kukuvaia (self-hosted, single-engine), a **flat anti-pattern table** in PostgreSQL is sufficient and composes with existing memory infrastructure.

```sql
-- Piggybacks on P15 Pillar 3 calibration events.
-- Already-planned schema — this plan reuses it, does not add new tables.
CREATE TABLE kukuvaia.anti_patterns (
    id              BIGSERIAL PRIMARY KEY,
    user_id         TEXT,          -- null = global pattern
    session_id      TEXT,          -- null = cross-session
    domain          TEXT NOT NULL, -- geography, dates, prices, legal, code
    pattern_embed   vector(384),   -- pgvector — semantic match on step content
    correction_hint TEXT NOT NULL, -- what to inject when matched
    captured_at     TIMESTAMPTZ DEFAULT NOW(),
    hit_count       INT DEFAULT 0
);
```

### Intervention flow

At each step boundary inside `SteppedChatModel`:

1. Embed the step text using the existing local ONNX embedding model (already running in `kukuvaia-memory`).
2. Top-K similarity search in `anti_patterns` (threshold 0.85, domain-scoped).
3. If hit:
   - Log the match, record `hit_count += 1`.
   - Inject a `SystemMessage` with `correction_hint` into the prompt (requires collaboration with `ToolCallAdvisor` — see risks).
   - Emit `llm:correction` span with the hit details.
   - Continue generation with the corrected context.
4. If no hit: continue normally.

### Population of anti-patterns

Three sources, matching the article's taxonomy:

| Source | Who writes | Examples |
|--------|------------|----------|
| User correction | `MemoryExtractionService` watches for `"to nieprawda"`, `"Kreta to wyspa"`, explicit overrides | Immediate next turn |
| Tool failure log | Existing `ProviderAuditLog` + `ToolHookDispatcher` emit failure events; cron consolidates into patterns | Batch |
| Calibration driven | P15 Pillar 3 triggers pattern creation when domain calibration drops below threshold | Nightly |

### Acceptance

- With the flag on, a prompt known to trigger a registered anti-pattern produces a `llm:correction` span and the final answer matches the hint.
- Flag off → no `anti_patterns` lookups, no spans, no injected messages.
- Correction events are observable in Prometheus (`kukuvaia.reasoning.corrections.total`) and the admin UI.
- Roll-out safety: a kill switch per domain (`correction.domains.geography.enabled=false`) lets operators disable a misbehaving pattern without a redeploy.

### Open design questions (resolve at activation time)

- **Injection mechanism** — can `ToolCallAdvisor` accept a mid-run system message? If not, the correction happens on the next LLM round, which is still cheaper than waiting for the full response.
- **Pattern decay** — how fast does an anti-pattern become stale? Default proposal: halve hit-count every 30 days; drop below 1 → retire.
- **User-visibility** — surface corrections to the user (`💡 I was about to repeat a previous mistake — thanks to the pattern log`) or keep silent?

---

## Cross-phase design principles

- **Composition over inheritance** — never subclass Spring AI classes. Every integration point is a bean wrapper with a `@ConditionalOnProperty` guard.
- **Feature-flag purity** — with all flags off, the code paths behave bit-identically to today. No phantom CPU, no phantom DB rows, no phantom log lines.
- **Emitter reuse** — Phase A, B, C all emit into the same `SessionOutputSink`. The CLI activity tracker gets the stepped view for free.
- **Measurable impact** — each phase exposes Prometheus metrics (see per-phase acceptance). Ship A, measure the dialogue quality delta, then decide whether B/C carry their weight.

## Risks

| Risk | Phase | Mitigation |
|------|-------|------------|
| Heuristic splitter miscounts steps | A, B | Ship with a conservative default (whole reasoning as one step if no markers); iterate based on real traces |
| Streaming wrapper breaks tool calling | B | Integration test matrix: chat + tool-only, chat + multi-tool, tool-only, no-tool. Ship behind flag. |
| Anti-pattern injection makes responses stilted | C | Injection prompt is concise ("Note: a prior similar attempt failed because X. Reconsider."); A/B compare with flag on/off |
| Embedding cost explodes | C | Cache per-step embeddings for the duration of a turn; reuse if step text repeats |
| Spring AI 1.2 changes `ChatModel` shape | B, C | Wrapper is one file, easy to bring forward. Pin spring-ai-bom per migration. |
| Users dislike seeing reasoning | A | CLI: reasoning-step rendering is collapsed by default under the supervisor node; user opens via `ctrl+o` |

## Rollback

All phases are strictly additive:

- Phase A: unset `KUKUVAIA_REASONING_VISIBILITY`. The emit loop short-circuits.
- Phase B: unset `KUKUVAIA_REASONING_INTERCEPTION`. `SteppedChatModel` is not registered; the original `ChatModel` is `@Primary`.
- Phase C: unset `KUKUVAIA_REASONING_CORRECTION`. The intervention hook returns immediately.
- Master switch: unset `KUKUVAIA_STEPPED_REASONING`. Overrides all sub-flags.

Database: Phase C adds one table. Removal is a standard Flyway down migration, no data loss for other features.

## Recommended execution order

1. **Phase A** — low risk, high observability win. Ship alone for 2–4 weeks of production use. Gather traces.
2. **Phase B** — only if Phase A traces show step-level granularity is valuable enough to justify streaming overhead.
3. **Phase C** — only if Phase B is stable and you observe repeated failure patterns in dialogue logs that a flat anti-pattern store would catch.

## Integration with other plans

- **P14 (tiered context)** — no dependency in either direction. Context sizing and reasoning visibility compose cleanly.
- **P15 Pillar 1 (Provenance)** — Phase A steps become provenance entries. Each reasoning step can carry its own `Provenance(MODEL_ONLY, stepIndex, UNVERIFIED)` tag.
- **P15 Pillar 3 (Empirical Calibration)** — feeds Phase C's anti-pattern generation directly. Calibration drop in a domain triggers anti-pattern creation.
- **P15 Pillar 2 (Red-Team)** — red-team runs after drafting; Phase C runs during drafting. Together they form a two-layer correction system.

## Future extensions (deferred)

- **Reasoning replay** — store reasoning_content for admin inspection, replay a turn with different model to compare.
- **Anti-pattern sharing** — optional export/import of anti-pattern lists between deployments (community contribution path).
- **Full KG upgrade** — only if flat anti-pattern table proves insufficient. Schema → graph migration is a separate plan (`P-KG-TBD`).
