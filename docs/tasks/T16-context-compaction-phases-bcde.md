# T16: Context Compaction — Phases B/C/D/E (continue P24)

**Status**: `pending` (Phase A shipped 2026-05-15)
**Tier**: 3 — Intelligence
**Depends On**: P24 Phase A (✅ shipped, see "Phase A landed" below)
**Blocks**: nothing critical; P08 prompt-cache work should respect pinning rules added here
**Source Plan**: [`docs/plan/P24-context-compaction.md`](../plan/P24-context-compaction.md)
**Supersedes**: P04 conversation summarization (the long-session part is now Phase D below)

## Why this exists

P24 Phase A solved the catastrophic case — a single agent turn growing past the LLM's
context window and crashing with HTTP 400 — by trimming oldest tool-call pairs verbatim.
That stops the bleed but loses information silently. Phases B/C/D/E turn the crude trim
into actual compaction: collapse redundant calls, summarize big payloads with per-tool
hooks, summarize whole turns with an LLM, and respect provider-side prompt-cache markers.

A future session picking this up should be able to read THIS file, jump into the
Phase A code, and start on Phase B without re-reading P24's full spec — though the spec
remains the authoritative source of trade-offs and open questions.

## Phase A landed (reference for the new session)

Files to read first to understand the foundation:

| File | What it does |
|---|---|
| `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/advisors/TokenEstimator.java` | char-based estimator with NL/JSON weights + per-msg overhead; handles `ToolResponseMessage.getResponses()` and `AssistantMessage.getToolCalls()` payloads (these are NOT in `getText()`) |
| `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/advisors/ContextCompactionAdvisor.java` | the advisor — order `HIGHEST_PRECEDENCE + 1100`, runs after `MessageChatMemoryAdvisor`; resolves active model window from `ModelRepository.findByModelId(CTX_ROUTED_MODEL)`; drops oldest tool-call pairs verbatim |
| `kukuvaia-engine/kukuvaia-core/src/main/java/ai/kukuvaia/config/ChatClientConfig.java` | wires the advisor into both ChatClient beans |
| `kukuvaia-engine/kukuvaia-core/src/test/java/ai/kukuvaia/advisors/{TokenEstimatorTest,ContextCompactionAdvisorTest}.java` | the test fixtures — copy their patterns for new phases |

Config surface already in place (extend, don't replace):

```yaml
kukuvaia:
  context-compaction:
    enabled: true
    default-window-tokens: 200000
    safety-margin-tokens: 5000
    keep-last-turns: 2
```

Key invariants Phase A established that B/C/D/E must preserve:
- `SystemMessage` is never dropped (persona is the contract).
- The last user message is never dropped.
- The last `keep-last-turns` (default 2) tool-call pairs are never dropped.
- Tool-call pairs (assistant-with-tool_calls + paired `ToolResponseMessage`) are dropped
  as units — never leave a tool_use without its tool_result, or the provider crashes.
- The advisor emits a `TextBlock(style="warning")` via `SessionOutputSink` whenever it
  fires, so the operator sees what happened in the SSE stream.

---

## Phase B — Duplicate-drop + Retry-loop collapse

**Goal:** Recognise that the agent retried the same tool with the same (or near-same)
args and fold the failed attempts into one synthetic summary, keeping only the most
recent verbatim attempt. Catches the L1/L2 reject retry pattern from 2026-05-13 where
4 `create_rule` attempts with the same wrong UUID literal each took ~1 KB.

**Scope:**
- Detect duplicate tool calls — same tool name + identical `arguments` JSON ≥ 2 times.
  Keep only the latest verbatim; replace older ones with a pointer message:
  `"identical to message #N below — see there for the response"`.
- Detect retry loops — same tool name + similar args + ≥ 2 consecutive responses whose
  text starts with `Error calling tool:` or matches a known reject pattern. Collapse the
  N failures into one synthetic assistant message:
  `"agent attempted create_rule 4 times; rejection verdicts: ANTI_PATTERN, REJECT_PREREQUISITE_NEVER_MATCHED (×3 with histogram [Lesson=12, Page=8]). Most recent attempt's full args + response retained below."`
- "Similar args" definition: same tool name AND ≥ 80 % JSON-key overlap. JSON deep-equal
  is too strict (agent may flip one literal); Levenshtein on full string is too lax.
- Pair-detection still applies — drop both halves of a redundant call together.

**Acceptance:**
- [ ] Phase A's hard-cap drop is the last resort; Phase B runs FIRST when over threshold.
- [ ] A fixture with 4 identical `introspect_section_schema(template=X)` calls keeps
      only one verbatim response; the others become pointer messages.
- [ ] A fixture replaying the 2026-05-13 log (4× `create_rule` with same wrong UUID
      → 4× L2 reject) emits one collapsed message naming the verdict pattern.
- [ ] Diagnostic counts in the warning OutputBlock: `duplicatesFolded`, `retryLoopsCollapsed`.
- [ ] Existing Phase A tests still pass — order of strategies in the advisor must be
      "B first, then A as fallback."

**Don't:**
- Don't reorder Phase A's pin policy.
- Don't collapse loops that include non-error responses interleaved — that's a different
  pattern (oscillation), out of scope here.

---

## Phase C — Per-tool summary hooks

**Goal:** Replace the 50 KB verbatim `introspect_section_schema` response with a 1 KB
summary that preserves what the LLM needs to author rules (counts, top spec ids), so
older copies of large payloads stop bloating the prompt.

**Scope:**
- Introduce an opt-in mechanism for `@Tool` methods to register a `compactSummary`
  function `(originalArgs, originalResponse) -> String`. Default fallback when no
  summary registered: drop the body, keep tool name + arg keys + a size marker
  (`"... [4732-char response elided by P24-C]"`).
- Mechanism options (decide at implementation time):
  - A new annotation `@CompactSummary("summarizerBeanName")` on the @Tool method —
    Spring resolves the bean, invokes its `summarize(args, response)`.
  - Or a `ToolCompactionRegistry` keyed by tool name, populated at startup by tool-
    owning modules.
  - The simpler one wins; if no clear winner, registry. Tool-owning teams should not
    have to import Spring-AI internals to opt in.
- Implement summaries for the high-payload tools we hit in production:
  - `introspect_section_schema` → `"schema for template <id>: 47 sectionTypes, 23 boolean specs, 8 taxonomy specs"`. Drop the body — agent can re-call.
  - `get_outline_sections` → `"first <K> sections of outline <id>: titles=[t1, …, tK]; <N-K> more available via offset=<K>"`.
  - `start_rule_authoring` → similar shape, pin sample-id list as it's actively used.
  - `get_outline_snapshot` → reject up-front; this is megabytes and the persona prompt
    already tells agents not to call it for in-context retention. The summary message
    should reference that guidance.
- Compaction applies ONLY to old responses (older than `keep-last-turns`). The most
  recent response stays verbatim.

**Acceptance:**
- [ ] A fixture with 2× `introspect_section_schema` calls compacts the older response
      to a single-sentence summary; the recent one remains verbatim.
- [ ] If a tool has no registered summary, fallback message preserves tool name +
      arg keys, drops body.
- [ ] Summaries fit within a configurable per-tool cap (default 500 chars). Long
      summaries are truncated with `...`.
- [ ] Tool-side opt-in is documented in `docs/architecture/` or wherever the
      `@Tool`/MCP conventions live.

**Don't:**
- Don't summarise on EVERY turn — only when the advisor decides to compact. Summaries
  cost an LLM call's worth of code maintenance even if not LLM-driven.
- Don't summarise responses for tools the LLM is actively reasoning about. Use the
  `keep-last-turns` window as the protective fence.

---

## Phase D — LLM-driven long-session summarisation (replaces P04)

**Goal:** When in-turn strategies (Phases A+B+C) have done all they can and the prompt
is STILL over budget — typical on long-resumed sessions with hundreds of turns —
summarise the oldest user/assistant pairs into `conversations.summary` (the orphan
column already present in V2 migration) and prepend that summary on future turns.

**Scope:**
- Trigger: estimator still over `soft-threshold-fraction` of window after Phase A+B+C
  ran. Token-threshold-triggered, NOT cron-triggered. The P04 cron model is explicitly
  abandoned (see P24 spec rationale).
- Mechanism:
  - Take oldest N user/assistant pairs (outside `keep-last-turns`) plus the existing
    `summary` (may be empty on first run).
  - Call a CHEAP summarisation LLM (config: `kukuvaia.context-compaction.summarisation-llm`,
    default `haiku` or equivalent) with prompt:
    `"Given this prior summary and these N new turns, produce the updated summary.
     Preserve: decisions made, in-flight artefacts (rule ids, outline ids), constraints
     the operator stated, the user's overall goal. Drop: small talk, dead-ends, tool
     errors the agent recovered from."`
  - Persist updated summary to `conversations.summary`.
  - Replace the summarised pairs in the in-flight message list with one
    `SystemMessage` named "Conversation summary so far:" containing the summary.
- Resume: on session load, prepend the persisted summary as a `SystemMessage` at
  position 1 (after the persona/system prompt) so the LLM sees:
  persona → summary → recent history → user message.
- Monotonicity: summaries fold; never reset to empty. Each pass produces a strictly
  longer-or-equal summary that absorbs more history.

**Acceptance:**
- [ ] A simulated 200-turn session with rolling summarisation:
      - `conversations.summary` is non-empty after the first summarisation pass.
      - Each subsequent pass produces a summary that mentions facts from the previous one
        (monotonicity).
      - Resuming the session injects the summary as the second `SystemMessage`.
- [ ] Summarisation LLM is configurable; default doesn't hit the main interactive model.
- [ ] If the summarisation call fails, advisor falls through to Phase A's verbatim drop
      (degraded but not crashed).
- [ ] The orphan `conversations.summary` column gets a writer — schema doesn't change.

**Don't:**
- Don't fold the most recent turns into the summary (preserves debugging visibility).
- Don't trigger summarisation on every turn — only when over the soft threshold AND
  Phases A+B+C didn't free enough budget.
- Don't conflate this with `kukuvaia-memory` (`MemoryConsolidationJob`) — different
  module, different concern (cross-session knowledge vs in-session continuity).

---

## Phase E — Provider-aware threshold + smarter pinning + prompt-cache awareness

**Goal:** Stop using a hardcoded default window. Pull each model's real window from
provider config dynamically. Let tools mark specific responses un-compactable. Coordinate
with P08 prompt-cache markers so compaction doesn't invalidate cache prefixes.

**Scope:**
- **Per-model window:** Phase A already resolves `contextWindow` from `ModelRecord` if
  `CTX_ROUTED_MODEL` is set. Make this robust: discover-time + warn when a registered
  model has `contextWindow == null`. Add admin endpoint or migration to backfill.
- **Tool-side pin metadata:** mechanism for a tool response to declare `pin=true` — e.g.
  `sample_sections_for_authoring` returns section UUIDs the rule-editor is actively
  using; dropping them mid-turn breaks the workflow. Implementation options:
  - Tool annotation `@PinResponse` on the @Tool method (whole-tool pin).
  - Per-response wrapper record with `boolean pinned` field (per-call pin).
  - Decide based on whether any tool legitimately wants per-call pinning.
- **Prompt-cache coordination (depends on P08):** Anthropic's prompt cache works by
  matching a stable prefix of the request. If compaction mutates the system prompt or
  persona block, the cache misses on every call — costly. Phase A pins those already;
  Phase E formalises the rule:
  - Compaction must NEVER mutate messages that are inside a prompt-cache marker range.
  - The advisor reads cache marker metadata from request options (P08 will define the
    shape) and treats marked ranges as pinned.

**Acceptance:**
- [ ] Removing the hardcoded `default-window-tokens` is feasible — every registered
      model has a real window or admin tooling forces one.
- [ ] A tool can opt in to pinning; pinned responses survive compaction even when older
      than `keep-last-turns`.
- [ ] If P08 is shipped, cache-marked ranges are treated as pinned. If P08 is not yet
      shipped, this phase still ships the pinning mechanism, with a TODO noting the P08
      coordination point.
- [ ] Telemetry: emit per-compaction event the strategies applied, byte savings, and
      whether any pinned-response barriers were encountered.

**Don't:**
- Don't let tools pin everything — pinning is the loophole and must be explicit.
- Don't break Phase A's behaviour when `contextWindow` is unset; keep the default-window
  fallback for grace.

---

## Suggested execution order

1. **Phase B first** — highest leverage per line of code: the retry-loop case from
   2026-05-13 was real and large. Will produce visible improvement on the next
   rule-authoring session.
2. **Phase D second** — orphan column has been there since V2; persisting summaries
   unlocks session resume and removes the "context amnesia on long sessions" complaint
   that P04 was originally drafted for.
3. **Phase C third** — per-tool summaries are bigger work for less catastrophic gain;
   the registry/annotation choice deserves a small spike.
4. **Phase E last** — refinement layer. Useful after C+D have established what
   pinning needs to protect.

A single fresh session can probably ship Phase B + Phase D in two days each if focused.
Phase C is a week of design + per-tool implementation. Phase E is a few days once
P08's prompt-cache shape is known.

## Pointers for the next session

- The "200 K token overflow" log slice from 2026-05-13 is the canonical regression — if
  any phase passes its own tests but doesn't materially help that scenario, the design
  needs a rethink.
- The roadmap entry: `.maister/docs/project/roadmap.md` has P24 row with status
  "Draft" and supersedes P04. Update that row's status to "Phase B shipped" /
  "Phase D shipped" etc. as each lands.
- The persona prompt at `kukuvaia-engine/kukuvaia-core/src/main/resources/personas/rule-editor.yaml`
  has retry-bound language already (see "RETRY BOUND" section). Phase B should make
  the engine-side reality match the persona's expectation that retries will be collapsed.
- Open questions in the P24 spec (granularity, re-fetch policy, estimator calibration,
  summarisation LLM choice) are still open — read them BEFORE designing the phase, not
  after.
