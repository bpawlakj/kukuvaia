# T16: Context Compaction — P24 closed (A–E all shipped, P08 hook pending)

**Status**: `done` (all five phases shipped; A 2026-05-15, B 2026-05-18, C 2026-05-18, D 2026-05-18, E 2026-05-18)
**Tier**: 3 — Intelligence
**Depends On**: P24 Phases A + B + C + D + E (✅ all shipped)
**Blocks**: nothing; the Phase E pinning mechanism is the integration point for P08 prompt-cache markers when that ships
**Source Plan**: [`docs/work/025-context-compaction/plan.md`](./plan.md)
**Supersedes**: P04 conversation summarization (long-session part landed as Phase D)

## Why this exists

P24 was a five-phase token-threshold context compaction effort to stop LLM requests
from overflowing the context window. All five phases shipped on 2026-05-18 (Phase A
shipped earlier, 2026-05-15).

This file is now a reference doc for the design as built. The only follow-up is the
P08 prompt-cache marker reader — when P08 ships, this advisor should treat
cache-marked ranges as pinned. The pinning mechanism (Phase E) is already in place.

## P24 as built (reference)

Files to read to understand the foundation:

| File | What it does |
|---|---|
| `advisors/TokenEstimator.java` | char-based estimator with NL/JSON weights + per-msg overhead; handles `ToolResponseMessage.getResponses()` and `AssistantMessage.getToolCalls()` payloads (these are NOT in `getText()`) |
| `advisors/ContextCompactionAdvisor.java` | the advisor — order `HIGHEST_PRECEDENCE + 1100`, runs after `MessageChatMemoryAdvisor`. Resolves active model window from `ModelRepository.findByModelId(CTX_ROUTED_MODEL)` (warns once per model when missing). Runs full strategy ladder: B(retry collapse) → B(duplicate fold) → C(per-tool summary) → A(hard-cap drop) → D(LLM summarisation). Phase A and Phase C both honour `ToolCompactionRegistry.isPinned(toolName)` |
| `advisors/CompactionStrategies.java` | Pure static strategies for Phases B + C. Jackson `convertValue(node, Object.class)` + `ORDER_MAP_ENTRIES_BY_KEYS` canonicalises args for duplicate detection. Verdict regex matches `REJECT_*` / `ANTI_PATTERN` / `InvariantViolationException` / `Error calling tool`. Phase C compacts each `ToolResponseMessage.ToolResponse` individually — skips registry-pinned tools, applies registered `ToolCompactSummary` if available, else default elision marker for bodies ≥ 200 chars |
| `advisors/ToolCompactionRegistry.java` | Component holding `toolName → ToolCompactSummary` map + `pinnedTools` set. Tool-owning modules register via `@PostConstruct` |
| `advisors/ToolCompactSummary.java` | Functional interface: `(argsJson, responseData) → String`. Implementations must be pure + crash-safe (return null on parse failure → caller falls back to elision) |
| `advisors/ToolSummariesConfiguration.java` | Built-in registrations for canonical MCP bloat sources: `introspect_section_schema`, `get_outline_sections`, `get_outline_snapshot` |
| `advisors/ConversationSummariser.java` | Phase D summariser — calls the cheap `worker`-role LLM via `ChatModelCache` with a 10s timeout. Returns `Optional.empty()` on any failure |
| `advisors/ConversationSummaryAdvisor.java` | Phase D resume-side — order `HIGHEST_PRECEDENCE + 1050`. Reads persisted summary and injects a `SystemMessage("Conversation summary so far:\n...")` after the persona |
| `kukuvaia-memory/.../ConversationSummaryRepository.java` | JDBC reader/writer for `kukuvaia.conversations.summary`. Upserts via `ON CONFLICT (session_id) DO UPDATE` |
| `config/ChatClientConfig.java` | wires `ConversationSummaryAdvisor` + `ContextCompactionAdvisor` into both ChatClient beans |
| Tests | `{TokenEstimator, ContextCompactionAdvisor, CompactionStrategies, ConversationSummariser, ConversationSummaryAdvisor, ConversationSummaryRepository, ToolCompactionRegistry, ToolSummariesConfiguration}Test` |

Full config surface (P24 closed):

```yaml
kukuvaia:
  context-compaction:
    enabled: true
    default-window-tokens: 200000
    safety-margin-tokens: 5000
    keep-last-turns: 2                       # Phase A + Phase C keep window
    # Phase C
    tool-summaries-enabled: true
    tool-summaries-default-elision: true     # elide bodies of unregistered tools' old responses
    # Phase D
    summarisation-enabled: true
    summarisation-role: worker               # ChatModelCache role for the summariser
    summarisation-timeout-seconds: 10
    summarisation-max-input-chars: 30000     # cap on per-pass summariser input
    summarisation-keep-last-turns: 4         # last K user messages preserved verbatim
```

Key invariants Phases A + B established that C/D/E must preserve:
- `SystemMessage` is never dropped (persona is the contract).
- The last user message is never dropped.
- The last `keep-last-turns` (default 2) tool-call pairs are never dropped by Phase A's
  hard-cap pass. (Phase B's fold/collapse may transform older copies/attempts within
  the recent window, but the most recent verbatim copy/attempt is always retained.)
- Tool-call pairs (assistant-with-tool_calls + paired `ToolResponseMessage`) are dropped
  as units — never leave a tool_use without its tool_result, or the provider crashes.
- Phase B replaces folded ranges with a single `SystemMessage` note (canonical pointer or
  synthetic retry summary). Multi-tool-call assistant messages are skipped by duplicate
  folding (too risky to flatten distinct tool intents).
- The advisor emits a `TextBlock(style="warning")` via `SessionOutputSink` whenever it
  fires, with per-strategy counts (`retryLoopsCollapsed`, `duplicatesFolded`,
  `messagesDropped`) so the operator sees exactly what happened in the SSE stream.

---

## Phase B — Duplicate-drop + Retry-loop collapse [✅ SHIPPED 2026-05-18]

**Goal:** Recognise that the agent retried the same tool with the same (or near-same)
args and fold the failed attempts into one synthetic summary, keeping only the most
recent verbatim attempt. Catches the L1/L2 reject retry pattern from 2026-05-13 where
4 `create_rule` attempts with the same wrong UUID literal each took ~1 KB.

**Landed:** `CompactionStrategies.java` (pure static helpers) +
`ContextCompactionAdvisor.compact()` runs strategies in order:
`collapseRetryLoops` → `foldDuplicateToolCalls` → `hardCapDropOldestPairs`.

Implementation notes carried forward (do not regress in later phases):
- "Similar args" is JSON-top-level-key overlap ≥ 0.80 (Jaccard). Same tool name required.
- Canonical args use Jackson `convertValue(node, Object.class)` to a `Map` then serialise
  with `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` (ObjectNode's direct
  `writeValueAsString` does NOT honour that feature).
- Pair invariant preserved: replacements are whole-pair, with a single `SystemMessage`
  note inserted at the dropped range.
- Multi-tool-call assistant messages are skipped by duplicate folding (`toolName == null`
  sentinel) — too risky to flatten distinct tool intents.
- Retry collapse runs BEFORE duplicate fold so verdict info is captured before identical-arg
  pairs would otherwise be flattened to plain pointer notes.
- Run is required to be CONSECUTIVE in the message list — any intervening
  `UserMessage`/`SystemMessage` breaks the run (oscillation patterns are out of scope).

**Acceptance results:**
- [x] Phase A's hard-cap drop is the last resort; Phase B runs FIRST when over threshold.
- [x] A fixture with 4 identical `introspect_section_schema(template=X)` calls keeps
      only one verbatim response; the others become pointer messages.
- [x] A fixture replaying the 2026-05-13 log emits one collapsed message naming the
      verdict pattern (`REJECT_PREREQUISITE_NEVER_MATCHED`, `InvariantViolationException`).
- [x] Diagnostic counts in the warning OutputBlock: `duplicatesFolded`,
      `retryLoopsCollapsed`, `messagesDropped`.
- [x] All Phase A tests still pass (fixtures updated to use distinct args where they
      previously shared `"{}"` so the hard-cap drop path remains exercised).

**Test coverage:** 10 unit tests on `CompactionStrategiesTest` (5 fold + 5 collapse) +
3 new integration tests on `ContextCompactionAdvisorTest` (dup-fold path, retry-collapse
path, Phase B+A combined).

---

## Phase C — Per-tool summary hooks [✅ SHIPPED 2026-05-18]

**Goal:** Replace the 50 KB verbatim `introspect_section_schema` response with a 1 KB
summary that preserves what the LLM needs to author rules (counts, top spec ids), so
older copies of large payloads stop bloating the prompt.

**Landed:** `ToolCompactSummary` functional interface + `ToolCompactionRegistry` Spring
component (registry chosen over annotation — simpler, no reflection, tool-owning teams
register at startup). `CompactionStrategies.summariseOldToolResponses` runs between
Phase B and Phase A. `ToolSummariesConfiguration` registers built-ins on startup.

Implementation notes carried forward:
- Compaction applies ONLY to responses older than `keep-last-turns` (default 2).
- Per-response not per-pair: each `ToolResponseMessage.ToolResponse` is handled
  individually, so a multi-call assistant message still gets per-call treatment.
- Registered summary returns null → default elision marker (configurable via
  `tool-summaries-default-elision`; on by default).
- Registered summary throws → swallowed, falls back to elision.
- Summary text capped at 500 chars; longer outputs are truncated with `...`.
- Bodies shorter than 200 chars (`MIN_ELIDE_CHARS`) are NOT elided — the marker itself
  would be comparable in size.
- Built-in summaries are best-effort: they parse the JSON tree defensively, returning
  null on any parse failure so the strategy falls through to elision.

**Acceptance results:**
- [x] A fixture with 3× `introspect`-style calls compacts the older two responses,
      most recent stays verbatim (`registeredSummary_appliedToOlder_keepsLatestVerbatim`).
- [x] No registered summary + default elision on → fallback marker carries tool name +
      arg keys + size (`noSummary_elisionOn_elidesOld`).
- [x] Summary text capped at 500 chars (`overLongSummary_truncated`).
- [x] Registry registration documented in {@code ToolCompactionRegistry} javadoc + the
      `ToolSummariesConfiguration` exemplar.

**Test coverage:** 4 registry tests + 7 strategy tests + 5 built-in summary tests +
1 integration test on `ContextCompactionAdvisor` = 17 new tests.

---

## Phase D — LLM-driven long-session summarisation (replaces P04) [✅ SHIPPED 2026-05-18]

**Goal:** When in-turn strategies (Phases A+B+C) have done all they can and the prompt
is STILL over budget — typical on long-resumed sessions with hundreds of turns —
summarise the oldest user/assistant pairs into `conversations.summary` (the orphan
column already present in V2 migration) and prepend that summary on future turns.

**Landed:** `ConversationSummariser` (worker-tier LLM call, 10s timeout, graceful
fallback) + `ConversationSummaryRepository` (upsert on `kukuvaia.conversations`) +
`ConversationSummaryAdvisor` (resume-side injection at order 1050) + Phase D step in
`ContextCompactionAdvisor.compact()` after the Phase A hard-cap drop.

Implementation notes carried forward (do not regress in remaining phases):
- Order matters: B → A → D. Phase D is a FALLBACK after A — it catches narrative bloat
  (user/assistant text without tool calls) that A cannot touch.
- Phase D pins SystemMessages and the last `summarisation-keep-last-turns` user
  messages (plus everything after the last kept user message). Default is 4.
- The replaced range becomes ONE `SystemMessage` with prefix
  `"Conversation summary so far:\n"` — also used by the resume advisor to detect
  double-injection.
- Monotonicity: previous summary is read from DB and fed back into the next pass
  as part of the worker prompt. The cheap LLM is instructed to ABSORB, not reset.
- DB write is best-effort: if upsert fails, Phase D still applies the in-memory
  summary so the current turn fits — accepts that the next resume won't see it.
- Counter in `CompactionOutcome` + warning OutputBlock: `messagesSummarised`.

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

**Acceptance results:**
- [x] Rolling summarisation: `phaseD_summarisesOldestTurns` covers the first-pass case;
      `phaseD_monotonicityPreservedAcrossPasses` covers feed-forward of the prior summary;
      `ConversationSummaryAdvisorTest.summaryPresent_injectedAfterPersona` covers the
      resume-side injection at position 1.
- [x] Summarisation LLM is configurable (`summarisation-role`, default `worker`) and
      defaults to NOT the main interactive model.
- [x] LLM failure → `Optional.empty()` → no DB write, no message mutation
      (`phaseD_summariserFailure_doesNotPersist`).
- [x] Orphan column is now written via `ConversationSummaryRepository.upsertSummary`.

**Test coverage:** 6 repository tests + 7 summariser tests + 6 resume-advisor tests +
5 integration tests on `ContextCompactionAdvisor` = 24 new tests, all green.

**Did not:**
- Don't fold the most recent turns into the summary (debugging visibility preserved).
- Don't trigger summarisation on every turn — only on overflow after Phases A+B.
- Don't conflate with `kukuvaia-memory` (`MemoryConsolidationJob`) — different table,
  different concern.

---

## Phase E — Provider-aware threshold + smarter pinning + prompt-cache awareness [✅ SHIPPED 2026-05-18]

**Goal:** Stop using a hardcoded default window. Pull each model's real window from
provider config dynamically. Let tools mark specific responses un-compactable. Coordinate
with P08 prompt-cache markers so compaction doesn't invalidate cache prefixes.

**Landed:** `ToolCompactionRegistry.pin(toolName)` API (extended from the Phase C
registry — same component, no new beans). `ContextCompactionAdvisor.resolveHardCap`
logs a one-time-per-model warning when the active model has no `contextWindow` in the
registry (still falls back to `defaultWindowTokens`, but the operator sees the gap).
`hardCapDropOldestPairs` and `summariseOldToolResponses` both honour `isPinned(toolName)`.
P08 prompt-cache marker reader is the only piece NOT in this drop — flagged as a TODO
in the advisor javadoc; pinning mechanism is in place for when P08 ships.

Implementation notes:
- Pinning is whole-tool, not per-call. Tool-owning teams register pin via
  `registry.pin("tool_name")` at startup; idempotent.
- Phase A skips the pair if ANY tool_call in the AssistantMessage targets a pinned tool.
- Phase C skips the specific `ToolResponse` whose `name()` is pinned, leaving siblings
  in the same `ToolResponseMessage` free to be summarised.
- Warn-on-missing-window is rate-limited via a `Set<String>` per process — clears at
  restart, which is the operator-feedback loop anyway.

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

**Acceptance results:**
- [x] Per-model window: `resolveHardCap` warns once per misconfigured model (rate-limited).
      Hard removal of the default is left for the future — the fallback is graceful.
- [x] Tool pin opt-in via `ToolCompactionRegistry.pin`; pinned tools survive Phase A drops
      AND Phase C summaries (`phaseE_pinSurvivesPhaseA`, `phaseE_pinSurvivesPhaseC`).
- [ ] **TODO (P08 coordination):** prompt-cache marker reader. The pinning mechanism is in
      place; the reader is the remaining one-day piece. Noted in
      `ContextCompactionAdvisor` javadoc.
- Telemetry hookup (byte savings + per-strategy event) is deferred — current logging
  + warning OutputBlock cover the operator-visible signal.

**Test coverage:** 3 new registry tests (pin/unpin/idempotent) + 2 integration tests on
`ContextCompactionAdvisor` (Phase A skip-pinned, Phase C skip-pinned).

**Did not:**
- Don't let tools pin everything — pinning is the loophole and must be explicit.
- Don't break Phase A's behaviour when `contextWindow` is unset; default-window fallback
  retained for grace.

---

## Follow-up

The only remaining work is the **P08 prompt-cache marker reader**. When P08 ships:
1. Decide where cache-marker metadata lives on the request (P08 should define).
2. In `ContextCompactionAdvisor.compact()`, treat marked message ranges as pinned —
   refuse to mutate them, even for Phase B's folds.
3. Add a fixture test mirroring `phaseE_pinSurvivesPhaseA` but using a cache marker.

Everything else in P24 is closed.

## Pointers for the next session

- The "200 K token overflow" log slice from 2026-05-13 is the canonical regression — if
  any phase passes its own tests but doesn't materially help that scenario, the design
  needs a rethink. (Phase B alone now collapses the 4× create_rule retry into one
  synthetic note + one verbatim attempt; verify in production logs after next deploy.)
- The roadmap entry: `.maister/docs/project/roadmap.md` has P24 row with status
  "Phases A + B shipped". Update that row's status to "Phase D shipped" / "Phase C
  shipped" etc. as each lands.
- The persona prompt at `kukuvaia-engine/kukuvaia-core/src/main/resources/personas/rule-editor.yaml`
  has retry-bound language already (see "RETRY BOUND" section). Phase B now makes the
  engine-side reality match the persona's expectation — engine collapses retry runs
  even if the agent fails to honour the retry cap.
- Open questions in the P24 spec (granularity, re-fetch policy, estimator calibration,
  summarisation LLM choice) are still open — read them BEFORE designing Phase D, not
  after.
