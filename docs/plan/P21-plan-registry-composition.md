# P21 — Plan Registry & Composition

**Status:** Draft — ready to implement pending 3 design decisions (§"Open questions").
**Created:** 2026-04-20
**Depends on:** P13 (Planning Discovery State, shipped) — adds phase machine P21 persists. `kukuvaia.plans` table (V4, shipped). `SessionPicker` pattern in CLI (shipped) — mirror for `PlanPicker`.
**Scope:** Elevate plans from session-local side-effects to first-class objects with persistence, listing, resume, and composition (linking). Users browse plans interactively, resume drafts after restart, and combine multiple plans into a new one with inherited context.

## Problem

Current plan lifecycle is tied to session in-memory state:

1. `PlanningModeService` keeps phase + `DiscoveryFacts` in a `ConcurrentHashMap<sessionId, PlanningSession>`. Engine restart → map is empty → drafts in DB are orphaned.
2. `/plan cancel` marks DB row `status='abandoned'` — destructive, not recoverable.
3. No way to list user's plans across sessions from the CLI — only `SessionContextAdvisor` passively injects them into the system prompt.
4. No way to resume a draft plan after engine restart or session switch.
5. No composition — if user has "Crete trip" and "Czech trip" plans, there is no flow to create "combined trip" referencing both.
6. The CLI has no visible plan state beyond my recent yellow-border badge; ctrl+p currently destroys the draft.

The user's proposal covers all of these in one design: `/plans` interactive picker + plan linking + natural-language trigger.

## Goal

1. Users can invoke `/plans` (or ask "jakie miałem plany?") and see an arrow-navigable picker of their plans with status, age, and parent-link indicator.
2. From the picker: resume (re-attach planning session to a DB draft), abandon, create-new-from, or toggle-for-combine.
3. Combining two or more plans starts a new planning session seeded with merged parent facts and a `plan_links` entry per parent.
4. Plans survive engine restart — phase + discovery facts persisted in `kukuvaia.plans`.
5. Natural-language path exists through `@Tool list_plans / resume_plan / combine_plans` — agent can call them from free-form conversation.

## Non-goals

- **Transitive closure of plan_links** — if C combines A+B and B combines X+Y, C does NOT auto-inherit X+Y facts. Only direct parents. Keeps merge tractable.
- **Cross-user plan sharing** — plans are strictly per-user. `plan_links` only between plans owned by same user. Collaboration is out-of-scope.
- **Plan templates / cloning** — just composition. Templates are a separate feature.
- **Plan editing UI** (rewrite steps manually) — the existing `revisePlan` tool stays. Picker only navigates, selects, and launches actions.
- **Scheduled plan execution** — plans are still static outputs. Execution = commitments (P17) or daemon (out-of-scope here).

## Architecture

```
kukuvaia.plans                  ← V4, extended by V16 (name, phase, discovery_facts)
   │
   ├─→ plan_links (NEW)         ← relations between plans
   │
   ├─→ PlansRepository
   │     findByUser / findById / findWithParents / createLink
   │
   ├─→ PlanController  (NEW)
   │     GET  /api/plans?user=…&status=…
   │     GET  /api/plans/{id}
   │     POST /api/plans/{id}/resume
   │     POST /api/plans/combine
   │
   ├─→ PlanningModeService  (extended)
   │     resumeFromDb(planId)       — rehydrate session from DB
   │     combinePlans(parents, task)— new session with merged facts + links
   │     mergeFacts(parents)        — discovery_facts union with dedup
   │
   ├─→ @Tool (new)
   │     list_plans(status?, limit?)
   │     resume_plan(planId)
   │     combine_plans(parentIds, newTask)
   │
   ├─→ PlanListBlock (new OutputBlock)  ← renders as interactive picker on CLI
   │
   └─→ Slash commands
         /plans                         — opens picker
         /plan resume <id>              — direct resume
         /plan combine <id1> <id2> ...  — direct combine
         /plan cancel                   — existing, unchanged (destructive)

CLI
   ├─→ PlanPicker component          ← mirror of SessionPicker
   │     ↑↓ nav · enter resume · c combine-toggle · n new-from · d abandon · / filter · esc close
   │
   ├─→ ctrl+p repurposed             ← now opens /plans picker (not /plan cancel)
   │
   └─→ auto-open picker on PlanListBlock arrival
```

## Data model (V16 migration)

```sql
ALTER TABLE kukuvaia.plans
  ADD COLUMN name             VARCHAR(120),
  ADD COLUMN phase            VARCHAR(20) DEFAULT 'approval'
      CHECK (phase IN ('discovery', 'drafting', 'approval', 'done', 'abandoned')),
  ADD COLUMN discovery_facts  JSONB DEFAULT '{}';

-- Backfill name for existing rows — first 60 chars of task, trimmed at word boundary
UPDATE kukuvaia.plans SET name = LEFT(task, 60) WHERE name IS NULL;

-- Align phase with current status for legacy rows
UPDATE kukuvaia.plans SET phase = CASE
    WHEN status = 'active'    THEN 'done'         -- legacy 'active' meant approved
    WHEN status = 'completed' THEN 'done'
    WHEN status = 'abandoned' THEN 'abandoned'
    WHEN status = 'draft'     THEN 'approval'     -- best guess; user can still cancel
    ELSE 'approval'
END;

CREATE INDEX idx_plans_user_phase ON kukuvaia.plans(user_id, phase, updated_at DESC);

CREATE TABLE kukuvaia.plan_links (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_plan_id  UUID NOT NULL REFERENCES kukuvaia.plans(id) ON DELETE CASCADE,
    target_plan_id  UUID NOT NULL REFERENCES kukuvaia.plans(id) ON DELETE CASCADE,
    relation        VARCHAR(30) NOT NULL
        CHECK (relation IN ('combines', 'derived_from', 'follows', 'alternative_to')),
    note            TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE (source_plan_id, target_plan_id, relation),
    CHECK (source_plan_id <> target_plan_id)
);

CREATE INDEX idx_plan_links_source ON kukuvaia.plan_links(source_plan_id);
CREATE INDEX idx_plan_links_target ON kukuvaia.plan_links(target_plan_id);
```

**Relation semantics:**

| Relation | Meaning | Direction |
|---|---|---|
| `combines` | New plan was created from the simultaneous merge of multiple parents | source = new plan, target = each parent |
| `derived_from` | New plan branched from one existing plan (copy-as-starting-point) | source = new plan, target = single parent |
| `follows` | Plan B is a continuation of plan A (A's output is B's input) | source = B, target = A |
| `alternative_to` | Plan B is a different approach to the same problem | source = B, target = A |

MVP ships `combines` + `derived_from` — other relations are data-only until UX demand.

## Phased rollout

### Phase A — Registry foundation (~1.5 days AI-paired)

- V16 migration + backfill test
- `PlansRepository` read methods: `findByUser(userId, status?, limit)`, `findById(planId)`, `findWithParents(planId)`, `findByParent(parentId)`
- `PlanController` with `GET /api/plans`, `GET /api/plans/{id}` — session auth, user scoping
- New `PlanListBlock` OutputBlock (sealed hierarchy) with `List<PlanEntry>`
- `/plans` slash command returns `PlanListBlock`
- Tests: repository, controller auth isolation (user A can't see user B's plans), block serialization

### Phase B — CLI Picker (~1 day AI-paired)

- `PlanPicker` component modeled on `SessionPicker` (same Bubbletea patterns, styles_ui.go)
- Bindings: ↑↓ navigate, enter=resume, `c`=toggle-combine-mark, `n`=new-from, `d`=abandon, `/`=cycle status filter (all/draft/active/done), esc=close
- Auto-open picker on `PlanListBlock` arrival (agent-triggered) + on `/plans` command
- Ctrl+p changed: was `/plan cancel`, now opens picker. `/plan cancel` stays as explicit destructive command.
- Multi-select visual: `[x]` marker on selected items, combine button activates when ≥ 2 selected
- Tests: go test for selection state machine

### Phase C — Resume & Combine (~2 days AI-paired)

- `PlanningModeService.resumeFromDb(planId)` — reads plan row, rebuilds `PlanningSession` with phase from DB, deserializes `discovery_facts` JSONB, puts into `sessions` map, logs resume event
- Phase mapping on resume: DB phase column is authoritative. `abandoned` / `done` refuse resume with error. `discovery` / `drafting` / `approval` re-enter that phase's prompt.
- `PlanningModeService.combinePlans(List<UUID> parents, String newTask, String userId)` —
  - Validates all parents belong to user
  - Creates new plan row with `phase='discovery'`
  - Calls `mergeFacts(parents)` to seed `discovery_facts`
  - Inserts N rows in `plan_links` with `relation='combines'`
  - Returns new plan id → caller starts planning session for current chat session
- `mergeFacts` strategy — see Open question #1
- Phase serialization — each phase transition now writes `discovery_facts` + `phase` column. `updateFacts()` already does it in memory; add DB flush.
- Plan phase persistence: extend existing `advanceToDrafting/approval` methods with `UPDATE plans SET phase=? WHERE id=?`.
- `/plan resume <id>`, `/plan combine <id1> <id2> [...]`, `/plan new --parent <id>` — CommandRouter dispatch
- Tests: resume after engine restart (integration, real PG), combine preserves facts from both parents, user-isolation refusal

### Phase D — Natural-language tools (~1 day AI-paired)

- `@Tool list_plans(status?, limit?)` — returns structured list; agent renders as `PlanListBlock` (advisor decides whether to also verbally summarise)
- `@Tool resume_plan(planId)` — calls `resumeFromDb`
- `@Tool combine_plans(parentIds: List<UUID>, newTask)` — calls `combinePlans`, starts planning session
- Persona system prompt: register the new tools as available, with a 1-sentence capability description each
- Tests: agent-intent integration test — "jakie mam plany?" → tool call list_plans → PlanListBlock in response

## Fact merging for `combinePlans`

Open question #1 — see below. MVP strategy chosen:

**Naive concat + dedup** (zero LLM cost):
- `knownFacts` = union of parents' `knownFacts`, deduped by exact string match
- `excludedOptions` = union, deduped
- `remainingGaps` = union (duplicates across parents signal cross-plan uncertainty — keep as distinct entries with parent id suffix)
- `ambiguities` = empty (re-asked in new DISCOVERY)

This is pragmatic: users expect the new plan's DISCOVERY phase to start from the combined knowns, with gaps explicitly unclear. An LLM merge pass is a future enhancement when users report "too much duplication" or "missed semantic overlaps".

## CLI UX detail

### Picker render (mockup)

```
  ┌─────────────────────────────────────────────────────────────┐
  │  My Plans                                           [all ↓] │
  ├─────────────────────────────────────────────────────────────┤
  │  [✓ active]  Wyjazd Kreta lipiec                    2d ago  │
  │ >[✓ active]  Wyjazd Czechy październik              1d ago  │
  │  [… draft ]  Refactor auth middleware               3h ago  │
  │  [✗ done  ]  Q1 launch roadmap                      2w ago  │
  │  [… draft ]  ↳ from: Wyjazd Kreta + Wyjazd Czechy   5m ago  │
  ├─────────────────────────────────────────────────────────────┤
  │  ↑↓ nav · enter resume · c combine · n new · d abandon · / filter · esc │
  └─────────────────────────────────────────────────────────────┘
```

Parent-link indicator: `↳ from: <parent names>` shown under combined plans.

### Combine flow

```
User marks [c] on 2 plans, presses enter:
  CLI: "New task for combined plan:"
        [text input]
User: "wspólny 2-tygodniowy wyjazd: Czechy + Kreta"
CLI: POST /api/plans/combine { parents:[…], task:"…" }
Server:
  - Creates plan row, phase=discovery, discovery_facts=merged
  - Inserts 2x plan_links (combines)
  - Starts planning session for current chat session pointing at new plan
  - Returns PlanBlock confirmation + DISCOVERY prompt
CLI: planning UI reactivates (yellow border + badge)
     Badge shows: "◆ PLANNING · DISCOVERY · from: Kreta + Czechy"
Agent: "Łączę dwa plany. Znane fakty: [list from merged facts].
        Czego jeszcze brakuje żeby zaplanować wspólny wyjazd?"
```

### Natural-language combine (no picker needed)

```
User (inside Czech plan approval): "stwórzmy nowy plan łączący ten z Kretą"
Agent: list_plans(filter="kreta") → single match
       combine_plans([crete_id, current_plan_id], task="połączone: Czechy + Kreta")
Agent: same DISCOVERY prompt as above
```

## Acceptance criteria

- [ ] V16 migration applied, backfills `name` from `task`, backfills `phase` from `status`
- [ ] `GET /api/plans` returns only the authenticated user's plans with parent-link info
- [ ] `/plans` slash command opens picker; empty state shows "No plans yet"
- [ ] Picker keybindings work: ↑↓ nav, enter resume, c combine-toggle, n new-from, d abandon (confirms with `/plan cancel` semantics), / filter, esc close
- [ ] Ctrl+p now opens picker instead of cancelling
- [ ] Resume rehydrates `PlanningSession` with correct phase + facts (integration test with real PG)
- [ ] Engine restart test: start planning → restart engine → `/plans` shows draft → resume → planning UI active with original facts visible
- [ ] Combine with 2 plans: new plan row created with phase=discovery, 2 plan_links rows created, merged facts visible in DISCOVERY prompt
- [ ] User-isolation: user B cannot resume or combine user A's plans (server returns 403)
- [ ] Natural language: "jakie mam plany" triggers `list_plans` tool → `PlanListBlock` → picker auto-opens
- [ ] Natural language: "połącz ten plan z planem X" triggers `combine_plans` → new planning session
- [ ] `/plan cancel` (explicit) still marks plan `abandoned` — not weakened
- [ ] PlanPicker closes cleanly on esc, restores pre-picker viewport scroll position

## Tests

- `PlansRepositoryTest` — find-by-user filtering, parent-link assembly, status filter
- `PlanControllerTest` — auth isolation (403 for other user), pagination, status filter
- `PlanningModeServiceResumeTest` — resume-after-restart via Testcontainers PG
- `PlanningModeServiceCombineTest` — merges facts, creates links, refuses cross-user parents
- `PlanListBlockTest` — serialization round-trip
- `PlanPickerTest` (Go) — selection state machine, multi-select, filter cycling
- CLI integration — `/plans` → picker open → enter resume → planning UI state
- E2E — "jakie mam plany?" → agent tool call → picker auto-opens (requires test double or recording)

## Security

- Every `PlanController` endpoint checks `plan.user_id == authenticatedUserId`; mismatch → 403
- `combine_plans` tool validates ALL parent ids belong to same user before any DB write (transactional precondition)
- `plan_links` has no user_id column — enforced via FK to plans + join check in read queries
- No SQL injection surface — all parameterized via JdbcTemplate
- Audit: resume / combine / abandon actions emit INFO logs with user id + plan id for compliance trace
- Natural-language tools: same auth path as REST — `@Tool` methods read `CTX_USER_ID` from `ChatClientRequest.context()`, refuse if absent
- `PlanListBlock` sent over SSE contains only the caller's plans — serializer enforces user scoping on assembly, not only on read

## Effort

| Task | Traditional | AI-paired |
|---|---|---|
| V16 migration + backfill + test | 0.5 day | 20 min |
| `PlansRepository` extensions + tests | 0.5 day | 30 min |
| `PlanController` + auth + tests | 0.5 day | 30 min |
| `PlanListBlock` + serialization | 0.25 day | 15 min |
| `/plans` slash command | 0.25 day | 15 min |
| `PlanPicker` CLI component | 1 day | 50 min |
| `ctrl+p` rewire + picker auto-open | 0.5 day | 20 min |
| `PlanningModeService.resumeFromDb` | 0.75 day | 45 min |
| Phase + facts DB persistence in existing transitions | 0.5 day | 30 min |
| `PlanningModeService.combinePlans` + mergeFacts | 0.75 day | 45 min |
| `/plan resume` / `/plan combine` / `/plan new --parent` commands | 0.5 day | 30 min |
| `@Tool list_plans` / `resume_plan` / `combine_plans` | 0.75 day | 45 min |
| Persona tool registration | 0.25 day | 15 min |
| Tests (Java + Go) | 1.5 days | 90 min |
| **Total** | **~8.5 days** | **~7.5 h** |

## Dependencies

- **P13 Planning Discovery State (shipped)** — P21 extends the phase state machine with persistence. No conflict.
- **`kukuvaia.plans` table (V4, shipped)** — extended by V16, no breaking change.
- **`SessionPicker` (shipped)** — template for `PlanPicker`. Reused Bubbletea patterns.
- **`SessionContextAdvisor` (shipped)** — will continue to surface live plans; P21 enriches display using `name` column. Non-breaking.
- **`@Tool` infrastructure (shipped)** — standard wiring.

## Relationship to other plans

- **P13 (Planning Discovery State)** — P21 persists what P13 stores in memory. Direct extension, no conflict.
- **P15 Pillar 2 (Red-Team Verification + Auto-Correction)** — combined plans inherit red-team findings from parents via `plan_revisions` audit; auto-fix on combined plan gets new revision rows. Compose cleanly.
- **P17 (Commitments Memory)** — complementary surface. Plans = structured multi-step. Commitments = atomic. Both surfaced in `SessionContextAdvisor`. P21 does not change P17.
- **P19 (Complexity-Driven Routing)** — resumed plan's DRAFTING/APPROVAL phase triggers `STRATEGY` complexity via existing planning-mode signal. No change.
- **P20 (Routing Self-Tuning via Dreaming)** — plan lifecycle events become outcome signals for routing (e.g., user abandons plans routed to `worker` — that's a signal the worker tier under-served the task). Future integration.

## Open questions — need decision before implementation

1. **Fact merge strategy for `combinePlans`** — Naive concat+dedup (MVP) vs LLM-merge pass (higher quality, ~2s + ~500 tokens cost).
   **Proposed:** ship naive, add `kukuvaia.planning.combine.merge-strategy: naive|llm` config flag in Phase D. Revisit after real usage shows duplication pain.

2. **Resume semantics — same session or new?** — Plan A was created in session X. User is now in session Y. User does `/plan resume <A>`. Two options:
   - (a) Rehydrate A's planning into current session Y (decouple plan from original session).
   - (b) Refuse and show "plan belongs to session X — switch session first".
   **Proposed:** option (a) — plans are first-class objects, session is just where planning happened. Session-detach is the point of P21. `sessionId` column stays as "last edited from" for audit.

3. **Abandon from picker — confirm prompt?** — `d` key in picker marks plan abandoned via `/plan cancel`. Destructive, same as typing `/plan cancel`. Confirm prompt?
   **Proposed:** two-step delete — first `d` marks row red "press d again to confirm", second `d` within 3s abandons. Aligns with terminal-idiom two-key delete (vim `dd`).

## Implementation readiness

**Can it be implemented now? — Yes, with 3 caveats:**

1. **Open questions above need your sign-off** (fact merge strategy, resume cross-session semantics, abandon confirm). Without them I'd make pragmatic calls but they change user-facing behaviour.
2. **No blocking dependencies** — all infrastructure (V4 plans table, SessionPicker, @Tool, PlanningModeService, SessionContextAdvisor) already shipped.
3. **Linear phases A→B→C→D** — no phase blocks the next in unexpected ways. Phase A is pure foundation; B is pure CLI; C touches service layer but independently of D.

**Risk inventory:**
- Fact merge naive approach may produce noisy DISCOVERY for combined plans with overlapping facts. Fallback: add LLM merge later. Low.
- Phase persistence in existing `PlanningModeService` transitions — needs careful addition to every `advanceToX` method. Medium complexity, high test coverage required. Mitigated by integration test with real PG.
- Picker auto-open on `PlanListBlock` from free-form agent response — need to ensure it doesn't steal focus during unrelated conversation. Mitigated by user having to explicitly request plans.
- Cross-session resume changes plan ownership model slightly — if user expects plan-per-session, this may surprise. Mitigate with UI copy: "Resumed from session 'crete-planning'".
