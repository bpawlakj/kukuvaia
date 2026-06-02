# P18 — Intelligent Task Routing

**Status:** Draft — ready to implement (no dependencies)
**Created:** 2026-04-19
**Scope:** Replace keyword-based `ModelRoutingAdvisor` with feature-scored classification + optional LLM fallback.
**Relationship to other plans:** Not overlapping — P18 decides WHICH MODEL handles the whole turn at entry; P14 decides WHAT CONTEXT goes to sub-agents inside the turn.

## Problem

Current `ModelRoutingAdvisor.classify(msg, escalateFlag)`:

```java
if (escalateFlag || contains(ESCALATION_PHRASES)) → ESCALATE
if (length ≤ 30 && contains(GREETING_WORDS))      → FAST
else                                                 → DEFAULT
```

Fails on:

| Message | Current | Should be | Reason |
|---------|---------|-----------|--------|
| `"hej, zaplanuj wycieczkę na Kretę"` | FAST | DEFAULT | Greeting prefix ≠ trivial turn |
| `"streszcz mi ten 30-stronicowy dokument"` | DEFAULT | ESCALATE | Complex task, no keyword |
| `"hi"` (first turn) | FAST | DEFAULT | First turn = onboarding, supervisor should introduce |
| `"```python\nprint('x')\n```"` | DEFAULT | DEFAULT+ | Code context deserves larger model — undetected today |

## Goal

Route each user turn based on **features of the turn**, not string matching on whole message.

## Architecture — hybrid D (feature-scored → LLM fallback)

### Phase 1 (MVP, ships now) — Feature-scored classifier

Deterministic, zero-latency, no LLM cost. Replaces current `classify()`.

### Phase 2 (deferred) — LLM classifier fallback

When feature scores are ambiguous (top 2 within ε), call cheap classifier (nova-micro / gemma 4B) with 1-shot prompt. Cached per-session for ~5s.

## Phase 1 design — feature scoring

### Features extracted per turn

| Feature | Cost | Source |
|---------|------|--------|
| `length_chars` | O(1) | `message.length()` |
| `word_count` | O(n) | `split("\\s+")` |
| `has_greeting` | O(n) | `GREETING_WORDS` lookup (existing) |
| `has_complexity_marker` | O(n) | `ESCALATION_PHRASES` lookup (existing) |
| `has_question_mark` | O(n) | `message.contains("?")` |
| `has_code_block` | O(1) | `message.contains("```")` or backticks |
| `has_file_path` | O(n) | regex `/\S+\.(java\|py\|go\|ts\|md\|sql\|yaml\|json)/` |
| `has_planning_intent` | O(n) | whitelist: `plan`, `zaplanuj`, `organize`, `strategy`, `strategia`, `opracuj` |
| `has_analysis_intent` | O(n) | whitelist: `analyze`, `przeanalizuj`, `explain`, `wyjaśnij`, `compare`, `porównaj`, `summarize`, `streszcz` |
| `in_planning_mode` | O(1) | `PlanningModeService.isInPlanningMode(sessionId)` |
| `is_first_turn` | O(1) | session message count == 0 (via SessionContext) |
| `num_sentences` | O(n) | simple period/newline split |

### Scoring table

Each feature pushes scores for FAST / DEFAULT / ESCALATE:

| Feature | FAST | DEFAULT | ESCALATE |
|---------|------|---------|----------|
| `length ≤ 30` | **+3** | 0 | 0 |
| `length > 200` | −2 | +1 | **+2** |
| `word_count ≤ 3` | **+2** | 0 | 0 |
| `has_greeting` | **+2** | 0 | 0 |
| `has_complexity_marker` | −3 | 0 | **+5** |
| `has_question_mark` | −1 | **+1** | +1 |
| `has_code_block` | −3 | **+2** | +1 |
| `has_file_path` | −2 | **+2** | 0 |
| `has_planning_intent` | −3 | **+3** | +1 |
| `has_analysis_intent` | −2 | +1 | **+3** |
| `in_planning_mode` | −5 | **+3** | +1 |
| `is_first_turn` | −2 | **+2** | 0 |
| `num_sentences ≥ 3` | −1 | +1 | **+2** |

Base scores: FAST=0, DEFAULT=1 (slight default bias), ESCALATE=0. Highest wins. Ties → DEFAULT (safe).

### Hard overrides (bypass scoring)

- `kukuvaia.escalate=true` in context → ESCALATE (user explicit)
- Empty/blank message → DEFAULT (no classification possible)

## Integration

Replace `ModelRoutingAdvisor.classify()` body with `TaskClassifier.classify(request)` call. `TaskClassifier` is a new `@Component`.

```java
public record ClassificationResult(
    RoutingDecision decision,
    Map<String, Integer> scores,    // for logging/debugging
    String reason                    // top feature that tipped the scale
) {}
```

Keep existing `lastDecision()` / `lastRoutedModel()` ThreadLocals — CLI observability unchanged.

## Observability

- Prometheus counter `kukuvaia.routing.decision{tier, reason}` — see distribution
- `INFO` log line per non-DEFAULT decision: `Routing: FAST (reason=short_greeting, length=4, scores={FAST:5, DEFAULT:1, ESCALATE:0})`
- Add to `role:supervisor` span attributes: `kukuvaia.routing.scores` (already has `kukuvaia.routing.decision`)

## Acceptance criteria

- [ ] `TaskClassifier` unit tests: 10 concrete messages with expected decisions pass
- [ ] `"hej, zaplanuj wycieczkę na Kretę"` → DEFAULT (was FAST)
- [ ] `"streszcz ten dokument..."` → ESCALATE (was DEFAULT) when analysis-intent is present
- [ ] `"hej"` → FAST (unchanged)
- [ ] `"pomyśl dobrze jak zaplanować X"` → ESCALATE (unchanged, keyword override still works)
- [ ] Planning mode active + any message → DEFAULT (force non-FAST)
- [ ] First-turn messages → never FAST (onboarding bias)
- [ ] Observability: counter increments, scores visible in log/span

## Non-goals

- LLM-based classifier fallback (deferred to Phase 2)
- Learning from feedback (route correction based on outcome) — would need `P19: feedback-trained routing`
- Per-user preferences — everyone uses same model today

## Effort

| Task | Traditional | AI-paired |
|------|-------------|-----------|
| `TaskClassifier` class + feature extractors | 0.5 day | 30 min |
| Integrate into `ModelRoutingAdvisor` | 0.25 day | 15 min |
| Unit tests (10 scenarios) | 0.5 day | 20 min |
| Observability + logging | 0.25 day | 10 min |
| **Total** | **~1.5 days** | **~75 min** |

## Dependencies

None. Builds on operational `ModelRoutingAdvisor`. `PlanningModeService.isInPlanningMode()` and `SessionContextAdvisor`'s message count are existing public methods.

## Phase 2 deferred

LLM classifier fallback: when scores `topA - topB < 2`, call `nova-micro` with 1-shot prompt, cache per message-hash for 5 minutes. Skipped for MVP — deterministic features cover 90%+ of cases. Added when real user traffic shows ambiguous routing.

## Relationship to other plans

- **P14 (tiered context)** — orthogonal. P14 decides intra-turn context budget; P18 decides pre-turn model. Integration diagram in P14 §"Integration with sibling mechanisms" shows both.
- **P08 (prompt caching)** — P18 classification runs BEFORE cache lookup. A stable classifier means stable cache prefix → better cache hit rate.
- **P16 (stepped reasoning)** — P18 picks model tier; P16 reasons about what steps happen inside that model's response.
