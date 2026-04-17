# Analysis: Task Decomposition with Cost-Optimized Model Assignment

**Created**: 2026-04-13
**Status**: Concept analysis
**Prerequisites**: DB-driven provider registry (Phase 1-3), Embabel activation (Phase 4), model routing (Phase 5)

## Problem

Current plan (`model-routing-and-embabel.md`) handles **routing** — which model for which type of request. But it treats each request as atomic. Complex tasks contain subtasks of varying complexity, where using Opus for everything wastes tokens, and using Haiku for everything fails on hard parts.

```
Current:    User → [classify whole request] → pick ONE model → execute everything
Missing:    User → [decompose into steps] → [assign optimal model per step] → execute → merge
```

No production solution exists that combines automatic task decomposition with per-subtask model assignment.

## Market Analysis

| Approach | Who | How | Limitations |
|----------|-----|-----|-------------|
| **Cascading** | Martian, RouteLLM | Try cheap → escalate on failure | No decomposition. Retry = double cost on failure |
| **Semantic routing** | Anthropic research, Unify.ai | Embed query → cosine to model profile | Routes whole request, no decomposition |
| **LLM-as-router** | OpenRouter Auto | Cheap model classifies → dispatch | Extra routing call cost. No decomposition |
| **Task decomposition** | AutoGPT, CrewAI | LLM splits task into subtasks | LLM decides decomposition = hallucinations, expensive |
| **GOAP planning** | Embabel (kukuvaia) | A* planner on typed actions | Deterministic, but developer defines actions manually |

Nobody combines decomposition with optimal model assignment automatically.

## Proposed Algorithm: Hybrid GOAP + Cost-Aware Model Assignment

### How It Works

```
1. DECOMPOSE (Embabel GOAP — deterministic, zero LLM cost)
   User: "Analyze this document, find errors, propose fixes"
   
   GOAP planner (A*) discovers:
     Step 1: extractContent(Document) → RawContent
     Step 2: analyzeErrors(RawContent) → ErrorReport  
     Step 3: generateFixes(ErrorReport) → FixProposals
     Step 4: compileReport(FixProposals) → FinalReport

2. CLASSIFY each step (deterministic rules, zero LLM cost)
   Step 1: extractContent   → EXTRACTION  (structured, low reasoning)
   Step 2: analyzeErrors    → ANALYSIS    (pattern matching, medium reasoning)
   Step 3: generateFixes    → GENERATION  (creative, high reasoning)
   Step 4: compileReport    → SYNTHESIS   (summarization, medium reasoning)

3. ASSIGN model per step (cost optimization from DB mapping)
   Step 1: EXTRACTION  → Haiku    (cheap, fast, sufficient)
   Step 2: ANALYSIS    → Sonnet   (needs reasoning but not deep)
   Step 3: GENERATION  → Opus     (creative, needs deep understanding)
   Step 4: SYNTHESIS   → Sonnet   (summarization, medium is enough)

4. EXECUTE (sequential or parallel where GOAP allows)
   Step 1: Haiku   extracts   → 800 tokens  
   Step 2: Sonnet  analyzes   → 2000 tokens 
   Step 3: Opus    generates  → 3000 tokens 
   Step 4: Sonnet  compiles   → 1500 tokens 
```

The critical insight: step 1 (DECOMPOSE) is **free** — GOAP uses A* algorithm on typed state transitions, no LLM call needed. The planning itself costs zero tokens. Only action execution inside each step calls the LLM.

## Task Complexity Taxonomy

```java
public enum TaskComplexity {
    EXTRACTION,      // Structured data extraction, parsing
    TRANSFORMATION,  // Format conversion, mapping, reformatting
    CLASSIFICATION,  // Categorization, labeling, tagging
    RETRIEVAL,       // Search, lookup, filtering
    ANALYSIS,        // Pattern finding, comparison, reasoning
    GENERATION,      // Creative content, code, new ideas
    SYNTHESIS,       // Merge multiple inputs, summarization
    STRATEGY,        // Planning, architecture, high-level decisions
    EVALUATION       // Quality assessment, review, scoring
}
```

### Complexity → Model Role Mapping

Configurable in database (extends `model_roles` table or new `complexity_mappings` table):

```
EXTRACTION      → worker      (Haiku)     — structured, low reasoning
TRANSFORMATION  → worker      (Haiku)     — mechanical, pattern-based
CLASSIFICATION  → worker      (Haiku)     — categorical, bounded output
RETRIEVAL       → worker      (Haiku)     — search, no generation
ANALYSIS        → supervisor  (Sonnet)    — reasoning required
GENERATION      → supervisor  (Sonnet)    — creative but bounded
SYNTHESIS       → supervisor  (Sonnet)    — multi-source merging
STRATEGY        → advisor     (Opus)      — deep reasoning, architecture
EVALUATION      → supervisor  (Sonnet)    — judgment, scoring
```

This mapping is a starting point. The admin can override via API (e.g., promote GENERATION to advisor/Opus for a specific use case).

## Embabel Integration

### Current: Manual Model Assignment

Developer explicitly chooses model per action:

```kotlin
@Action
fun extract(doc: Document, ctx: ProcessContext): RawContent =
    ctx.ai().withLlmByRole("cheapest").creating(RawContent::class.java)
        .create("Extract content from: ${doc.text}")

@Action  
fun analyze(content: RawContent, ctx: ProcessContext): ErrorReport =
    ctx.ai().withLlmByRole("best").creating(ErrorReport::class.java)
        .create("Find errors in: ${content.text}")
```

Problem: developer guesses which model is needed. No data-driven optimization.

### Proposed: Annotation-Based Auto Assignment

```kotlin
@Action
@ActionComplexity(EXTRACTION)
fun extract(doc: Document, ctx: ProcessContext): RawContent =
    ctx.ai().withAutoModel().creating(RawContent::class.java)
        .create("Extract content from: ${doc.text}")

@Action
@ActionComplexity(ANALYSIS)
fun analyze(content: RawContent, ctx: ProcessContext): ErrorReport =
    ctx.ai().withAutoModel().creating(ErrorReport::class.java)
        .create("Find errors in: ${content.text}")
```

`@ActionComplexity` declares the complexity type. `withAutoModel()` resolves to the optimal model from DB mapping at runtime:

```
@ActionComplexity(EXTRACTION)
    → complexityToRole mapping in DB
    → EXTRACTION maps to "worker" role
    → model_roles table: "worker" → haiku model UUID
    → ChatModelCache resolves UUID → ChatModel instance
    → LLM call executes with Haiku
```

### Implementation: @ActionComplexity Annotation

```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ActionComplexity(val value: TaskComplexity)
```

### Implementation: withAutoModel() Extension

```kotlin
fun OperationContextAi.withAutoModel(): OperationContextAi {
    // 1. Read @ActionComplexity from calling @Action method
    val complexity = resolveComplexityFromAnnotation()
    
    // 2. Look up role mapping from DB
    val role = complexityMappingService.getRole(complexity)
    
    // 3. Delegate to existing role-based selection
    return this.withLlmByRole(role)
}
```

### Fallback Behavior

- No `@ActionComplexity` annotation → use default model (Sonnet/supervisor)
- Unknown complexity type → use default model
- Role not configured in DB → use default model
- Model unavailable → escalate to next tier

## Token Savings Calculations

### Scenario 1: Research Task (4 steps, mixed complexity)

| Step | Without routing (all-Opus) | With per-step routing | Model |
|------|--------------------------|----------------------|-------|
| Extract content | 800 tokens × Opus price | 800 tokens × Haiku price | Haiku |
| Analyze errors | 2000 tokens × Opus price | 2000 tokens × Sonnet price | Sonnet |
| Generate fixes | 3000 tokens × Opus price | 3000 tokens × Opus price | Opus |
| Compile report | 1500 tokens × Sonnet price | 1500 tokens × Sonnet price | Sonnet |
| **Estimated savings** | | | **~50%** |

### Scenario 2: Simple Q&A (1 step, no decomposition)

| Step | Without routing | With routing | Savings |
|------|----------------|-------------|---------|
| Answer question | Sonnet: 500 tokens | Haiku: 500 tokens | ~5x cheaper |

### Scenario 3: Complex Architecture (3 steps, all need deep reasoning)

| Step | Without routing | With routing | Savings |
|------|----------------|-------------|---------|
| Analyze system | Opus | Opus | 0 |
| Design solution | Opus | Opus | 0 |
| Write spec | Opus | Sonnet | ~3x on this step |
| **Estimated savings** | | | **~15-20%** |

### Scenario 4: Data Pipeline (5 steps, mostly mechanical)

| Step | Without routing | With routing | Savings |
|------|----------------|-------------|---------|
| Parse CSV | Sonnet | Haiku | ~5x cheaper |
| Validate schema | Sonnet | Haiku | ~5x cheaper |
| Transform format | Sonnet | Haiku | ~5x cheaper |
| Enrich with lookup | Sonnet | Haiku | ~5x cheaper |
| Generate summary | Sonnet | Sonnet | 0 |
| **Estimated savings** | | | **~70%** |

### Summary of Savings

| Task Profile | Expected Savings |
|-------------|-----------------|
| Mixed complexity (common) | **30-50%** |
| Uniformly complex (rare) | **0-20%** |
| Mostly mechanical (data processing) | **60-80%** |
| Single-step simple | **70-85%** (just routing, no decomposition) |

## Advanced: Dynamic Complexity Estimation (Future)

Static `@ActionComplexity` annotations cover 80% of cases. For the remaining 20%, dynamic estimation:

```kotlin
@Action
fun processDocument(doc: Document, ctx: ProcessContext): ProcessedDoc {
    val complexity = complexityEstimator.estimate(
        actionName = "processDocument",
        inputTokens = doc.text.length / 4,
        outputType = ProcessedDoc::class,
        historicalStats = statsService.getActionStats("processDocument")
    )
    
    return ctx.ai()
        .withLlm(modelSelector.selectOptimal(complexity))
        .creating(ProcessedDoc::class.java)
        .create("Process: ${doc.text}")
}
```

`ComplexityEstimator` considers:
- Input size (larger inputs may need more capable model)
- Output type complexity (simple string vs structured object with many fields)
- Historical performance (if Haiku failed this action before, escalate)
- Action name patterns (extract/parse → low, analyze/design → high)

This is Phase 2 of the algorithm. Phase 1 (static annotations) should ship first.

## Dreaming Integration

The dreaming agent's log analysis task can optimize complexity mappings over time:

```
Dream Task: Model Assignment Optimization
Schedule: Weekly

1. Analyze past agent executions from audit log
2. For each @ActionComplexity type, check:
   - Did the assigned model succeed? (no retries, no escalation)
   - Could a cheaper model have handled it? (similar tasks succeeded with cheaper model)
   - Did it need escalation? (assigned model was too weak)
3. Propose mapping adjustments:
   - "CLASSIFICATION actions succeeded 98% with Haiku — keep as worker"
   - "GENERATION actions needed Opus escalation 30% of time — promote to advisor"
   - "EXTRACTION with large documents (>10k tokens) failed on Haiku 15% — add size threshold"
```

This creates a **self-optimizing cost loop**: execute → measure → adjust mappings → execute cheaper → measure again.

## Database Schema Extension

```sql
-- Extends the model routing system from model-routing-and-embabel.md

CREATE TABLE kukuvaia.complexity_mappings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    complexity      VARCHAR(30) NOT NULL UNIQUE,
    role            VARCHAR(50) NOT NULL,       -- FK concept to model_roles.role
    description     VARCHAR(500),
    min_input_tokens INT,                       -- optional: size-based override
    max_input_tokens INT,                       -- optional: escalate above this
    success_rate    DECIMAL(5,2),               -- tracked by dreaming
    last_optimized  TIMESTAMP,                  -- when dreaming last adjusted
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- Seed defaults
INSERT INTO kukuvaia.complexity_mappings (complexity, role, description) VALUES
    ('EXTRACTION', 'worker', 'Structured data extraction, parsing'),
    ('TRANSFORMATION', 'worker', 'Format conversion, mapping'),
    ('CLASSIFICATION', 'worker', 'Categorization, labeling'),
    ('RETRIEVAL', 'worker', 'Search, lookup, filtering'),
    ('ANALYSIS', 'supervisor', 'Pattern finding, reasoning'),
    ('GENERATION', 'supervisor', 'Creative content, code'),
    ('SYNTHESIS', 'supervisor', 'Multi-source merging, summarization'),
    ('STRATEGY', 'advisor', 'Planning, architecture decisions'),
    ('EVALUATION', 'supervisor', 'Quality assessment, review');
```

## API Endpoints

```
GET    /api/complexity-mappings              — list all mappings
PUT    /api/complexity-mappings/{complexity} — update mapping (change role)
GET    /api/complexity-mappings/stats        — success rates per complexity type
POST   /api/complexity-mappings/optimize     — trigger dreaming optimization manually
```

## Relationship to Existing Architecture

| Component | Connection |
|-----------|-----------|
| **Embabel GOAP** | Provides free decomposition (A* planning, zero LLM cost) |
| **Model Routing (Phase 5)** | `@ActionComplexity` extends routing from request-level to action-level |
| **Provider Registry (Phase 1-3)** | Complexity mappings reference roles from `model_roles` table |
| **Dreaming** | Log analysis optimizes complexity→role mappings over time |
| **Harness Engineering** | Group rules can override mappings (e.g., "for medical domain, EXTRACTION needs Sonnet") |
| **SubAgentFactory** | Sub-agents can use complexity-based model selection too |
| **Cost tracking** | Per-action model tracking enables savings measurement |

## Implementation Phases

### Phase A: Static Annotation System
- `@ActionComplexity` Kotlin annotation
- `TaskComplexity` enum (Java, shared between core and agents)
- `complexity_mappings` DB table with seed data
- `ComplexityMappingService` — reads mappings from DB
- `withAutoModel()` extension for Embabel `OperationContextAi`
- Unit tests with FakeOperationContext

### Phase B: Complexity Mapping API
- CRUD endpoints for managing mappings
- Per-complexity success rate tracking in audit log
- Dashboard data for admin UI

### Phase C: Dreaming Optimization
- Weekly dream task analyzes action execution history
- Proposes mapping adjustments based on success/failure rates
- Recommendations in DreamReport (human approval before applying)

### Phase D: Dynamic Estimation (Optional)
- `ComplexityEstimator` — input size, output type, historical stats
- Size-based thresholds (large documents escalate automatically)
- Action-name heuristic patterns

## Uniqueness

This combination does not exist in any current platform:

- **GOAP decomposition** (zero-cost planning) + **per-action model assignment** (cost-optimized) + **self-optimizing mappings** (dreaming feedback loop)

Closest approaches:
- AutoGPT decomposes but uses same model for everything
- CrewAI assigns models per-agent but not per-action within an agent
- RouteLLM routes but doesn't decompose
- kukuvaia would decompose AND route at the action granularity, AND optimize over time
