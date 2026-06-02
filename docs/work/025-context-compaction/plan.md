# P24: Context Compaction — Token-Threshold-Triggered, Unified

> **Status:** Draft. Supersedes P04 (Conversation Summarization). The original P04
> trigger model (message-count or cron) does not cover the failure mode this plan
> addresses — a single 8-turn agent session can blow past a 200 K token window long
> before the 100-message default window fills.

## Problem

kukuvaia-engine sends the FULL conversation history to the LLM on every turn (modulo
`MessageWindowChatMemory`'s coarse last-N-messages cap, default 100). Each LLM tool
call appends two items to that history: the tool-call request AND the tool-call
response. Some responses are very large — `introspect_section_schema` for one
validation template can be 40–60 KB of JSON; `get_outline_snapshot` is megabytes.
After a few iterations of authoring (introspect → propose JsonLogic → L1 reject →
retry → L2 reject → retry → introspect again → …), the prompt exceeds the provider's
context window and the entire turn fails.

This is not a theoretical worry — we tripped it during L2 testing on 2026-05-13:

    17:18:57.570 L1 reject (anti-pattern, ~1 KB)
    17:19:03.251 L2 reject (matched 0 of 30, ~1 KB)
    17:19:11.700 introspect_section_schema retry (~50 KB)
    17:19:33.541 L2 reject (~1 KB)
    17:19:38.632 get_section_context → not found (~0.5 KB)
    17:19:50.094 dry_run_rule → not found (~0.5 KB)
    17:19:58.092 L2 reject (~1 KB)
    17:20:15.080 AnthropicTransportException: HTTP 400
                 — "prompt is too long: 200360 tokens > 200000 maximum"

The 200 K cap was hit after 8 turns and ~85 seconds. The `MessageWindowChatMemory`
window was nowhere near its 100-message limit — the size came from tool responses,
not message count. The final failed turn lost the entire session.

## Current State (what's actually shipped 2026-05-13)

| Component | What it does today | Gap |
|---|---|---|
| `MessageWindowChatMemory` (kukuvaia-memory) | Keeps last 100 messages verbatim; older messages dropped silently | No summary persisted; rapid growth via tool payloads ignored |
| `TokenBudgetAdvisor` (kukuvaia-core) | Tracks tokens per session; at 80% of **hardcoded 50 000** budget injects a WARN system message | Hardcoded budget unrelated to active LLM's window; WARN *adds* tokens; never actually compacts |
| `MemoryConsolidationJob` (kukuvaia-memory) | Cron `0 0 3 * * *` — consolidates cross-session memory facts | Solves a different problem (long-term memory), not in-session size |
| `conversations.summary` column (V2 migration) | Exists in schema | No service writes or reads it — orphan |
| P04 plan (Draft) | Proposed message-count + cron trigger for conversation summaries | Trigger model doesn't catch rapid in-session growth; merged into THIS plan |

There is no advisor today whose job is "if the prompt is too big, shrink it." The
provider returns HTTP 400 and `AgentService.runChat` propagates the exception. The
session survives (the failed turn is not persisted) but the user loses the work-in-
progress reasoning.

## Goal

ONE token-threshold-triggered advisor that keeps every outbound LLM request below the
**active model's** context window minus a safety margin, by compacting both:

1. **Tool responses in the current turn's history** (the regression P24 was originally
   scoped to). Large responses older than the most-recent K turns are summarised or
   collapsed; retry loops are folded into one synthetic message.
2. **Older conversation turns spanning previous sessions** (what P04 was scoped to).
   When all in-turn compaction has been exhausted and the budget is still over, fold
   the oldest user/assistant pairs into a running summary persisted to
   `conversations.summary`. On session resume, the summary is prepended verbatim so
   the LLM has continuity without the full transcript.

Both behaviours share the SAME trigger (estimated token count > threshold) and the
SAME advisor — they are graduated responses, not separate systems.

Non-goals:
- Reducing the size of tool RESPONSES at the source (per-tool pagination/field-
  whitelisting work; tracked per tool).
- Reducing the system prompt or persona definitions (those are intentionally pinned).
- Compacting `kukuvaia-memory` cross-session knowledge (that's
  `MemoryConsolidationJob`'s job and lives in a different layer).

## Proposed Approach

### Replace `TokenBudgetAdvisor` with `ContextCompactionAdvisor`

Same advisor-chain slot, larger responsibility. Mounted AFTER `MessageChatMemoryAdvisor`
(so we see the assembled history) and BEFORE the provider transport (so we can mutate
before send).

On every `before(request)`:

1. **Estimate token count** of the assembled prompt (system + persona + history + user
   message + tool descriptors).
2. **Resolve the active model's context window** from provider config (NOT hardcoded):
   - Opus 4.7 → 200 K
   - Opus 4.7 1M-context → 1 M
   - Sonnet 4.6 → 200 K
   - GPT-4o → 128 K
3. **Compute soft and hard thresholds:**
   - `soft = window * 0.75` (default, configurable)
   - `hard = window - safety_margin_tokens` (default 5000)
4. **If estimate < soft:** pass through unchanged.
5. **If soft ≤ estimate < hard:** run graduated compaction (next section) until under
   `soft` again.
6. **If estimate ≥ hard:** apply the most aggressive compaction; if still over, drop
   oldest non-pinned items as a last resort and emit a user-facing
   `OutputBlock` warning ("Context was compacted — older tool results summarised").

### Graduated compaction strategies (in order)

| Step | Strategy | Trigger |
|---|---|---|
| 1 | Drop duplicate tool calls — `introspect_section_schema(X)` called multiple times with the same arg → keep only the latest verbatim, replace earlier ones with `"identical response — see message #N"` | Always cheap; runs first |
| 2 | Collapse retry loops — N ≥ 2 consecutive failed tool calls with the same name and similar args collapse into one synthetic message: *"agent attempted `create_rule` 4 times; rejection verdicts: ANTI_PATTERN, REJECT_PREREQUISITE_NEVER_MATCHED (×3 with histogram [Lesson=12, Page=8, Exercise=5]). Most recent attempt's full args + response retained below."* | Catches the L1/L2 retry pattern from 2026-05-13 |
| 3 | Per-tool `compactSummary` hook — for high-payload tools (`introspect_section_schema`, `get_outline_sections`, `start_rule_authoring`) replace verbatim response with a registered summary function output | Each tool opts in; default fallback drops body and keeps a size marker |
| 4 | Summarise oldest user/assistant turns into the conversation's `summary` field — only when steps 1-3 didn't free enough budget. Uses the LLM (one summarisation call). Resulting summary persisted to `conversations.summary` and prepended to future turns | Replaces the original P04 mechanism; trigger now token-based, not cron-based |
| 5 | Drop oldest non-pinned items verbatim | Last resort under `hard` threshold |

### Pinned items (never compacted)

- System prompt + persona block.
- Current user message.
- Most recent tool-call request + response (LLM needs to see what it just did).
- Anything tool-side tagged `pin=true` (future: a tool can mark its response un-
  compactable, e.g. sample UUIDs the LLM is actively using).
- The `summary` field from `conversations.summary` once present.

### Persistence

After a step-4 summarisation, write to `conversations.summary`:

```sql
UPDATE kukuvaia.conversations
SET summary = ?, message_count = ?, token_count = ?, updated_at = NOW()
WHERE session_id = ?;
```

`summary` is monotonic — each summarisation pass FOLDS more old turns into the
existing summary (LLM call: "given this prior summary and these N new turns, produce
the updated summary"). Never reset to empty; never overwrite without folding.

On session resume, `ChatMemory` reads `summary` once and prepends it as a system-
role message AT POSITION 1 (after the system prompt, before any history). The LLM
sees: persona → summary → recent history → user message.

### Configuration

```yaml
kukuvaia:
  context-compaction:
    enabled: true
    soft-threshold-fraction: 0.75      # of active model's window
    safety-margin-tokens: 5000          # subtracted from window for hard cap
    keep-last-turns: 2                  # never compact within this many recent turns
    per-tool-summary-enabled: true
    summarisation-llm: copilot-haiku    # cheap model for the step-4 summary call
    log-on-compaction: true
```

### Audit + observability

On every compaction event, emit a span with:
- `original_token_estimate`
- `compacted_token_estimate`
- `strategies_applied: [duplicate-drop, retry-collapse, ...]`
- `bytes_saved_per_strategy`
- `summarisation_llm_call: bool`

So we can measure whether compaction is working AND tune thresholds without guessing.

## Phases

### Phase A — Estimator + Hard Cap (smallest useful slice)

1. `TokenEstimator` service: char-based estimate with per-message-type weights.
2. `ContextCompactionAdvisor` mounted in the chain, **replacing** `TokenBudgetAdvisor`:
   - Reads active model's window from provider registry (not hardcoded).
   - When `estimate > hard cap`, drops oldest non-pinned tool responses until under.
3. Emit `OutputBlock` warning to the user.
4. Tests: synthetic conversation with N×30 KB tool responses; assert advisor trims.

Ship this first. Even without smart compaction, this prevents the catastrophic
HTTP 400 outcome — the worst case is "agent forgets the schema it called 4 turns ago"
instead of "the whole turn fails." This single slice would have saved the 2026-05-13
session.

### Phase B — Duplicate + Retry Collapse

5. Detect duplicate tool calls with identical args; replace older verbatim responses
   with pointers.
6. Detect retry loops (same tool, similar args, ≥ 2 failed responses in a row);
   collapse into a synthetic summary message.

### Phase C — Per-Tool Summary Hooks

7. `compactSummary` annotation/registration on tool definitions.
8. Implement summaries for the high-payload tools we hit during testing:
   `introspect_section_schema`, `get_outline_sections`, `start_rule_authoring`,
   `get_outline_snapshot`.

### Phase D — Long-Session Summarisation (formerly P04)

9. Step-4 LLM summarisation call when steps 1-3 don't free enough budget.
10. `ConversationSummaryRepository` reads/writes `conversations.summary`.
11. `ChatMemory` integration: prepend summary on session resume.
12. Tests: simulate a 200-turn session, assert summary is monotonic, assert resume
    works.

### Phase E — Provider-Aware Threshold + Smarter Pinning

13. Per-model window lookup from provider config (Opus / Sonnet / GPT / SmartGate).
14. Tool-side `pin=true` metadata so a tool can mark its response un-compactable.
15. Cache-prefix awareness — never compact tokens covered by Anthropic prompt-cache
    markers (P08 dependency).

## Open Questions

1. **Compaction granularity.** Per-message or per-block within a message? Tool
   responses are often single JSON blobs; compacting them in place vs replacing the
   whole message preserves the conversation's structural shape differently.
2. **Re-fetch policy.** If the LLM asks for previously-compacted info, do we
   auto-re-fetch and re-inject, or rely on it to re-call the tool? Auto-re-inject
   defeats the purpose; explicit re-call costs a round trip but is honest. Probably
   explicit re-call.
3. **Token estimator accuracy.** Char-based is fast but rough. Calibrate against
   real Anthropic/OpenAI token counts on a fixture corpus before relying on it for
   tight hard caps.
4. **Summarisation LLM choice.** Haiku is cheap but the summary must preserve facts
   the agent will need later. Trade-off: cheap-but-lossy vs slow-but-thorough.
   Probably start with the same provider as the main turn, switch if costs hurt.

## Out of Scope

- **Per-tool response size limits at the source.** Some tools genuinely return large
  payloads. Pagination/field-whitelisting is per-tool work, not blocked by this plan.
- **Cross-session memory compaction.** `MemoryConsolidationJob` handles that —
  different module, different concern.
- **`kukuvaia-memory` semantic search compaction.** Vector store has its own
  retention story.

## Acceptance Criteria

- The 2026-05-13 rule-authoring scenario (8–10 internal turns including 2 introspects,
  multiple L1/L2 rejects, 200 K-token Claude window) completes without HTTP 400.
- A 200-turn session with rolling summarisation resumes correctly: `conversations.summary`
  is monotonic, no information loss for facts referenced in the most recent 50 turns.
- Compaction events visible in observability with before/after token estimates.
- An unchanged session that previously fit in the window does NOT trigger compaction
  (no false positives).
- `TokenBudgetAdvisor` is removed; `ContextCompactionAdvisor` takes its slot.
- Standards compliance: respects `kukuvaia.context-compaction.*` config; constructor
  injection; follows advisor-chain pattern in `.maister/docs/standards/backend/architecture.md`.

## Related Work

- **P04 Conversation Summarization** — **superseded by P24.** P04 proposed a
  cron/message-count trigger; P24 generalises to a token-threshold trigger that covers
  both P04's original case AND the intra-turn rapid-growth case.
- `P08 Prompt Cache Optimization` — Anthropic prompt-cache markers depend on stable
  prefix segments. Compaction breaks cache prefixes if applied naively to the system
  prompt or persona block; P24 explicitly pins those.
- `TokenBudgetAdvisor` (shipped) — being replaced by `ContextCompactionAdvisor`.
  Its session-token-counting infrastructure is reused; its WARN-only behaviour is
  dropped.
- `rule-correctness-guards.md` — L1/L2 rejects are one source of the retry loops
  Phase B needs to collapse.
