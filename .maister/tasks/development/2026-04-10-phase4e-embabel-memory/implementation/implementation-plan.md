# Implementation Plan: Phase 4E — Embabel Agent Integration with Memory

## Task Groups

### Group 1: Build + Data Models

**Specialty**: Build configuration + Kotlin data modeling
**Dependencies**: None

**Steps**:

- [ ] 1.1 Update kukuvaia-agents/build.gradle: add `implementation project(':kukuvaia-memory')`
- [ ] 1.2 Create `agents/model/ResearchModels.kt` — data classes: ResearchRequest, WebFindings, ResearchReport
- [ ] 1.3 Create `agents/model/ValidationModels.kt` — data classes: ValidationRequest, ValidationResults, ValidationReport
- [ ] 1.4 Verify build: `./gradlew :kukuvaia-agents:compileKotlin`

---

### Group 2: Memory Utility

**Specialty**: Kotlin extensions
**Dependencies**: Group 1

**Steps**:

- [ ] 2.1 Write tests for MemoryAwareAction — verify retrieve and save extensions work with mocked services
- [ ] 2.2 Create `agents/util/MemoryAwareAction.kt`:
  - Extension function to retrieve relevant memories for a topic
  - Extension function to save a memory entry
  - Uses MemoryRetrievalService and SmartMemoryRepository injected via Spring
- [ ] 2.3 Run tests

---

### Group 3: ResearchAgent

**Specialty**: Embabel GOAP agent
**Dependencies**: Group 2

**Steps**:

- [ ] 3.1 Write tests for ResearchAgent using FakeOperationContext — verify action signatures, GOAP can discover action sequence, memory retrieval called
- [ ] 3.2 Create `agents/ResearchAgent.kt`:
  - @Agent(description = "Multi-step research with memory context")
  - @Action gatherContext: retrieves relevant memories -> ResearchRequest with context
  - @Action analyzeFindings: uses LLM to analyze -> WebFindings
  - @AchievesGoal @Action compileReport: combines findings -> ResearchReport, saves to memory
- [ ] 3.3 Run tests: `./gradlew :kukuvaia-agents:test`

---

### Group 4: ValidationAgent

**Specialty**: Embabel GOAP agent
**Dependencies**: Group 2

**Steps**:

- [ ] 4.1 Write tests for ValidationAgent using FakeOperationContext — verify action signatures, memory save for results
- [ ] 4.2 Create `agents/ValidationAgent.kt`:
  - @Agent(description = "Multi-step validation with memory persistence")
  - @Action checkStatus: checks outline status
  - @Action runValidation: executes validation
  - @AchievesGoal @Action generateReport: creates report, saves as episodic memory
- [ ] 4.3 Run tests: `./gradlew :kukuvaia-agents:test`

---

### Group 5: Final Verification

**Specialty**: Integration

**Steps**:

- [ ] 5.1 `./gradlew clean build` — all modules pass
- [ ] 5.2 Verify PingAgent test still passes (no regressions)
- [ ] 5.3 Verify kukuvaia-agents depends on kukuvaia-memory (check build.gradle)
- [ ] 5.4 Verify no circular dependencies between modules
