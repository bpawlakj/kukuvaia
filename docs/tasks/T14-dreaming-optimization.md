# T14: Dreaming Self-Optimization

**Status**: `pending`
**Tier**: 5 — Optimization
**Depends On**: T12, T09
**Blocks**: —
**Source**: `docs/analyzes/dreaming-agent-analysis.md`, `docs/analyzes/task-decomposition-model-assignment-analysis.md`

## Goal

Dreaming agent analyzes past execution data to optimize complexity→model mappings and propose model upgrades. Creates a self-improving cost loop: execute → measure → adjust → execute cheaper.

## Scope

### Model Assignment Optimization (Dream Task)
- Weekly analysis of agent execution history from audit log
- Per `@ActionComplexity` type: success rates, escalation frequency, cost
- Propose mapping adjustments:
  - "CLASSIFICATION succeeded 98% with Haiku — keep as worker"
  - "GENERATION needed Opus escalation 30% — promote to advisor"
- Recommendations in DreamReport (human approval)

### Model Scout Enhancement
- Cross-reference HuggingFace discoveries with current role assignments
- Compare benchmarks (if available) with current models
- Propose: "Haiku 4.6 is 15% faster, same cost — upgrade worker role"

### Feedback Loop
- Track accepted vs rejected recommendations over time
- Adjust confidence scores based on acceptance rate
- Learn which recommendation types are most valuable

## File Inventory

### Create
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/dream/ModelOptimizationTask.kt`
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/dream/ModelScoutTask.kt`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ExecutionStatsService.java`

## Acceptance Criteria

- [ ] Weekly dream task produces complexity mapping optimization recommendations
- [ ] Model scout detects new models on configured providers
- [ ] Recommendations include confidence score and supporting evidence
- [ ] Accepted recommendations update complexity_mappings table
- [ ] Execution stats tracked per @ActionComplexity type
