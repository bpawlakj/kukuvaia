# P19 — Complexity-Driven Routing (Unify Chat + Embabel)

**Status:** Draft — ready to implement
**Created:** 2026-04-19
**Scope:** Remove hardcoded keyword dictionaries from `TaskClassifier` + `IntentDetectionAdvisor`. Route chat turns through the same `TaskComplexity` → role → model pipeline that Embabel actions already use. Hybrid approach: structural signals (deterministic, cheap) as fast path + LLM micro-classifier fallback for ambiguous turns.

## Problem

Two parallel routing systems exist today, solving the same "pick the right model tier" problem with different mechanisms:

**System A — user chat messages (hardcoded):**
- `TaskClassifier.classify(message, ...)` uses `Set.of("think harder", "pomyśl głębiej", …)` — hardcoded escalation phrases
- `IntentDetectionAdvisor.classify(message)` — second parallel classifier with its own `Set.of("analyze", "compare", …)`
- Both are compiled into the jar. Adding a new signal requires rebuild + redeploy.
- Keyword coverage is brittle (see P18 — "streszcz ten dokument" should escalate but has no marker).

**System B — Embabel actions (DB-backed, clean):**
- `TaskComplexity` enum (`EXTRACTION`, `TRANSFORMATION`, `CLASSIFICATION`, `RETRIEVAL`, `ANALYSIS`, `GENERATION`, `SYNTHESIS`, `STRATEGY`, `EVALUATION`)
- `kukuvaia.complexity_mappings` table (V7) — editable via `ModelController` API
- `ComplexityMappingService.resolveRole(TaskComplexity)` — cached DB lookup
- Used by Kotlin `@ActionComplexity(TaskComplexity.X)` on Embabel actions

The two systems never meet. Chat routing bypasses `TaskComplexity` entirely. This means:

1. Adding a new complexity category requires code changes in two places (enum + keyword sets).
2. Ops cannot retune chat routing without a rebuild (no admin endpoint for `TaskClassifier` rules).
3. Embabel action routing is deterministic (annotation), chat routing is fuzzy (keyword match). Same user intent — different treatment.

## Goal

**One routing pipeline for both paths:**

```
user message  ──┐
                ├──► detect TaskComplexity ──► ComplexityMappingService.resolveRole ──► ChatModel
@ActionComplexity ──┘
```

- Zero hardcoded keyword dictionaries.
- Structural signals only in code (length, punctuation, code blocks, file paths — these are features, not keywords).
- Ambiguous cases → cheap LLM micro-classifier (worker model, single short call).
- All complexity → role mapping goes through existing `complexity_mappings` table — editable at runtime.

## Non-goals

- Learning routing from feedback — separate plan (P19 is static heuristic + LLM).
- Per-user routing preferences — everyone shares `complexity_mappings`.
- Rewriting Embabel `@ActionComplexity` — stays as-is; this plan adapts the chat path to match.
- Full replacement of `TaskClassifier` on day one — runs alongside during Phase 1 with a feature flag.

## Architecture

### Component map (after change)

```
ChatClientRequest
      │
      ▼
ModelRoutingAdvisor.before()
      │
      ├──► ComplexityDetector.detect(message, sessionCtx)  ◄── NEW
      │         │
      │         ├── StructuralHeuristics  (features only, no keywords)
      │         │     ├── length, wordCount, sentences
      │         │     ├── hasCodeBlock, hasFilePath
      │         │     ├── hasQuestionMark, exclamationCount
      │         │     ├── inPlanningMode (existing signal)
      │         │     └── isFirstTurn (existing signal)
      │         │
      │         └── LlmComplexityClassifier  (fallback when scores ambiguous)
      │               ├── calls worker-role model with enum list
      │               ├── returns TaskComplexity
      │               └── cached by message-hash (5 min TTL)
      │
      ▼
TaskComplexity  (one of 9 existing enum values)
      │
      ▼
ComplexityMappingService.resolveRole(complexity)  ◄── EXISTING
      │
      ▼
ChatModelCache.getModelIdForRole(role)  ◄── EXISTING
      │
      ▼
ChatModel (haiku / sonnet / opus / etc)
```

### New components

1. **`ComplexityDetector`** (`ai.kukuvaia.advisors.ComplexityDetector`, Java)
   - Single entrypoint: `TaskComplexity detect(String message, SessionContext ctx)`
   - Delegates to `StructuralHeuristics` first; falls back to `LlmComplexityClassifier` when confidence low.

2. **`StructuralHeuristics`** (`ai.kukuvaia.advisors.StructuralHeuristics`, Java)
   - Pure features — no word dictionaries.
   - Scores each `TaskComplexity` value based on structural signals.
   - Returns `HeuristicResult(TaskComplexity top, double confidence, Map<TaskComplexity,Double> scores)`.
   - Confidence = (top − runnerUp) / max; caller decides threshold.

3. **`LlmComplexityClassifier`** (`ai.kukuvaia.advisors.LlmComplexityClassifier`, Java)
   - Uses `ChatModelCache.getModelIdForRole("worker")` to find cheapest model.
   - System prompt (English, minimal — per `feedback_prompts_english_minimal`):
     ```
     Classify the user message into exactly one category:
     EXTRACTION, TRANSFORMATION, CLASSIFICATION, RETRIEVAL,
     ANALYSIS, GENERATION, SYNTHESIS, STRATEGY, EVALUATION.
     Return only the category name.
     ```
   - Caches by `sha256(message)` → 5 min TTL (`Caffeine`).
   - Timeout 3s; on timeout/error → `TaskComplexity.GENERATION` (supervisor fallback).
   - Audited via existing `ProviderAuditLog` (no bypass).

### Removed / deprecated

- `TaskClassifier.COMPLEXITY_MARKERS`, `PLANNING_INTENT`, `ANALYSIS_INTENT`, `GREETING_WORDS` sets — **deleted**.
- `IntentDetectionAdvisor.READ_KEYWORDS`, `WRITE_KEYWORDS`, `ANALYSIS_KEYWORDS`, `GREETING_WORDS` sets — **deleted**.
- `TaskClassifier.classify(...)` — **deleted** (replaced by `ComplexityDetector`).
- `TaskClassifier.Tier` enum (`FAST/DEFAULT/ESCALATE`) — **deleted**. `TaskComplexity` is now the single vocabulary.
- `ModelRoutingAdvisor.RoutingDecision` enum — **deleted**. Routing decision = `TaskComplexity` + resolved role.

`IntentDetectionAdvisor` keeps its job (tool filtering by intent) but switches to `TaskComplexity` — e.g., `RETRIEVAL` → read-only tools only, `GENERATION` → write tools allowed.

## Structural heuristics — no keywords

Each feature contributes to one or more `TaskComplexity` scores. No word matching.

| Feature | Source | Bumps |
|---|---|---|
| `length ≤ 30 chars` | `msg.length()` | `RETRIEVAL +2`, `CLASSIFICATION +1` |
| `length > 400 chars` | `msg.length()` | `SYNTHESIS +2`, `STRATEGY +1`, `ANALYSIS +1` |
| `wordCount ≤ 3` | split whitespace | `RETRIEVAL +2` |
| `sentenceCount ≥ 3` | period/newline split | `SYNTHESIS +2`, `STRATEGY +1` |
| `hasCodeBlock` | `msg.contains("\`\`\`")` or backticks | `GENERATION +2`, `TRANSFORMATION +1` |
| `hasFilePath` | regex on common ext | `GENERATION +1`, `RETRIEVAL +1` |
| `hasQuestionMark` | `msg.contains("?")` | `ANALYSIS +1`, `RETRIEVAL +1` |
| `hasMultipleQuestions` | count `?` ≥ 2 | `ANALYSIS +2` |
| `inPlanningMode` | `PlanningModeService.isInPlanningMode(sessionId)` | `STRATEGY +3`, `SYNTHESIS +1` |
| `isFirstTurn` | prior messages = 0 | `GENERATION +1` (onboarding → supervisor) |
| `kukuvaia.escalate=true` | context flag | `STRATEGY +10` (hard override) |

Winner = top score. **Confidence threshold = 0.3** (top − runnerUp ≥ 30% of max).

Below threshold → call `LlmComplexityClassifier`.

Above threshold → return top complexity directly (zero LLM cost).

### Why no vocabulary-based signals

The point is to stop maintaining word lists. "Streszcz", "analyze", "plan", "think harder" — all of these are captured by:
- **length** (long complex asks → SYNTHESIS/STRATEGY),
- **sentence count** (multi-sentence → more work),
- **planning mode signal** (authoritative — set by `/plan`),
- **`?` count** (comparison/analysis questions tend to stack them),
- **LLM fallback** (handles semantic cases heuristics miss).

Hard escalation remains via explicit `kukuvaia.escalate` context flag (used by `LoopDetectionAdvisor` + new `/escalate` slash command — see §Integration).

## Integration

### `ModelRoutingAdvisor` rewrite

Before:
```java
RoutingDecision decision = classify(userMessage, escalateFlag, sessionId);
var modelUuid = chatModelCache.getModelIdForRole(decision.role());
```

After:
```java
TaskComplexity complexity = complexityDetector.detect(userMessage, sessionCtx);
String role = complexityMappingService.resolveRole(complexity);
var modelUuid = chatModelCache.getModelIdForRole(role);
```

`lastDecision()` / `lastRoutedModel()` ThreadLocals keep their CLI contract — just expose the `TaskComplexity` name instead of `FAST/DEFAULT/ESCALATE`. CLI activity tracker badge already reads the string.

### Observability (span attributes)

Replace:
- `kukuvaia.routing.decision` (old: `FAST`/`DEFAULT`/`ESCALATE`) → keep attribute name, new values are `TaskComplexity` names
- Add `kukuvaia.routing.source` = `heuristic` | `llm_fallback` | `explicit_flag`
- Add `kukuvaia.routing.confidence` = heuristic confidence score (0.0–1.0)
- Add `kukuvaia.routing.scores` = map of complexity → score (logged at INFO for non-default outcomes, DEBUG otherwise)

Prometheus:
- Rename counter `kukuvaia.routing.decision{tier}` → `kukuvaia.routing.complexity{complexity, source}`
- New histogram `kukuvaia.routing.llm_fallback.duration` (fires only when LLM called)

### Explicit escalation — `/escalate` slash command

New deterministic command: `/escalate` — sets `kukuvaia.escalate=true` in context for the **next** turn only. Replaces the hardcoded "think harder" phrase matching cleanly: users who want the big model just type `/escalate` before their question.

Files:
- `commands/EscalateCommand.java` (new) — stores `escalate_next=true` in session state
- `ModelRoutingAdvisor` reads session state flag + clears after use
- Documented in CLI help and persona instructions

## Phased rollout

### Phase 1 — shadow mode (safe, reversible)

- Implement `ComplexityDetector` + `StructuralHeuristics` + `LlmComplexityClassifier`.
- Run alongside existing `TaskClassifier` **without changing routing decisions**.
- Log both decisions side-by-side; emit metric `kukuvaia.routing.shadow_diff{old, new}`.
- Let it run 24–48h on real traffic. Tune heuristic weights based on divergence analysis.

### Phase 2 — cutover

- Flip feature flag `kukuvaia.routing.mode=complexity` (default `keyword`).
- `ModelRoutingAdvisor` uses `ComplexityDetector` as authoritative.
- Keep `TaskClassifier` code present but unused (dead-code eliminator pass comes next).

### Phase 3 — cleanup

- Delete `TaskClassifier.java`, its test, and keyword sets from `IntentDetectionAdvisor`.
- Rewrite `IntentDetectionAdvisor` to map `TaskComplexity` → tool filter.
- Remove old `RoutingDecision` enum, old Tier enum, feature flag `kukuvaia.routing.mode`.
- Update all tests + span attribute docs.

## Acceptance criteria

- [ ] `ComplexityDetector.detect("hej")` → `RETRIEVAL` via heuristics, no LLM call
- [ ] `ComplexityDetector.detect("streszcz ten 30-stronicowy dokument w 5 punktach")` → `SYNTHESIS` via heuristics (long + multi-sentence + planning phrasing captured structurally)
- [ ] `ComplexityDetector.detect("czy A jest lepsze niż B? jak to zmierzyć? co by to oznaczało?")` → `ANALYSIS` (3 question marks)
- [ ] `kukuvaia.escalate=true` in context → `STRATEGY` regardless of message content (hard override)
- [ ] Ambiguous message (confidence < 0.3) → triggers `LlmComplexityClassifier`; result cached by message hash
- [ ] `LlmComplexityClassifier` timeout → returns `GENERATION` (supervisor-tier safe default)
- [ ] `ComplexityMappingService.resolveRole(SYNTHESIS)` returns DB-configured role (seeded to `supervisor`) — edit the table, next turn uses new role without restart
- [ ] Zero occurrences of hardcoded word sets in `advisors/` package after Phase 3
- [ ] Existing `ModelRoutingAdvisorTest` migrated — every old test case has an equivalent new assertion on `TaskComplexity` instead of `Tier`
- [ ] `/escalate` command sets flag for one turn, clears after use
- [ ] Shadow-mode metric `kukuvaia.routing.shadow_diff` available during Phase 1

## Tests

- `StructuralHeuristicsTest` — 15 concrete messages × expected top complexity; no LLM.
- `LlmComplexityClassifierTest` — mock worker ChatModel; verify cache hit/miss, timeout fallback, audit log emission.
- `ComplexityDetectorTest` — end-to-end with mocked heuristics + LLM: confident heuristic skips LLM; low-confidence triggers LLM.
- `ModelRoutingAdvisorTest` — rewrite existing cases to new vocabulary.
- `EscalateCommandTest` — flag set, cleared after one turn, session-scoped.
- Integration test: real session, `/escalate` then message → routed to advisor role.

## Security

- `LlmComplexityClassifier` sends user message to a worker-tier LLM. Already covered by `ProviderAuditLog` (no new surface). Ensure the classifier call goes through the standard `OpenAiChatModel` bean, not a side channel — so rate limiting + retry + audit all apply.
- Message hash (sha256) as cache key — no PII echo in logs. Log only the hash prefix + resolved complexity.
- Cache is in-memory (Caffeine), per-process. No cross-session leakage risk since the value is a 9-enum category, not the message itself.

## Effort

| Task | Traditional | AI-paired |
|---|---|---|
| `ComplexityDetector` + `StructuralHeuristics` | 0.5 day | 40 min |
| `LlmComplexityClassifier` (with cache + timeout) | 0.5 day | 30 min |
| Rewrite `ModelRoutingAdvisor` to use `ComplexityDetector` | 0.25 day | 15 min |
| `/escalate` command | 0.25 day | 15 min |
| Shadow-mode integration + metric | 0.25 day | 20 min |
| Tests (5 classes) | 0.75 day | 45 min |
| Delete `TaskClassifier` + `IntentDetectionAdvisor` keyword sets + migrate tests | 0.5 day | 30 min |
| Observability rename + dashboard updates | 0.25 day | 15 min |
| **Total** | **~3.25 days** | **~3.5 h** |

## Dependencies

- Requires P18 **shipped** (it is — see `P18-intelligent-task-routing.md`). P19 replaces P18's `TaskClassifier` entirely; P18 established the infrastructure (advisor ordering, ThreadLocal contract, span attributes) that P19 reuses.
- `ComplexityMappingService` + `complexity_mappings` table — **already in place** (V7 migration, shipped).
- `ChatModelCache.getModelIdForRole("worker")` must resolve a cheap model — currently true; guarded by a startup check.

## Relationship to other plans

- **P18 (intelligent task routing)** — P19 is the next evolution. P18 kept keyword fallback; P19 removes keywords and unifies with Embabel's complexity model.
- **P08 (prompt cache optimization)** — `LlmComplexityClassifier` prompt is static English; high cache hit rate expected. Budget the cost assuming ~50% cache hits.
- **P13 (planning discovery state)** — `inPlanningMode` remains a heuristic input (top signal for `STRATEGY`). No conflict.
- **P14 (tiered context)** — orthogonal. P19 picks the model; P14 trims the context. They compose.
- **P03 (model fallback chain)** — if `LlmComplexityClassifier` fails over, the fallback chain kicks in transparently (uses existing worker-role resolution).

## Open questions

1. Should `LlmComplexityClassifier` run async + use stale cache while refreshing, to avoid adding a hop to first-turn latency? Decide after Phase 1 shadow-mode data.
2. Is `CLASSIFICATION` complexity even triggered by chat messages, or only by Embabel actions? If never chat-triggered, drop it from the LLM prompt to shorten.
3. `IntentDetectionAdvisor` currently feeds tool filtering — verify mapping `TaskComplexity → allowed tools` is obvious. If ambiguous, split into separate plan.
