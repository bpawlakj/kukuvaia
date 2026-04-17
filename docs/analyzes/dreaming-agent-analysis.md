# Analysis: Dreaming Agent — Autonomous Self-Improvement System

**Created**: 2026-04-12
**Status**: Concept analysis — not yet planned for implementation
**Prerequisites**: DB-driven provider registry (Phase 1-3), Embabel multi-model routing (Phase 4-6), observability layer

## Concept

A scheduled autonomous agent ("dreaming") that performs background self-inspection, optimization, and proactive improvement. Kukuvaia periodically reviews its own state — memory, configuration, logs, connected services — and produces actionable recommendations.

The metaphor is deliberate: biological dreaming consolidates memories, detects patterns, and prepares the organism for future challenges. Kukuvaia's dreaming does the same for its operational state.

## Motivation

Current AI agent platforms are **reactive** — they respond to user queries but never learn from their own operational patterns. Kukuvaia's dreaming introduces a **proactive self-improvement loop**:

- Detect degraded performance before users notice
- Discover new models and suggest upgrades
- Consolidate memory (merge, decay, resolve contradictions)
- Monitor connected services for anomalies
- Propose architectural improvements based on usage patterns

No existing agent platform offers this capability.

## Architecture

### Dream Scheduler

```
┌─────────────────────────────────────────────────────┐
│                  Dream Scheduler                     │
│  Cron-based, respects daemon token budget            │
│  Skip-if-running guard (AtomicBoolean)               │
│  Produces DreamReport per run                        │
└──────┬──────┬──────┬──────┬──────┬──────┬───────────┘
       │      │      │      │      │      │
       ▼      ▼      ▼      ▼      ▼      ▼
   ┌──────┐┌──────┐┌──────┐┌──────┐┌──────┐┌──────────┐
   │Memory││Health││Model ││Cross-││Log   ││Config    │
   │Cons. ││Check ││Scout ││Svc   ││Anal. ││Audit     │
   └──────┘└──────┘└──────┘└──────┘└──────┘└──────────┘
   nightly  hourly  daily   daily   hourly  on-change
```

Each dream task is an independent, pluggable module with its own schedule, model tier, and output format. Tasks are Embabel GOAP agents — each one has a goal, actions, and typed state on the blackboard.

### Core Principle: Propose, Never Act

The dreaming agent NEVER automatically:
- Changes models or provider configuration
- Modifies or deletes memories
- Alters anything in connected external services
- Applies fixes without user approval

Instead, it produces a `DreamReport` with ranked recommendations. The user (or future admin UI) reviews and approves/rejects each recommendation. This is critical for trust and safety.

## Dream Tasks

### Task 1: Memory Consolidation

**Schedule**: Nightly (1x per day)
**Model tier**: Worker (Haiku) — cheap bulk processing
**LLM required**: Yes

**What it does**:
- Review episodic memories older than configurable threshold (default 90 days)
- Identify candidates for merge into semantic memory (repeated facts → single entry)
- Detect contradictions in procedural memory (conflicting rules)
- Calculate decay scores based on access count, age, relevance
- Propose consolidation actions (merge N entries → 1, archive M, delete K)

**Output**: `MemoryConsolidationReport`
```
- 23 episodic memories eligible for decay (0 access, >90 days)
- 5 semantic entries with contradictions detected
- 3 procedural rules that overlap — suggest merge
- Estimated memory reduction: 15%
```

**Connects to**: kukuvaia-memory module (SmartMemoryRepository, pgvector)

### Task 2: Health Check

**Schedule**: Hourly
**Model tier**: None — fully deterministic, no LLM calls
**LLM required**: No

**What it does**:
- Ping each registered provider — verify API key validity, measure latency
- Check model availability per provider (quick `/v1/models` call)
- Verify database connectivity and migration status
- Check embedding service health (ONNX model loaded, vector index stats)
- Monitor token budget consumption rate (daemon budget)

**Output**: `HealthReport`
```
- SmartGate: healthy (latency: 120ms, 4 models available)
- OpenRouter: degraded (latency: 890ms, timeout on opus)
- PostgreSQL: healthy (V6 migration applied, 12.3k memories)
- Embedding: healthy (all-MiniLM-L6-v2 loaded, 384 dimensions)
- Token budget: 34% consumed (34,000 / 100,000 daily)
```

**API endpoint**: `GET /api/health/dream` — exposes latest health report

**Connects to**: Provider registry (ChatModelCache), database, embedding service

### Task 3: Model Scout

**Schedule**: Daily or weekly (configurable)
**Model tier**: Worker (Haiku) for filtering, Supervisor (Sonnet) for comparative analysis
**LLM required**: Yes

**What it does**:
- Sync models from all registered providers (`/v1/models` endpoint)
- Query HuggingFace API for new/trending models in relevant categories (text-generation, code, embeddings)
- Compare discovered models with current role assignments:
  - Is there a cheaper model with similar quality for the "worker" role?
  - Is there a faster model for "intent classification"?
  - Has a new model version been released for current models?
- Check provider support — can a discovered model be added to an existing provider?
- Score recommendations by confidence (benchmark data availability, community adoption)

**Output**: `ModelScoutReport`
```
- Claude Haiku 4.6 detected on SmartGate — 15% faster, same cost tier
  Recommendation: upgrade worker role (confidence: 0.85)
- New embedding model: nomic-embed-text-v2 (768 dims, 8192 context)
  Note: requires migration from 384 → 768 dimensions (breaking change)
  Recommendation: evaluate in test environment (confidence: 0.60)
- OpenRouter added DeepSeek V3 — premium tier, 128k context
  Recommendation: register as alternative advisor (confidence: 0.45)
```

**External dependencies**: HuggingFace API (paper_search, hub_repo_search), provider `/v1/models` endpoints

**Connects to**: Provider registry (ModelDiscoveryClient, ModelRepository), HuggingFace MCP tools

### Task 4: Cross-Service Inspection

**Schedule**: Daily
**Model tier**: Supervisor (Sonnet) — requires reasoning over multi-source data
**LLM required**: Yes

**What it does**:
- For each connected MCP server: health check, schema inspection
- For sl-content (if connected): 
  - Check embedding pipeline status — are all documents embedded?
  - Detect images without embeddings and diagnose why (format, size, missing metadata)
  - Verify content freshness (last sync timestamp vs expected update frequency)
- For other connected services:
  - Verify tool availability (are all expected tools present?)
  - Check for schema changes since last inspection
  - Monitor error rates in tool call logs

**Output**: `CrossServiceReport`
```
- sl-content MCP: connected, 12 tools available
  - 47 images without embeddings detected
    Root cause: PNG files >5MB skipped by embedding pipeline (size limit)
    Recommendation: increase limit or add image resizing pre-processor
  - Last content sync: 2h ago (within expected range)
- etsl MCP: connected, 8 tools available, no issues
```

**Guardrail**: STRICT read-only. Never modifies external services. Only reads via MCP tool calls.

**Connects to**: MCP Client (external servers), tool registry

### Task 5: Log Analysis

**Schedule**: Hourly (lightweight) + daily (deep analysis)
**Model tier**: Worker (Haiku) for pattern extraction, Supervisor (Sonnet) if anomaly detected
**LLM required**: Yes (for pattern analysis)

**What it does**:
- Analyze ProviderAuditLog entries since last run:
  - Token usage trends per model — spikes, unusual patterns
  - Error rate per provider — timeout frequency, 429 rate limits
  - Latency percentiles — p50, p95, p99 per model
  - Tool call success/failure rates — which tools fail most?
- Detect anomalies:
  - Model X timeout rate increased 300% in last hour
  - Tool Y has 40% failure rate (was 5% yesterday)
  - Token usage 3x higher than usual for this time of day
- Correlate patterns across dimensions (model + tool + time)

**Output**: `LogAnalysisReport`
```
- Anomaly: opus timeout rate 45% (last hour) vs 3% (baseline)
  Likely cause: provider degradation
  Recommendation: temporarily route advisor role to sonnet
- Pattern: exercise_generator uses 3x more tokens than other tools
  Context: normal — exercise generation requires longer prompts
  Recommendation: no action (informational)
- Trend: haiku latency increasing 5ms/day over last week
  Recommendation: monitor, no action yet
```

**Connects to**: ProviderAuditLog, database (aggregation queries)

### Task 6: Config Audit

**Schedule**: On-change (triggered after provider/model/role CRUD) + daily sweep
**Model tier**: None — fully deterministic
**LLM required**: No

**What it does**:
- Verify role assignment consistency:
  - No orphaned roles (role points to disabled/deleted model)
  - All critical roles assigned (default, advisor, supervisor, worker)
  - No circular dependencies in model routing
- Verify provider consistency:
  - All `api_key_ref` env vars exist and are non-empty
  - Base URLs are reachable (quick HEAD request)
  - No providers with 0 enabled models
- Verify memory configuration:
  - pgvector index healthy
  - Embedding dimension matches model output
  - Memory table size within expected bounds

**Output**: `ConfigAuditReport`
```
- WARNING: role "advisor" points to model (uuid) which is disabled
  Recommendation: reassign advisor role or re-enable model
- OK: all 3 providers have valid API key references
- OK: pgvector index (384 dims) matches embedding model output
```

**Connects to**: Provider registry, model roles, database schema

## Embabel Implementation

Dreaming tasks map naturally to Embabel GOAP agents. Each dream task is an `@Agent` with `@Action` methods that use different model tiers:

```kotlin
@Agent(description = "Nightly dreaming — memory consolidation and system optimization")
class DreamingAgent(
    private val memoryRepository: SmartMemoryRepository,
    private val providerRegistry: ProviderRegistryService,
    private val chatModelCache: ChatModelCache,
) {
    @Action(description = "Consolidate memories — merge, decay, resolve contradictions")
    fun consolidateMemory(trigger: DreamTrigger, ctx: ProcessContext): MemoryReport =
        ctx.ai()
            .withLlmByRole("worker")  // Haiku — cheap bulk work
            .creating(MemoryReport::class.java)
            .create("Review and consolidate these memories: ${trigger.staleMemories}")

    @Action(description = "Check system health — providers, DB, embeddings")
    fun checkHealth(trigger: DreamTrigger, ctx: ProcessContext): HealthReport {
        // Deterministic — no LLM needed
        return HealthReport(
            providers = providerRegistry.checkAllProviders(),
            database = checkDatabaseHealth(),
            embeddings = checkEmbeddingHealth(),
            tokenBudget = getDaemonBudgetStatus()
        )
    }

    @Action(description = "Scout for new and upgraded models")
    fun scoutModels(health: HealthReport, ctx: ProcessContext): ModelScoutReport =
        ctx.ai()
            .withLlmByRole("supervisor")  // Sonnet — needs comparative reasoning
            .creating(ModelScoutReport::class.java)
            .create("Compare available models with current setup: ${health.currentModels}")

    @AchievesGoal(description = "Dream report compiled with actionable recommendations")
    @Action(description = "Compile recommendations from all dream task outputs")
    fun compileDreamReport(
        memory: MemoryReport,
        health: HealthReport,
        scout: ModelScoutReport,
        ctx: ProcessContext
    ): DreamReport =
        ctx.ai()
            .withLlmByRole("supervisor")
            .creating(DreamReport::class.java)
            .create("""
                Compile actionable recommendations from:
                Memory: ${memory.summary}
                Health: ${health.summary}
                Models: ${scout.summary}
                Prioritize by impact and confidence.
            """)
}
```

GOAP discovers the optimal execution plan:
```
DreamTrigger
  → consolidateMemory(worker/Haiku) → MemoryReport
  → checkHealth(deterministic)       → HealthReport
  → scoutModels(supervisor/Sonnet)   → ModelScoutReport
  → compileDreamReport(supervisor)   → DreamReport (goal achieved)
```

Note: `consolidateMemory` and `checkHealth` can run in parallel (no dependency). `scoutModels` depends on `HealthReport` (needs current model list). `compileDreamReport` depends on all three.

## DreamReport Schema

```java
public record DreamReport(
    Instant timestamp,
    DreamRunMetadata metadata,          // duration, token cost, models used
    List<Recommendation> recommendations,
    HealthReport healthSnapshot,
    Summary summary
) {}

public record Recommendation(
    String id,                          // UUID for tracking acceptance
    RecommendationType type,            // MODEL_UPGRADE, MEMORY_CLEANUP, CONFIG_FIX, etc.
    Priority priority,                  // CRITICAL, HIGH, MEDIUM, LOW, INFORMATIONAL
    String description,                 // Human-readable explanation
    String suggestedAction,             // What to do (API call, config change, etc.)
    double confidence,                  // 0.0-1.0 — how certain is the recommendation
    Map<String, Object> evidence        // Supporting data (metrics, comparisons, etc.)
) {}

public enum RecommendationType {
    MODEL_UPGRADE,
    MODEL_DOWNGRADE,        // cheaper model works equally well
    MEMORY_CLEANUP,
    MEMORY_CONTRADICTION,
    PROVIDER_DEGRADATION,
    PROVIDER_NEW,
    CONFIG_INCONSISTENCY,
    CROSS_SERVICE_ISSUE,
    PERFORMANCE_ANOMALY,
    COST_OPTIMIZATION,
    SECURITY_CONCERN
}
```

## API Endpoints

```
GET  /api/dream/reports              — list dream reports (paginated, newest first)
GET  /api/dream/reports/{id}         — get specific dream report
GET  /api/dream/reports/latest       — get most recent report
POST /api/dream/trigger              — manually trigger a dream run
GET  /api/dream/schedule             — get current dream schedule config
PUT  /api/dream/schedule             — update dream schedule

POST /api/dream/recommendations/{id}/accept   — accept recommendation (triggers action)
POST /api/dream/recommendations/{id}/reject   — reject recommendation (with reason)
GET  /api/dream/recommendations/pending       — list pending recommendations
```

## Database Schema

```sql
CREATE TABLE kukuvaia.dream_reports (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    started_at      TIMESTAMP NOT NULL,
    completed_at    TIMESTAMP,
    status          VARCHAR(20) DEFAULT 'running'
                    CHECK (status IN ('running','completed','failed','cancelled')),
    token_cost      INT,
    models_used     JSONB DEFAULT '[]',
    health_snapshot JSONB,
    summary         TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE TABLE kukuvaia.dream_recommendations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    report_id       UUID NOT NULL REFERENCES kukuvaia.dream_reports(id) ON DELETE CASCADE,
    type            VARCHAR(50) NOT NULL,
    priority        VARCHAR(20) NOT NULL,
    description     TEXT NOT NULL,
    suggested_action TEXT,
    confidence      DECIMAL(3,2),
    evidence        JSONB DEFAULT '{}',
    status          VARCHAR(20) DEFAULT 'pending'
                    CHECK (status IN ('pending','accepted','rejected','expired')),
    resolved_at     TIMESTAMP,
    resolved_by     VARCHAR(255),
    rejection_reason TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_dream_reports_status ON kukuvaia.dream_reports(status, started_at DESC);
CREATE INDEX idx_dream_recs_pending ON kukuvaia.dream_recommendations(status) 
    WHERE status = 'pending';
```

## Cost Estimation

| Dream Task | Model | Frequency | Est. tokens/run | Monthly cost (SmartGate) |
|-----------|-------|-----------|-----------------|--------------------------|
| Memory Consolidation | Haiku | 1x/day | ~5,000 | Low |
| Health Check | None (deterministic) | 1x/hour | 0 | Free |
| Model Scout | Haiku + Sonnet | 1x/day | ~8,000 | Low-Medium |
| Cross-Service | Sonnet | 1x/day | ~10,000 | Medium |
| Log Analysis | Haiku + Sonnet (on anomaly) | 1x/hour light, 1x/day deep | ~3,000-15,000 | Low-Medium |
| Config Audit | None (deterministic) | On-change | 0 | Free |
| Report Compilation | Sonnet | 1x/day | ~3,000 | Low |

**Estimated daily total**: ~30,000-45,000 tokens (well within 100k daemon budget)

## Risks and Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| LLM hallucination in recommendations | High | Confidence scoring, evidence requirement, human approval gate |
| Cost overrun from frequent LLM calls | Medium | Daemon token budget, deterministic tasks where possible, Haiku for bulk work |
| Scope creep — dreaming tries to do too much | Medium | Pluggable task architecture, each task independently schedulable/disableable |
| False anomaly detection creating noise | Medium | Configurable thresholds, anomaly baseline calibration period, "informational" priority tier |
| Security — reading sensitive logs/configs | Medium | Read-only principle, no secret values in reports, sanitized evidence |
| External service inspection overreach | Low | MCP read-only tools only, rate limiting on external calls |

## Implementation Phases

### Phase A: Foundation (after model routing plan completes)
- Dream scheduler infrastructure (cron, skip-if-running, token budget integration)
- DreamReport schema and API (reports, recommendations CRUD)
- Health Check task (deterministic, no LLM — quick win)
- Config Audit task (deterministic — immediate value)

### Phase B: LLM-Powered Tasks
- Memory Consolidation (Haiku — connects to existing memory module)
- Log Analysis (Haiku for extraction, Sonnet for anomaly reasoning)

### Phase C: External Intelligence
- Model Scout (HuggingFace API, provider model sync)
- Cross-Service Inspection (MCP client health checks)

### Phase D: Observability Layer
- Metrics collection infrastructure (latency, error rates, token usage over time)
- Baseline calibration (learn normal patterns before detecting anomalies)
- Dashboard endpoint for admin UI

### Phase E: Advanced
- Recommendation auto-execution with approval workflow
- Learning from accepted/rejected recommendations (feedback loop)
- Custom dream tasks via `.kukuvaia/dreams/` extension directory

## Relationship to Existing Architecture

```
Provider Registry (Phase 1-3)
  └── Health Check reads provider status
  └── Model Scout uses model discovery + sync
  └── Config Audit validates role assignments

Embabel + Multi-Model (Phase 4-6)
  └── Dream tasks are Embabel GOAP agents
  └── Per-task model selection (worker/supervisor/advisor)

Memory Module (existing)
  └── Memory Consolidation reads/proposes changes
  └── DreamReport stored as procedural memory

Daemon System (existing)
  └── Token budget shared with dreaming
  └── Skip-if-running guard reused
  └── Cron scheduling reused
```

## Comparison with Industry

| Platform | Background optimization | Self-improvement |
|----------|----------------------|------------------|
| **ChatGPT** | Memory consolidation (basic) | None |
| **Claude** | None | None |
| **LangChain** | None | None |
| **AutoGPT** | Task loop (not self-reflective) | None |
| **kukuvaia (dreaming)** | Full system introspection + cross-service | Propose model upgrades, memory optimization, config fixes |

Dreaming would be a differentiating feature — no current platform performs autonomous self-optimization at this level.
