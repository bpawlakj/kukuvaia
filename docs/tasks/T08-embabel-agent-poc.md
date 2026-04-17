# T08: Embabel Multi-Model Agent PoC

**Status**: `pending`
**Tier**: 3 — Agents
**Depends On**: T04
**Blocks**: T09
**Source**: `docs/plan/model-routing-and-embabel.md` Phase 6, `.maister/tasks/development/2026-04-10-phase4e-embabel-memory/`

## Goal

Build production Embabel agents (ResearchAgent, ValidationAgent) demonstrating GOAP planning with per-action model selection and memory integration. Merges Phase 4E pending task with new multi-model routing.

## Scope

### ResearchAgent.kt
- GOAP agent with per-action model tiers:
  - `quickScan()` → cheapest (Haiku) — fast initial scan
  - `deepAnalysis()` → best (Opus) — complex reasoning
- Injects `SmartMemoryRepository` for context retrieval
- Saves findings as semantic memories

### ValidationAgent.kt
- GOAP agent for multi-step validation workflows
- Saves validation results as episodic memories (with expiry)
- Retrieves past validation history from memory

### MemoryAwareAction.kt
- Kotlin extension functions for common memory patterns in agents
- `OperationContext.retrieveMemories(topic, userId)`
- `OperationContext.saveMemory(userId, name, content, type)`

### Data Classes
- ResearchModels.kt: ResearchQuery, InitialFindings, AnalysisResult
- ValidationModels.kt: ValidationRequest, ValidationResults, ValidationReport

## File Inventory

### Create
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/research/ResearchAgent.kt`
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/research/ResearchModels.kt`
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/validation/ValidationAgent.kt`
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/validation/ValidationModels.kt`
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/util/MemoryAwareAction.kt`
- Tests for all new agents (FakeOperationContext, no real LLM)

### Modify
- `kukuvaia-agents/build.gradle` — add explicit `:kukuvaia-memory` dependency

## Acceptance Criteria

- [ ] GOAP planner discovers correct action sequence for ResearchAgent
- [ ] GOAP planner discovers correct action sequence for ValidationAgent
- [ ] ResearchAgent uses cheapest role for scan, best role for analysis
- [ ] Both agents inject memory services via Spring DI
- [ ] Unit tests pass with FakeOperationContext (no real LLM calls)
- [ ] PingAgent still works (no regressions)
- [ ] `./gradlew clean build` passes
