# P20 — Routing Self-Tuning via Dreaming

**Status:** Draft
**Created:** 2026-04-19
**Depends on:** P19 Phase 1 (shadow mode, shipped) — provides the telemetry this plan consumes. P12 (Agent Reflection / Dreaming) — provides the scheduled-task infrastructure.
**Scope:** Close the routing feedback loop. Use the existing `DreamService` to analyse shadow-mode divergences, correlate with implicit outcome signals, and propose `DreamRecommendation`s that retune `complexity_mappings` and heuristic weights without code changes. De facto RouteLLM on our own telemetry — minus model training, plus Dreaming patterns.

## Problem

P19 Phase 1 ships a rich telemetry stream:
- `kukuvaia.routing.shadow_diff{old_role, new_role, complexity, source, same, confidence}` counter
- INFO log per divergence
- `complexity_mappings` table editable via admin API

But nothing **consumes** it. The heuristic weights are baked into `StructuralHeuristics.java`. The complexity→role mapping sits in a DB table that nobody edits because nobody has data-driven reasons to. Shadow divergences accumulate silently.

Meanwhile, **`DreamService`** already runs scheduled passes, already produces `DreamRecommendation`s with priority + confidence + evidence JSONB + accept/reject workflow. Migration V9 is live. It does health checks and config audits today — routing audit is a natural extension.

Without P20, P19 Phase 2 cutover is a guess: "I *think* the new routing is better because logs look OK." With P20, cutover is a decision grounded in measured outcomes.

## Goal

A nightly Dreaming task that:

1. Ingests last-24h shadow diffs + turn outcomes.
2. Identifies systematic under/over-routing patterns.
3. Produces `DreamRecommendation` entries of type `TUNE_ROUTING` with concrete actionable changes (mapping flips, weight adjustments).
4. On admin approval (or auto-apply at high confidence) mutates `complexity_mappings` / a new `heuristic_weights` table.
5. Next turn's `ModelRoutingAdvisor` reads the new values — no restart.

## Non-goals

- **Train a custom ML classifier** — heuristic tuning + complexity remapping is enough for Phase 1. Neural router comes later or never.
- **Real-time adaptation** — tuning runs nightly, not per-turn. Per-turn adaptation would inflate variance and make behaviour unreproducible.
- **Eliminate the LLM fallback** — LLM-fallback rate itself is a useful signal (persistent high rate = heuristics need expansion). Goal is to minimise it, not remove it.
- **User-specific routing** — aggregate tuning only. Per-user preferences are P21 territory.

## Architecture

```
ModelRoutingAdvisor.runShadowComparison()    ← P19 Phase 1 (shipped)
      ↓
  shadow_diff counter                         ← metric
  + routing_decisions table (NEW in P20)      ← per-turn row with outcome fields
      ↓
  Outcome signals collected per turn:
      - user_escalated_next   (bool)          ← /escalate within N turns
      - user_reworded_next    (bool)          ← same-session restatement heuristic
      - cost_tokens           (int)           ← ProviderAuditLog join
      - response_latency_ms   (int)
      - thumbs_up / thumbs_down (nullable)    ← NEW CLI feedback
      ↓                                        [nightly]
  DreamService.runDream()
      + new RoutingAuditTask                  ← P20
          ↓
          aggregates, correlates, produces findings
          ↓
          DreamRecommendation rows:
            type = TUNE_ROUTING
            suggested_action = "flip ANALYSIS → advisor" | "bump hasCodeBlock weight to +5" | …
            evidence = {sample_count, success_rate_old, success_rate_new, …}
            confidence = 0.0–1.0
      ↓ [admin review OR auto-apply if confidence ≥ 0.85]
      UPDATE complexity_mappings / heuristic_weights
      ↓ [next turn]
      ModelRoutingAdvisor reads refreshed mapping — no restart, no rebuild
```

### Phase A — Outcome signals + CLI feedback (2–3 days)

**New table** `kukuvaia.routing_decisions` (V14 migration):

```sql
CREATE TABLE kukuvaia.routing_decisions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id         VARCHAR(255) NOT NULL,
    turn_index         INT NOT NULL,             -- session-local turn counter
    created_at         TIMESTAMP DEFAULT NOW(),

    -- routing snapshot (dual — authoritative + shadow)
    old_role           VARCHAR(50) NOT NULL,
    new_complexity     VARCHAR(30),
    new_role           VARCHAR(50),
    source             VARCHAR(30),               -- EXPLICIT_FLAG / HEURISTIC / LLM_FALLBACK / DEFAULT
    confidence         DECIMAL(3,2),

    -- message features (for later analysis without re-reading messages)
    message_hash       CHAR(64),                  -- sha256 for joins/dedup
    message_length     INT,
    word_count         INT,
    has_code_block     BOOLEAN,
    has_file_path      BOOLEAN,
    question_marks     INT,
    in_planning_mode   BOOLEAN,

    -- outcome fields (populated by OutcomeTracker over the next ~10 turns)
    user_escalated_next  BOOLEAN DEFAULT FALSE,   -- /escalate fired within 3 turns after this
    user_reworded_next   BOOLEAN DEFAULT FALSE,   -- edit-distance heuristic vs next turn
    cost_tokens          INT,                     -- ProviderAuditLog join
    response_latency_ms  INT,
    thumbs               SMALLINT,                -- -1/0/+1, NULL = no feedback

    outcome_finalised_at TIMESTAMP                -- when OutcomeTracker stopped mutating this row
);

CREATE INDEX idx_routing_decisions_session ON kukuvaia.routing_decisions(session_id, turn_index);
CREATE INDEX idx_routing_decisions_daily ON kukuvaia.routing_decisions(created_at DESC);
```

**New components:**

- `RoutingDecisionRepository` (Java) — JdbcTemplate inserts from `ModelRoutingAdvisor` (async fire-and-forget)
- `OutcomeTracker` — updates outcome columns:
  - On `/escalate`: mark last 1–3 decisions with `user_escalated_next=true`
  - On reword heuristic: Levenshtein distance on consecutive user turns, threshold 0.6 similarity
  - `cost_tokens` + `response_latency_ms` — joined from `ProviderAuditLog` by session+turn
- CLI `thumbs up/down` — new keybinding (`ctrl+j` / `ctrl+k` for last assistant message), POSTs to `/api/feedback/{decisionId}`

### Phase B — RoutingAuditTask (3–4 days)

**New `DreamTask` implementation** — `ai.kukuvaia.dream.tasks.RoutingAuditTask`.

Invoked by `DreamService.runDream()` after existing health-check + config-audit phases. Operates on last-24h `routing_decisions`. Zero LLM calls by default (Phase B1); LLM-as-judge optional (Phase B2).

**Aggregations:**

```
-- mismatch matrix
SELECT old_role, new_role, source, COUNT(*) AS n,
       AVG(cost_tokens) AS avg_cost,
       AVG(CASE WHEN user_escalated_next THEN 1.0 ELSE 0.0 END) AS escalation_rate,
       AVG(CASE WHEN user_reworded_next THEN 1.0 ELSE 0.0 END) AS reword_rate,
       AVG(NULLIF(thumbs, 0)) AS avg_feedback
FROM kukuvaia.routing_decisions
WHERE created_at > NOW() - INTERVAL '1 day'
  AND outcome_finalised_at IS NOT NULL
GROUP BY old_role, new_role, source;
```

**Decision rules (deterministic, explainable):**

| Pattern | Threshold | Recommendation |
|---|---|---|
| Old role `worker`, `user_escalated_next` rate ≥ 15% in ≥ 50 samples | → recommend mapping the dominant complexity to `supervisor` |
| Source `LLM_FALLBACK` share ≥ 30% overall | → recommend expanding structural heuristics (manual follow-up — adds a feature extractor) |
| Feature X (e.g. `has_code_block=true`) routes to RETRIEVAL ≥ 20% of the time, `user_reworded_next` ≥ 20% | → recommend bumping heuristic weight for that feature |
| Advisor-tier turns with `thumbs=-1` rate ≥ 30% for complexity Y | → recommend downgrading Y mapping from `advisor` to `supervisor` |
| Same-role agreement (old == new) ≥ 95% for 7 consecutive days | → recommend Phase 2 cutover (P19) |

Each finding becomes a `DreamRecommendation` row:

```java
new DreamRecommendationRecord(
    reportId,
    "TUNE_ROUTING",
    priority,                      // high if escalation rate > 30%, medium otherwise
    description,                   // human-readable "ANALYSIS over-routed on advisor, supervisor would suffice"
    suggestedAction,               // machine-parseable YAML-ish stanza (see below)
    confidence,                    // fraction of sample space + signal strength
    evidenceJson                   // raw counts, rates, sample queries
);
```

**`suggested_action` schema (parseable by the auto-apply path in Phase C):**

```yaml
kind: UPDATE_COMPLEXITY_MAPPING
complexity: ANALYSIS
from_role: advisor
to_role: supervisor
reason: "avg cost 4.2× baseline, thumbs-up rate unchanged, escalation rate 3%"
```

```yaml
kind: ADJUST_HEURISTIC_WEIGHT
feature: hasCodeBlock
target: GENERATION
from_weight: 4
to_weight: 5
reason: "code-block messages routed away from GENERATION in 18% of cases, re-worded rate 22%"
```

Weights move to a new table:

```sql
CREATE TABLE kukuvaia.heuristic_weights (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    feature         VARCHAR(60) NOT NULL,         -- e.g. "hasCodeBlock"
    complexity      VARCHAR(30) NOT NULL,
    weight          INT NOT NULL,
    updated_at      TIMESTAMP DEFAULT NOW(),
    updated_by      VARCHAR(255),
    UNIQUE (feature, complexity)
);
```

Seeded from current `StructuralHeuristics` constants (one-time data migration). `StructuralHeuristics` becomes a thin reader over a cached `HeuristicWeightService` — same pattern as `ComplexityMappingService`.

### Phase C — Auto-apply + guard rails (2 days)

**New config:**

```yaml
kukuvaia:
  routing:
    auto-apply:
      enabled: ${KUKUVAIA_ROUTING_AUTOAPPLY:false}
      min-confidence: 0.85
      max-changes-per-run: 3
      cooldown-hours: 24                  # don't flip the same mapping twice in a day
```

**Auto-apply path:** after `RoutingAuditTask` writes recommendations, if `auto-apply.enabled=true`, pick the top-N `TUNE_ROUTING` recs with confidence ≥ threshold, parse `suggested_action` YAML, dispatch to `ComplexityMappingService.updateMapping()` or `HeuristicWeightService.updateWeight()`, mark recommendation `status='accepted'` with `resolved_by='dream:auto'`.

Guard rails:
- **Cooldown** per mapping key — prevents oscillation
- **Max 3 changes per run** — blast radius control
- **Rollback command** — `/dream rollback <report-id>` reverts that run's mutations by reading recommendation audit trail
- **Silent run first N days** — `auto-apply.enabled=false` default, admin reviews in dashboard, flips on when comfortable

**Dashboard surface:** extend existing `DashboardController` with a "Routing Intelligence" section — shows last run's findings, accept/reject buttons, history of auto-applied changes with before/after metric comparison.

## Acceptance criteria

- [ ] V14 migration creates `routing_decisions` + `heuristic_weights` tables with seeds
- [ ] `ModelRoutingAdvisor` writes a `routing_decisions` row per turn (async, never blocks)
- [ ] `OutcomeTracker` populates outcome columns over the turn's tail window
- [ ] CLI `ctrl+j` / `ctrl+k` bindings post thumbs feedback on the last assistant message
- [ ] `RoutingAuditTask` runs as part of `DreamService.runDream()` — integration test with seeded data asserts ≥ 1 `TUNE_ROUTING` recommendation when patterns match thresholds
- [ ] Auto-apply path gated behind config flag — disabled by default
- [ ] Accepting a recommendation via API mutates the target table; next `ChatModelCache.refreshRoles()` / `HeuristicWeightService.refreshCache()` reflects the change
- [ ] Rollback command restores prior state using the audit trail
- [ ] Dashboard shows last 7 days of recommendations with status + evidence drill-down

## Tests

- `OutcomeTrackerTest` — reword heuristic, escalation attribution window, thumbs update
- `RoutingAuditTaskTest` — fake `routing_decisions` fixture data, assert correct `DreamRecommendation`s emitted per decision rule
- `HeuristicWeightServiceTest` — cache refresh, update path, fallback to compiled defaults if table empty
- `DreamRoutingAutoApplyTest` — simulate high-confidence rec, verify mapping flipped, verify cooldown blocks second flip within 24h
- Integration: seed 100 synthetic `routing_decisions` rows matching "ANALYSIS over-routed" pattern, run `DreamService`, assert recommendation row + (with autoapply on) `complexity_mappings` flipped
- Edge: empty last-24h → task completes cleanly, emits 0 recommendations, no errors

## Security

- Thumbs feedback endpoint `/api/feedback/{decisionId}` requires session auth (same as `/api/chat`) — user can only thumbs-rate turns from their own sessions
- `suggested_action` YAML is parsed by a strict dispatcher (enum `kind`, whitelisted fields) — never `eval`'d or reflected into arbitrary method calls
- Auto-apply audit: every mutation logs `(recommendation_id, before_value, after_value, applied_at)` in a new `routing_audit` table — compliance-ready
- Rollback is idempotent and only touches rows the audit trail claims this report mutated

## Effort

| Task | Traditional | AI-paired |
|---|---|---|
| V14 migration + `RoutingDecisionRepository` | 0.5 day | 30 min |
| Async `routing_decisions` insert in `ModelRoutingAdvisor` | 0.25 day | 20 min |
| `OutcomeTracker` (reword heuristic, escalation attribution, ProviderAuditLog join) | 1 day | 60 min |
| CLI thumbs feedback UI + API endpoint | 0.5 day | 40 min |
| `RoutingAuditTask` + decision rules + evidence JSON | 1.5 days | 90 min |
| `HeuristicWeightService` + `StructuralHeuristics` refactor | 0.75 day | 45 min |
| Auto-apply dispatcher + cooldown + rollback | 0.75 day | 45 min |
| Dashboard "Routing Intelligence" panel | 0.5 day | 40 min |
| Tests (5 classes + integration) | 1 day | 60 min |
| **Total** | **~6.75 days** | **~7 h** |

## Rollout

1. **Phase A** ships first — starts collecting data. Runs for ~2 weeks in silent mode to build a fixture-quality dataset before anything tunes.
2. **Phase B** ships — produces recommendations, all `pending`, admin reviews via dashboard. Accept/reject teaches us whether the rules are sane. Run 2+ weeks.
3. **Phase C** flips `auto-apply.enabled=true` with conservative thresholds (`min-confidence=0.9`). Monitor change rate, rollback spikes. Relax thresholds only if no damage after a month.

## Relationship to other plans

- **P12 (Agent Reflection / Dreaming)** — P20 is the first concrete "insight-producing" dream task beyond deterministic audits. Shared infrastructure: `DreamReportRepository`, `DreamRecommendationRepository`, scheduled runner.
- **P19 (Complexity-Driven Routing)** — P20 consumes P19's shadow-diff telemetry and drives P19's Phase 2 cutover decision with data. Without P20, Phase 2 is a gut call.
- **P07 (Cost Tracking)** — P20 would benefit from P07's per-turn cost attribution instead of building its own `ProviderAuditLog` join. If P07 ships first, Phase A's `cost_tokens` column reads P07's view.
- **P05 (Eval Pipeline)** — shares the "did this turn succeed?" question. Eval gives ground truth on curated tasks; P20 gives behavioural signal on real traffic. Complementary, not overlapping.
- **P15 (Honest Agent) — Pillar 3 (Empirical Calibration)** — P20's outcome tracking is the scaffolding calibration tracking would use. If P15 Pillar 3 ships first, P20 reuses its infra.

## Open questions

1. **Reword detection — Levenshtein or embeddings?** Levenshtein is zero-cost and fine for catching "write a greedy algo" → "no, write a greedy algorithm that handles duplicates". Embeddings catch semantic rewording ("that's wrong, try again with constraint X") but require an extra call per turn. Start with Levenshtein, upgrade if signal is noisy.
2. **LLM-as-judge nightly pass — worth the cost?** Taking 50 random turns/day and asking a worker model "did this response solve the task" would give higher-quality ground truth. Cost: ~50 × 2k tokens = small. Deferred to Phase B2.
3. **Per-persona tuning?** A `research` persona has different routing needs from `editor`. Stratify recommendations by persona or keep global? Start global; split when cardinality and evidence justify it.
4. **Auto-apply on what triggers?** Only mapping flips (low blast radius, one row per change) vs heuristic-weight tuning (can cascade). Start with mapping-only auto-apply; weight changes require manual approval.
