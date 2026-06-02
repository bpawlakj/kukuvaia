# T09: Task Decomposition + @ActionComplexity

**Status**: `pending`
**Tier**: 3 — Agents
**Depends On**: T08
**Blocks**: T14
**Source**: `docs/analyzes/task-decomposition-model-assignment-analysis.md`

## Goal

Automatic per-action model assignment based on task complexity annotations. GOAP decomposes tasks (free), `@ActionComplexity` declares complexity type, `withAutoModel()` resolves to optimal model from DB mapping. Expected 30-50% token cost savings on mixed-complexity tasks.

## Scope

### TaskComplexity Enum (Java, shared)
```java
public enum TaskComplexity {
    EXTRACTION, TRANSFORMATION, CLASSIFICATION, RETRIEVAL,
    ANALYSIS, GENERATION, SYNTHESIS, STRATEGY, EVALUATION
}
```

### @ActionComplexity Annotation (Kotlin)
```kotlin
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ActionComplexity(val value: TaskComplexity)
```

### complexity_mappings DB Table
- Maps TaskComplexity → role name (from model_roles)
- Configurable via API, optimizable by dreaming agent
- Seed with defaults: EXTRACTION→worker, ANALYSIS→supervisor, STRATEGY→advisor

### withAutoModel() Extension
- Reads `@ActionComplexity` from calling action
- Resolves complexity → role → model from DB
- Fallback to default if no annotation

### Migration: `V7__create_complexity_mappings.sql`

## File Inventory

### Create
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/TaskComplexity.java`
- `kukuvaia-agents/src/main/kotlin/ai/kukuvaia/agents/ActionComplexity.kt`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ComplexityMappingService.java`
- `kukuvaia-core/src/main/java/ai/kukuvaia/provider/registry/ComplexityMappingRepository.java`
- `kukuvaia-app/src/main/resources/db/migration/V7__create_complexity_mappings.sql`

### Modify
- ResearchAgent.kt, ValidationAgent.kt — add `@ActionComplexity` + `withAutoModel()`

## Acceptance Criteria

- [ ] `@ActionComplexity(EXTRACTION)` + `withAutoModel()` resolves to Haiku
- [ ] `@ActionComplexity(STRATEGY)` + `withAutoModel()` resolves to Opus
- [ ] No annotation → fallback to default model (Sonnet)
- [ ] Mappings configurable via `PUT /api/complexity-mappings/{type}`
- [ ] Unit tests for resolution chain
