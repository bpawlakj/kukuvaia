# Implementation Plan: kukuvaia-memory Module + DB Schema Consolidation

## Task Groups

### Group 1: Gradle Module Setup
**Specialty**: Build configuration
**Dependencies**: None
**Files**: settings.gradle, kukuvaia-memory/build.gradle, kukuvaia-core/build.gradle

**Steps**:
- [x] 1.1 Create `kukuvaia-memory/build.gradle` with java-library plugin, spring-boot-starter-jdbc, Spring AI chat-memory-repository-jdbc (for ChatMemoryRepository interface), jackson-databind
- [x] 1.2 Update `settings.gradle`: add `'kukuvaia-memory'` to include list
- [x] 1.3 Update `kukuvaia-core/build.gradle`: add `api project(':kukuvaia-memory')`
- [x] 1.4 Create empty source dirs: `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/` with model/, repository/, advisor/, config/ subdirs
- [x] 1.5 Verify: `./gradlew clean build -x test` passes with empty module

**Tests**:
- Build compiles with 4 modules
- No circular dependencies

---

### Group 2: Domain Model (kukuvaia-memory/model/)
**Specialty**: Data modeling
**Dependencies**: Group 1
**Files**: KukuvaiaUser.java, KukuvaiaSession.java, ConversationSnapshot.java, Plan.java, MemoryEntry.java (migrated+extended)

**Steps**:
- [x] 2.1 Create `KukuvaiaUser.java` — record with id, displayName, preferences (Map), createdAt, lastSeenAt
- [x] 2.2 Create `KukuvaiaSession.java` — record with id, userId, name, metadata (Map), status, createdAt, updatedAt
- [x] 2.3 Create `ConversationSnapshot.java` — record with sessionId, messages (String/JSONB), summary, messageCount, tokenCount, updatedAt
- [x] 2.4 Create `Plan.java` — record with id (UUID), sessionId, userId, task, steps (String/JSONB), status, createdAt, updatedAt
- [x] 2.5 Move `MemoryEntry.java` from kukuvaia-core to kukuvaia-memory/model/ — extend with: memoryType, relevanceScore, accessCount, lastAccessedAt, sessionId, expiresAt
- [x] 2.6 Delete `kukuvaia-core/src/main/java/ai/kukuvaia/agent/memory/MemoryEntry.java`

**Tests**:
- Record compact constructor validation (non-null/non-blank required fields)
- Build compiles

---

### Group 3: Repositories (kukuvaia-memory/repository/)
**Specialty**: Data access
**Dependencies**: Group 2
**Files**: UserRepository.java, SessionRepository.java, SmartMemoryRepository.java

**Steps**:
- [x] 3.1 Create `UserRepository.java` — JdbcTemplate CRUD: save (upsert), findById, updateLastSeen. Schema: `kukuvaia.users`
- [x] 3.2 Create `SessionRepository.java` — JdbcTemplate CRUD: save, findById, findByUserId (ordered by updated_at DESC), updateName, archive. Schema: `kukuvaia.sessions`
- [x] 3.3 Move `MemoryRepository.java` from kukuvaia-core → `SmartMemoryRepository.java` in kukuvaia-memory — update all SQL: `kukuvaia_agent.agent_memory` → `kukuvaia.memories`, add queries for new fields (memoryType, relevanceScore)
- [x] 3.4 Delete `kukuvaia-core/src/main/java/ai/kukuvaia/agent/memory/MemoryRepository.java`

**Tests**:
- UserRepository: save + findById round-trip
- SessionRepository: save + findByUserId
- SmartMemoryRepository: existing tests pass with new class name

---

### Group 4: Advisor + Config Migration
**Specialty**: Spring AI integration
**Dependencies**: Group 3
**Files**: SmartMemoryAdvisor.java, MemoryModuleConfig.java

**Steps**:
- [x] 4.1 Move `PersistentMemoryAdvisor.java` → `SmartMemoryAdvisor.java` in kukuvaia-memory/advisor/ — update imports (SmartMemoryRepository), update class name. Behavior unchanged in this phase (load all user+feedback memories).
- [x] 4.2 Delete `kukuvaia-core/src/main/java/ai/kukuvaia/agent/memory/PersistentMemoryAdvisor.java`
- [x] 4.3 Move `MemoryConfig.java` → `MemoryModuleConfig.java` in kukuvaia-memory/config/ — update imports, keep MessageWindowChatMemory(20)
- [x] 4.4 Delete `kukuvaia-core/src/main/java/ai/kukuvaia/config/MemoryConfig.java`
- [x] 4.5 Delete empty directory `kukuvaia-core/src/main/java/ai/kukuvaia/agent/memory/`

**Tests**:
- SmartMemoryAdvisor advisor order unchanged (HIGHEST_PRECEDENCE + 5)
- Build compiles

---

### Group 5: Core Module Updates
**Specialty**: Import fixes
**Dependencies**: Group 4
**Files**: ChatClientConfig.java, MemoryTools.java, PlanningTools.java, tests

**Steps**:
- [x] 5.1 Update `ChatClientConfig.java` — import SmartMemoryAdvisor from ai.kukuvaia.memory.advisor
- [x] 5.2 Update `MemoryTools.java` — import SmartMemoryRepository from ai.kukuvaia.memory.repository
- [x] 5.3 Update `PlanningTools.java` — change schema prefix `kukuvaia_agent.agent_plans` → `kukuvaia.plans` in all SQL
- [x] 5.4 Update all test files with changed imports (MemoryToolsTest, ChatClientConfigTest)
- [x] 5.5 Grep entire kukuvaia-core for any remaining references to `ai.kukuvaia.agent.memory` or `kukuvaia_agent.` — fix all
- [x] 5.6 Verify: `./gradlew :kukuvaia-core:test` — all 25 tests pass

**Tests**:
- All 25 existing core tests pass
- No references to old package/schema remain

---

### Group 6: Flyway Migration + App Config
**Specialty**: Database
**Dependencies**: Groups 1-5 (all code ready)
**Files**: V4__consolidate_schema.sql, application.yaml

**Steps**:
- [x] 6.1 Create `V4__consolidate_schema.sql` in kukuvaia-app/src/main/resources/db/migration/
- [x] 6.2 Update `application.yaml`: schemas: kukuvaia, initialize-schema: never
- [x] 6.3 Verify: `./gradlew clean build` — full build passes
- [x] 6.4 Verify: SQL syntax is valid

**Tests**:
- Full build passes
- Migration SQL is syntactically correct

---

### Group 7: Final Verification
**Specialty**: Integration
**Dependencies**: Group 6
**Files**: None (verification only)

**Steps**:
- [x] 7.1 `./gradlew clean build` — all modules compile, all tests pass
- [x] 7.2 Verify module dependency graph: memory has NO dep on core, core depends on memory, agents depends on both
- [x] 7.3 Grep for `kukuvaia_agent` in all Java/Kotlin files — zero matches (test assertions are correct guards)
- [x] 7.4 Grep for `agent_memory` in all Java files — zero matches
- [x] 7.5 Grep for `PersistentMemoryAdvisor` in all files — zero matches
- [x] 7.6 Verify kukuvaia-memory module has zero imports from ai.kukuvaia.agent, ai.kukuvaia.api, ai.kukuvaia.commands, etc.

**Tests**:
- All verification checks pass
- Clean module boundaries confirmed
