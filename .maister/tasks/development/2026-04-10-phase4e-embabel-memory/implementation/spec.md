# Specification: Phase 4E — Embabel Agent Integration with Memory

## Scope

Create production Embabel agents that leverage kukuvaia-memory for context-aware planning. Agents retrieve relevant memories before actions and persist new knowledge after completing goals.

## Out of Scope

- Agent UI/API endpoints (future)
- Autonomy/Open mode (future)
- MCP Server export of agents (future)

## Prerequisites (from Phases 4A-4D)

- kukuvaia-memory: SmartMemoryRepository (vector search), MemoryRetrievalService (top-K), EmbeddingService
- kukuvaia-agents: Embabel 0.3.4, PingAgent.kt (framework verified)
- Embabel annotations: @Agent, @Action, @AchievesGoal, OperationContext

## What Changes

### 1. ResearchAgent (NEW — Kotlin)

GOAP agent for multi-step research tasks:

- Actions: gatherContext, searchWeb, analyzeFindings, compileReport
- Injects MemoryRetrievalService to load relevant context before research
- Injects SmartMemoryRepository to save findings as semantic memories
- Uses Embabel multi-model: cheap model for gathering, powerful for analysis

Data classes:

- ResearchRequest(topic: String, context: List\<String\>)
- WebFindings(topic: String, sources: List\<String\>, findings: List\<String\>)
- ResearchReport(topic: String, summary: String, findings: List\<String\>, recommendations: List\<String\>)

### 2. ValidationAgent (NEW — Kotlin)

GOAP agent for multi-step validation workflows:

- Actions: checkStatus, runValidation, analyzeResults, generateReport
- Saves validation results as episodic memories (with expires_at)
- Retrieves past validation history for the same outline via memory

Data classes:

- ValidationRequest(outlineId: String, ruleTypes: List\<String\>?)
- ValidationResults(outlineId: String, errors: Int, warnings: Int, details: List\<String\>)
- ValidationReport(outlineId: String, results: ValidationResults, recommendations: List\<String\>)

### 3. MemoryAwareAction (NEW — Kotlin utility)

Kotlin extension/helper that wraps common memory patterns:

- `OperationContext.retrieveMemories(topic: String, userId: String)` — calls MemoryRetrievalService
- `OperationContext.saveMemory(userId, name, content, type)` — calls SmartMemoryRepository
- Reduces boilerplate in agent @Action methods

### 4. kukuvaia-agents/build.gradle (MODIFY)

- Add explicit dependency on `:kukuvaia-memory` (currently transitive via core)
- This makes it clear that agents directly use memory services

### 5. AgentService Integration (MODIFY — kukuvaia-core)

- Add method to invoke Embabel agents from ChatClient flow
- When ChatClient detects a complex task, delegate to appropriate Embabel agent
- AgentPlatform bean injection for programmatic agent invocation

## Acceptance Criteria

1. `./gradlew clean build` passes
2. ResearchAgent compiles and GOAP planner discovers action sequence
3. ValidationAgent compiles and GOAP planner discovers action sequence
4. Both agents inject memory services via Spring DI
5. Unit tests pass with FakeOperationContext (no LLM required)
6. PingAgent still works (no regressions)

## File Inventory

### Create (in kukuvaia-agents)

- `agents/ResearchAgent.kt`
- `agents/ValidationAgent.kt`
- `agents/model/ResearchModels.kt` (data classes)
- `agents/model/ValidationModels.kt` (data classes)
- `agents/util/MemoryAwareAction.kt` (extension functions)
- Tests for all new agents

### Modify

- `kukuvaia-agents/build.gradle` — add `:kukuvaia-memory` dependency
