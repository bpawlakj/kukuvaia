# Specification: kukuvaia-memory Module + DB Schema Consolidation (Phase 4A)

## Scope

Create the `kukuvaia-memory` Gradle module as standalone infrastructure, consolidate the database from two schemas (`public` + `kukuvaia_agent`) into one (`kukuvaia`), and migrate existing memory classes from `kukuvaia-core` to the new module.

## Out of Scope

- Phase 4B: JsonChatMemoryRepository / JSONB conversations (separate task)
- Phase 4C: pgvector semantic search / SmartMemoryAdvisor (separate task)
- Phase 4D: Extraction pipeline / consolidation jobs (separate task)
- Phase 4E: Embabel agent integration with memory (separate task)

## Architecture Decision

- **kukuvaia-memory is infrastructure** — standalone module, kukuvaia-core depends on it
- **One schema: `kukuvaia`** — renamed from `kukuvaia_agent`, `public` schema tables dropped
- **Spring AI `initialize-schema: never`** — no auto-created tables, custom `JsonChatMemoryRepository` replaces `JdbcChatMemoryRepository` (implemented in Phase 4B, stubbed here)
- See: `docs/architecture/memory-architecture.md` for full rationale

## Module Structure

```
kukuvaia-engine/
├── settings.gradle                → add 'kukuvaia-memory'
├── kukuvaia-memory/               ← NEW MODULE
│   ├── build.gradle               ← java-library, Spring JDBC, Spring AI memory API
│   └── src/main/java/ai/kukuvaia/memory/
│       ├── model/
│       │   ├── KukuvaiaUser.java
│       │   ├── KukuvaiaSession.java
│       │   ├── ConversationSnapshot.java
│       │   ├── MemoryEntry.java          (migrated from core, extended)
│       │   └── Plan.java
│       ├── repository/
│       │   ├── UserRepository.java
│       │   ├── SessionRepository.java
│       │   └── SmartMemoryRepository.java (migrated from core MemoryRepository)
│       ├── advisor/
│       │   └── SmartMemoryAdvisor.java    (migrated from core PersistentMemoryAdvisor, minimal changes for now)
│       └── config/
│           └── MemoryModuleConfig.java    (migrated from core MemoryConfig)
│
├── kukuvaia-core/                 ← MODIFY
│   ├── build.gradle               → add dependency on :kukuvaia-memory
│   └── (DELETE: agent/memory/*, config/MemoryConfig.java)
│
└── kukuvaia-app/                  ← MODIFY
    └── src/main/resources/
        ├── application.yaml       → update schema refs + initialize-schema: never
        └── db/migration/
            └── V4__consolidate_schema.sql  ← NEW migration
```

## Database Changes (V4 Migration)

### Schema consolidation
```sql
ALTER SCHEMA kukuvaia_agent RENAME TO kukuvaia;
DROP TABLE IF EXISTS public.spring_ai_chat_memory;
```

### New tables
```sql
CREATE TABLE kukuvaia.users (
    id           VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255),
    created_at   TIMESTAMP DEFAULT NOW(),
    last_seen_at TIMESTAMP DEFAULT NOW(),
    preferences  JSONB DEFAULT '{}'
);

CREATE TABLE kukuvaia.sessions (
    id         VARCHAR(255) PRIMARY KEY,
    user_id    VARCHAR(255) NOT NULL REFERENCES kukuvaia.users(id),
    name       VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    metadata   JSONB DEFAULT '{}',
    status     VARCHAR(20) DEFAULT 'active'
);

CREATE TABLE kukuvaia.conversations (
    session_id    VARCHAR(255) PRIMARY KEY REFERENCES kukuvaia.sessions(id),
    messages      JSONB NOT NULL DEFAULT '[]',
    summary       TEXT,
    message_count INT DEFAULT 0,
    token_count   INT DEFAULT 0,
    updated_at    TIMESTAMP DEFAULT NOW()
);
```

### Existing table modifications
```sql
-- agent_memory → memories
ALTER TABLE kukuvaia.agent_memory RENAME TO memories;
ALTER TABLE kukuvaia.memories ADD COLUMN memory_type VARCHAR(20) DEFAULT 'semantic';
ALTER TABLE kukuvaia.memories ADD COLUMN relevance_score FLOAT DEFAULT 1.0;
ALTER TABLE kukuvaia.memories ADD COLUMN access_count INT DEFAULT 0;
ALTER TABLE kukuvaia.memories ADD COLUMN last_accessed_at TIMESTAMP DEFAULT NOW();
ALTER TABLE kukuvaia.memories ADD COLUMN session_id VARCHAR(255);
ALTER TABLE kukuvaia.memories ADD COLUMN expires_at TIMESTAMP;
-- embedding column deferred to Phase 4C (requires pgvector extension)

-- agent_plans → plans (add user_id, UUID PK)
ALTER TABLE kukuvaia.agent_plans RENAME TO plans;
ALTER TABLE kukuvaia.plans ADD COLUMN id UUID DEFAULT gen_random_uuid();
ALTER TABLE kukuvaia.plans ADD COLUMN user_id VARCHAR(255);
ALTER TABLE kukuvaia.plans ADD COLUMN status VARCHAR(20) DEFAULT 'active';
```

### Indexes
```sql
CREATE INDEX idx_sessions_user ON kukuvaia.sessions(user_id, updated_at DESC);
CREATE INDEX idx_memories_user_type ON kukuvaia.memories(user_id, memory_type);
CREATE INDEX idx_memories_relevance ON kukuvaia.memories(user_id, relevance_score DESC);
```

## Code Changes

### Files to CREATE (in kukuvaia-memory)
1. `build.gradle` — java-library plugin, dependencies
2. `model/KukuvaiaUser.java` — record(id, displayName, preferences, createdAt, lastSeenAt)
3. `model/KukuvaiaSession.java` — record(id, userId, name, metadata, status, createdAt, updatedAt)
4. `model/ConversationSnapshot.java` — record(sessionId, messages, summary, messageCount, tokenCount, updatedAt)
5. `model/Plan.java` — record(id, sessionId, userId, task, steps, status, createdAt, updatedAt)
6. `repository/UserRepository.java` — CRUD for users table
7. `repository/SessionRepository.java` — CRUD for sessions table, findByUserId

### Files to MOVE (from kukuvaia-core to kukuvaia-memory)
1. `agent/memory/MemoryEntry.java` → `memory/model/MemoryEntry.java` — add new fields (memoryType, relevanceScore, accessCount, lastAccessedAt, sessionId, expiresAt)
2. `agent/memory/MemoryRepository.java` → `memory/repository/SmartMemoryRepository.java` — update schema prefix kukuvaia_agent → kukuvaia, add new field queries
3. `agent/memory/PersistentMemoryAdvisor.java` → `memory/advisor/SmartMemoryAdvisor.java` — update imports, schema refs (behavior unchanged in this phase)
4. `config/MemoryConfig.java` → `memory/config/MemoryModuleConfig.java` — update imports

### Files to MODIFY (in kukuvaia-core)
1. `build.gradle` — add `api project(':kukuvaia-memory')`
2. `config/ChatClientConfig.java` — update import: PersistentMemoryAdvisor → SmartMemoryAdvisor
3. `tools/MemoryTools.java` — update import: MemoryRepository → SmartMemoryRepository
4. `tools/PlanningTools.java` — update schema prefix kukuvaia_agent → kukuvaia

### Files to MODIFY (in kukuvaia-app)
1. `application.yaml` — schemas: kukuvaia, initialize-schema: never

### Files to DELETE (from kukuvaia-core)
1. `agent/memory/MemoryEntry.java` (moved)
2. `agent/memory/MemoryRepository.java` (moved)
3. `agent/memory/PersistentMemoryAdvisor.java` (moved)
4. `config/MemoryConfig.java` (moved)

## Acceptance Criteria

1. `./gradlew clean build` passes — all 4 modules compile
2. `./gradlew test` passes — all existing tests pass (imports updated)
3. No code in kukuvaia-core references `ai.kukuvaia.agent.memory` package (deleted)
4. kukuvaia-memory has zero dependency on kukuvaia-core
5. kukuvaia-core depends on kukuvaia-memory (via `api` configuration)
6. All SQL uses `kukuvaia.` schema prefix (no `kukuvaia_agent.`)
7. V4 migration is syntactically valid SQL
8. application.yaml has `initialize-schema: never` and `schemas: kukuvaia`

## Dependencies

- Spring Boot 3.4.4 (JdbcTemplate, @Component, @Configuration)
- Spring AI 1.1.0 (ChatMemoryRepository interface, ChatMemory, MessageWindowChatMemory)
- PostgreSQL (JDBC)
- Flyway (migration)
