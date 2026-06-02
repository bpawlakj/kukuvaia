# P12: Agent Reflection — Dreaming Service & Skill Library

**Created**: 2026-04-16
**Status**: Draft
**Module**: kukuvaia-memory (extension)
**Depends on**: P04 (kukuvaia-memory foundation, pgvector infrastructure, SmartMemoryAdvisor)
**Milestone**: 3C (Intelligence Layer)
**Effort**: L (Phase 1: days, Phases 2–3: weeks, Phase 4: optional)

---

## Problem

Kukuvaia accumulates a growing corpus of work artifacts: implementation plans (P01–P11+), architecture decisions, bug-fix logs, conversation history, retrospectives. Each artifact contains reusable patterns — recurring approaches, trade-offs, lessons — but these patterns remain locked inside individual documents.

When the agent tackles a new task it starts fresh. It has no mechanism to recognize *"this planning task resembles P04 and P07 — apply the pattern refined there"* or *"this bug has the same shape as one from conversation X — here's what worked."*

The agent needs a **meta-cognitive layer**: an offline process that reads the corpus, extracts reusable patterns, stores them in a retrievable skill library, and surfaces them when relevant to new tasks. This is what **Voyager** (NVIDIA, 2023) and **Generative Agents** (Park et al., 2023) demonstrated in their domains — applied here to a software-engineering agent.

## Why This Matters

| Without P12 | With P12 |
|-------------|----------|
| Each task reasoned from scratch | Task benefits from distilled prior experience |
| Lessons in `tasks/lessons.md` manually curated | Patterns auto-extracted and auto-retrieved |
| Human re-reads old docs for context | Agent retrieves relevant patterns automatically |
| Same mistakes repeated across sessions | Reflexion loop prevents repetition |
| Knowledge scales linearly with user effort | Knowledge compounds via dreaming |

## Current State

| Component | Status | Notes |
|-----------|--------|-------|
| `kukuvaia-memory` module | Planned (P04) | 3 memory types, pgvector, JSONB conversations |
| Semantic memory storage | Planned (P04) | Facts/patterns from conversations |
| Scheduled jobs | Unused | Spring `@Scheduled` available but unused |
| Corpus | Rich | 11+ plans, architecture docs, retrospectives, `tasks/lessons.md` |
| Skill library | None | No structured pattern store |
| Feedback loop | None | Task outcomes not tracked or fed back |

## Research Grounding

This plan draws from four production-validated architectures:

### Voyager (Wang et al., NVIDIA, 2023)
Minecraft agent building an ever-growing skill library. Mechanisms adopted:
- **Skill library** — skills indexed by embedding, retrieved by task similarity
- **Iterative self-verification** — LLM critic rejects low-quality skills before storage
- **Automatic curriculum** — agent proposes what to learn next based on gaps

### Generative Agents (Park et al., Stanford, 2023)
Persistent-memory simulation agents. Mechanisms adopted:
- **Reflection tree** — observations → *"what high-level questions can we ask?"* → reflections → meta-reflections
- Hierarchical abstraction from concrete events to reusable principles

### Reflexion (Shinn et al., 2023)
Self-critique loop. Mechanisms adopted:
- **Textual self-reflection after task completion** — what worked / what didn't
- Feedback online (after each task), not only offline (nightly dreaming)

### MemGPT (Packer et al., 2023)
OS-inspired hierarchical memory. Mechanisms adopted:
- **Explicit memory interface** — agent calls tools to query/insert patterns
- Function-calling over memory store, pagination of large result sets

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  kukuvaia-memory                         │
│                                                          │
│  ┌──────────────────┐      ┌──────────────────────┐     │
│  │  Episodic memory │      │   Skill Library      │     │
│  │  (what happened) │      │   (what to apply)    │     │
│  └──────────────────┘      │                      │     │
│  ┌──────────────────┐      │  - name              │     │
│  │  Semantic memory │ ───► │  - when_to_apply     │     │
│  │  (facts/patterns)│      │  - success_rate      │     │
│  └──────────────────┘      │  - embedding         │     │
│  ┌──────────────────┐      │  - source_docs       │     │
│  │Procedural memory │      │  - times_applied     │     │
│  │ (how to do)      │      └──────────────────────┘     │
│  └──────────────────┘               ▲                    │
│                                     │                    │
│  ┌──────────────────────────────────┴──────────────┐    │
│  │          DreamingService (@Scheduled)           │    │
│  │                                                 │    │
│  │  1. Collect corpus (plans/, convs, memories)   │    │
│  │  2. Cluster thematically (embeddings + LLM)    │    │
│  │  3. Extract candidate patterns per cluster     │    │
│  │  4. Self-verify (critic LLM)                   │    │
│  │  5. Store verified skills + export to MD       │    │
│  │  6. Propose curriculum gaps                    │    │
│  └────────────────────────────────────────────────┘     │
└──────────────────────────┬──────────────────────────────┘
                           │
                           │ SmartMemoryAdvisor enriches
                           │ system prompt with top-K
                           │ relevant skills for current task
                           ▼
                  ┌─────────────────┐
                  │   ChatClient    │
                  │   + Advisors    │
                  └────────┬────────┘
                           │
                           ▼
                  ┌─────────────────────────┐
                  │    Task execution       │
                  └────────┬────────────────┘
                           │
                           │ After task:
                           │ - Reflexion (what worked)
                           │ - Update success_rate
                           ▼
                    Feedback loop closed
```

## Design Principles

1. **Don't invent a new module** — extend `kukuvaia-memory`. Skills are a specialized semantic memory.
2. **Human-inspectable** — every skill also exported to `docs/knowledge/skills/<category>/<name>.md`. If agent "learns" something wrong, dev sees it.
3. **Feedback loop is the product** — a library that only grows (never validates) is just summarization. Success-rate tracking is non-optional.
4. **Small, composable phases** — Phase 1 delivers retrieval value alone (even with seeded skills). Phase 2 automates population. Phase 3 closes the loop.
5. **Token budget aware** — reuse daemon token-budget advisor from P03; dreaming must never exceed configured cap.

---

## Implementation

### Phase 1 — Skill Library Foundation (Effort: M, days)

**Goal**: Storage and retrieval for patterns. No extraction yet — patterns seeded manually to validate retrieval path.

#### Step 1: Database schema

**Migration**: `kukuvaia-memory/src/main/resources/db/migration/V5__skill_library.sql`

```sql
CREATE TABLE skill_library (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(128) UNIQUE NOT NULL,
    description     TEXT NOT NULL,
    when_to_apply   TEXT NOT NULL,
    content         TEXT NOT NULL,
    category        VARCHAR(64) NOT NULL,   -- planning|coding|debugging|communication|architecture|principle
    source_docs     TEXT[] NOT NULL,         -- ['P04', 'P07', 'conv:abc123']
    embedding       VECTOR(1536),
    confidence      REAL NOT NULL DEFAULT 0.5,
    times_applied   INTEGER NOT NULL DEFAULT 0,
    success_count   INTEGER NOT NULL DEFAULT 0,
    failure_count   INTEGER NOT NULL DEFAULT 0,
    archived        BOOLEAN NOT NULL DEFAULT false,
    archived_reason TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX skill_library_embedding_idx ON skill_library USING hnsw (embedding vector_cosine_ops);
CREATE INDEX skill_library_category_idx  ON skill_library (category) WHERE archived = false;
```

#### Step 2: Domain model

**File**: `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/skill/Skill.java`

Java `record` mirroring schema. Derived method `successRate()` returns `success_count / max(times_applied, 1)`.

#### Step 3: Repository

**File**: `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/skill/SkillRepository.java`

Methods:
- `save(Skill)` — upsert by `name`
- `findSimilar(float[] embedding, int topK, Optional<Category> filter)` — pgvector cosine, archived excluded
- `markApplied(UUID id, boolean success)` — atomic counter update
- `archive(UUID id, String reason)` — soft delete
- `findAll(boolean includeArchived)` — for MD export reconciliation

#### Step 4: `@McpTool` interface

**File**: `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/skill/SkillTools.java`

- `retrieve_skills(task_description, category?, top_k=5)` — agent-facing retrieval
- `record_skill_outcome(skill_id, success, notes)` — agent-facing feedback

#### Step 5: Seed patterns

**Dir**: `docs/knowledge/skills/seed/`

5–10 manually authored MD files with YAML frontmatter covering known good patterns (e.g. *"always diagnose root cause before retry"*, *"parameterized queries for any DB tool"*). Loader ingests these on app startup if library is empty.

#### Acceptance criteria
- [ ] Migration applies cleanly against dev DB
- [ ] Skills stored and retrieved by embedding similarity
- [ ] `retrieve_skills` returns relevant seeded skills for smoke-test queries
- [ ] Success/failure counters update atomically under concurrent calls
- [ ] MD seed loader idempotent (re-running doesn't duplicate)

---

### Phase 2 — DreamingService (Effort: L, week+)

**Goal**: Offline scheduled process reads corpus, extracts patterns, verifies them, populates skill library.

#### Step 1: Corpus collector

**File**: `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/dream/CorpusCollector.java`

Sources:
- `docs/work/*/plan.md` — implementation plans
- `docs/architecture/*.md` — architecture decisions
- `tasks/lessons.md` — manual lessons (high-confidence seed material)
- Recent conversations from `SPRING_AI_CHAT_MEMORY` (last N days)
- Existing semantic memories

Returns `List<CorpusItem>` with `id`, `type`, `content`, `createdAt`, `embedding`.

#### Step 2: Thematic clustering

**File**: `dream/ThematicClusterer.java`

Primary: HDBSCAN over embeddings (min_cluster_size ~3). Fallback: agglomerative with cosine distance. Tiny clusters (<2 members) skipped — insufficient evidence.

Output: `List<Cluster>` with member items + centroid.

#### Step 3: Pattern extraction per cluster

**File**: `dream/PatternExtractor.java`

Per cluster:
1. Concatenate members (truncate to model context)
2. LLM prompt: *"Extract 1–3 reusable patterns. For each: name, when_to_apply, body, evidence_from_sources."*
3. Parse via Spring AI `BeanOutputConverter<List<CandidateSkill>>`

#### Step 4: Self-verification — Voyager pattern

**File**: `dream/SkillCritic.java`

For each candidate, independent LLM critic evaluates:
- **Reusability** — general enough to apply in new situations?
- **Specificity** — concrete enough to be actionable?
- **Novelty** — duplicates existing skill? (query top-3 similar from library)
- **Evidence** — source docs actually demonstrate this?

Returns `VerificationResult(accepted, confidence 0–1, reason)`. Rejected candidates logged with reason for human review.

#### Step 5: Persistence + MD export

Accepted skills:
- Written to `skill_library` with confidence from critic
- Exported to `docs/knowledge/skills/<category>/<slug>.md` with YAML frontmatter

MD export makes every skill **human-inspectable** — dev reads what agent "learned" and spots hallucinated or harmful patterns.

#### Step 6: Scheduled job

**File**: `dream/DreamingService.java`

```java
@Scheduled(cron = "${kukuvaia.dream.cron:0 0 3 * * *}")  // 3 AM daily
public void dream() { ... }
```

Reuse daemon token-budget advisor from P03 to cap cost. Run aborts gracefully at budget limit and logs partial progress.

#### Step 7: `SmartMemoryAdvisor` integration

Extend existing advisor (from P04):
1. Embed current user message
2. Query `skill_library` for top-K (default 3) relevant skills
3. Inject into system prompt under `## Relevant patterns from prior work`
4. Track which skills were injected (for Phase 3 feedback)

#### Acceptance criteria
- [ ] Dream runs on schedule when `kukuvaia.dream.enabled=true`
- [ ] Clustering produces coherent themes over seed corpus (human spot-check)
- [ ] Critic rejects ≥20 % of candidates (otherwise critic prompt too lenient — tune)
- [ ] MD export matches DB state after each run
- [ ] Agent references injected skills in test conversations
- [ ] Dream respects token budget (fails gracefully at limit)
- [ ] Full dream cycle under token cap for 100-doc corpus

---

### Phase 3 — Feedback Loop (Reflexion) (Effort: M, days)

**Goal**: Close the loop. Track whether applied skills actually helped, update success rates, archive failing skills.

#### Step 1: Skill outcome tracking

**File**: `advisor/SkillOutcomeAdvisor.java`

At conversation boundary (session end, explicit `/done`, or idle timeout):
1. List skills injected during this conversation (from advisor state)
2. LLM judge evaluates per skill: `helped / unused / misled`
3. Call `record_skill_outcome` for each

Use a **different model** for the judge than for execution to reduce self-congratulation bias.

#### Step 2: Reflexion — textual self-reflection

After task completion, LLM generates reflection:
> *"In this task, pattern `X` helped because… Pattern `Y` was retrieved but didn't fit because… Next time for similar task, try…"*

Stored as new `semantic_memory` with `type=reflection` and references to skills used. Becomes corpus input for next dream cycle — reflections feed back into extraction.

#### Step 3: Archival policy

Weekly scheduled job:
- `times_applied ≥ 10` AND `success_rate < 0.3` → archive (reason: *"low success rate"*)
- `times_applied = 0` AND `created_at < now() − 60 days` → archive (reason: *"unused"*)
- Archived skills excluded from retrieval, retained for audit

#### Acceptance criteria
- [ ] Outcome recorded after every applicable conversation
- [ ] `success_rate` reflects actual task outcomes over time
- [ ] Reflections stored as semantic memories and appear in next dream
- [ ] Archival runs weekly, logs archived skills with reasons
- [ ] Archived skills don't leak into retrieval results

---

### Phase 4 — Curriculum & Reflection Tree (Effort: M, optional)

**Goal**: Agent proposes what to learn next (Voyager curriculum) and builds hierarchical insights (Generative Agents reflection tree).

#### Step 1: Gap analysis

Monthly job scans skill library + recent conversations:
- Which categories under-represented?
- Which conversation themes recur without matching skills?

Produces **curriculum list** for next dream cycle.

#### Step 2: Focused dreaming

Dream accepts `--focus=<category>` arg. CLI/API can trigger on-demand focused dreams.

#### Step 3: Meta-reflection

Every N dream cycles, meta-pass:
1. Query top skills by `success_rate`
2. LLM prompt: *"What higher-level principle connects these successful patterns?"*
3. Store as `category=principle` skill linking to child skills

Creates hierarchy: concrete pattern → principle → meta-principle.

#### Acceptance criteria
- [ ] Gap analysis produces actionable curriculum list
- [ ] Focused dreams improve targeted-category coverage (measurable via retrieval recall on held-out tasks)
- [ ] Meta-reflections link back to constituent skills

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `kukuvaia.dream.enabled` | `false` | Enable DreamingService |
| `kukuvaia.dream.cron` | `0 0 3 * * *` | Schedule (daily 3 AM) |
| `kukuvaia.dream.corpus.lookback-days` | `30` | How far back to collect conversations |
| `kukuvaia.dream.corpus.include-plans` | `true` | Include `docs/work/*/plan.md` |
| `kukuvaia.dream.corpus.include-lessons` | `true` | Include `tasks/lessons.md` |
| `kukuvaia.dream.max-skills-per-run` | `20` | Cap new skills per dream |
| `kukuvaia.dream.token-budget` | `50000` | Max tokens per dream run |
| `kukuvaia.dream.critic.min-confidence` | `0.7` | Reject candidates below this |
| `kukuvaia.dream.archival.min-applications` | `10` | Min applications before archival check |
| `kukuvaia.dream.archival.min-success-rate` | `0.3` | Below this → archive |
| `kukuvaia.dream.retrieval.top-k` | `3` | Skills injected per conversation turn |
| `kukuvaia.dream.retrieval.min-similarity` | `0.65` | Cosine threshold to inject |

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Critic too lenient → junk fills library | Retrieval noise, prompt bloat | Min-confidence threshold; human review of first 100 skills |
| Critic too strict → empty library | No value delivered | Track acceptance rate; tune on seed corpus |
| LLM hallucinates patterns absent from source | False "learned" knowledge | Critic must cite evidence from source docs |
| Clustering produces noise | Incoherent themes → bad patterns | HDBSCAN with min_cluster_size; drop small clusters |
| Dream costs tokens nightly | Surprise bill | Reuse P03 token-budget advisor; daily cap |
| Retrieved skills pollute context | Prompt bloat, irrelevant retrieval | Small top-K (3), min-similarity threshold |
| Judge biased → inflated success rates | Archival never triggers | Different model for judge than execution; log judge inter-rater variance |
| Archival removes still-useful skills | Knowledge loss | Soft delete; weekly human-reviewable archive log |
| Privacy — conversations in dream corpus | Sensitive content embedded | Config flag to exclude personas marked sensitive; redaction pass before extraction |

## Open Questions

1. **Local model or production LLM for dreaming?**
   Local (Ollama) = free, lower quality. Production = better patterns, recurring cost.
   *Leaning*: production LLM for extraction + verification, local embeddings.

2. **Skills shared across users or private per-deployment?**
   Shared compounds community knowledge; private keeps data isolated.
   *Leaning*: private by default; optional Git-tracked export/import for intentional sharing.

3. **How does this interact with `tasks/lessons.md`?**
   Duplicate or complement?
   *Leaning*: ingest `lessons.md` as high-confidence seed; never overwrite manual edits.

4. **Should the critic see other existing skills?**
   Yes prevents near-duplicates but grows critic prompt.
   *Leaning*: pass top-3 most similar existing skills to critic for novelty check.

5. **Should archived skills be physically deleted after N months?**
   Soft delete vs hard delete trade-off on DB growth.
   *Leaning*: hard delete after 12 months, export to `docs/knowledge/archive/` first.

## Future Considerations

- **Cross-project transfer** — skills shareable between kukuvaia instances via Git-tracked `docs/knowledge/`
- **Skill composition** — agent combines multiple skills (Voyager composition pattern)
- **Active learning** — agent identifies weakest category and proposes dedicated tasks to generate learning signal
- **Explainability** — every retrieval logged with *"why this skill was chosen"* (cosine score + critic rationale); debuggable retrieval
- **Constitutional layer** — skills critiqued against project principles (`CLAUDE.md`, standards); ensures learned patterns align with team values
- **Skill provenance UI** — CLI/web view of skill library with source-doc links, success-rate histogram, application timeline
