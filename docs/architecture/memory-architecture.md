# Memory Architecture

## Decision

Extract memory into a dedicated `kukuvaia-memory` module with three memory types (episodic, semantic, procedural), JSONB conversation storage, pgvector semantic search, and a Session-User entity model. Memory is infrastructure — `kukuvaia-core` depends on it.

## Problem

Current memory architecture has scaling and relevance issues:

| Problem | Impact |
|---------|--------|
| Row-per-message storage (`SPRING_AI_CHAT_MEMORY`) | Millions of rows with many users |
| `PersistentMemoryAdvisor` loads ALL user+feedback memories | Context window noise, no relevance filtering |
| No session entity | `conversation_id` is opaque string, no user linkage |
| No semantic search | tsvector = keyword match only, misses meaning |
| No memory lifecycle | No decay, compaction, TTL — grows forever |
| No user entity | `user_id` is loose string, no preferences table |
| `agent_plans` not linked to user | Security gap — no user isolation |

## Current State (What Exists)

### Layer 1: Session Chat Memory (Spring AI)
```
SPRING_AI_CHAT_MEMORY table (auto-created, row per message)
  → MessageWindowChatMemory (20 msg sliding window)
  → MessageChatMemoryAdvisor (injects into prompt)
  → JdbcChatMemoryRepository (JDBC-backed)
```

### Layer 2: Persistent Cross-Session Memory (Custom)
```
agent_memory table (CRUD + FTS)
  → MemoryRepository (parameterized queries, user isolation)
  → PersistentMemoryAdvisor (loads ALL user+feedback → system prompt)
  → MemoryTools (@Tool: save, search, list, delete)
```

### Current Files (in kukuvaia-core)
- `agent/memory/MemoryEntry.java` — record: id, userId, category, name, content
- `agent/memory/MemoryRepository.java` — CRUD + tsvector FTS
- `agent/memory/PersistentMemoryAdvisor.java` — loads all memories, injects into prompt
- `config/MemoryConfig.java` — MessageWindowChatMemory (20 msg)
- `tools/MemoryTools.java` — @Tool save/search/list/delete
- `tools/PlanningTools.java` — @Tool createPlan/completeStep/revisePlan

### Current DB Schema
```sql
agent_memory (user_id, category CHECK, name, description, content, UNIQUE(user_id,name))
agent_plans (session_id PK, task, steps JSONB)
SPRING_AI_CHAT_MEMORY (conversation_id, message_index, content, type) -- row per message
```

## Target State (What We Build)

### Three Memory Types

```
EPISODIC — what happened
  "User asked about validation for outline X, found 3 bugs"
  → Distilled from conversations
  → Decay: old episodes → summarize → delete
  → TTL: expires_at timestamp

SEMANTIC — what we know
  "User prefers table format"
  "Project X has 5 outlines, topic: biology"
  → Facts extracted from interactions
  → Upsert: new facts overwrite old (conflict resolution)
  → Embedding vector: semantic retrieval, not keyword

PROCEDURAL — how we do things
  "When user asks for validation → check status first"
  → Learned from user corrections and feedback
  → Updated by explicit feedback
```

### New Module: kukuvaia-memory

```
kukuvaia-memory/                    (Java, standalone infrastructure)
├── build.gradle                    (Spring JDBC, pgvector, Spring AI memory API)
└── src/main/java/ai/kukuvaia/memory/
    ├── model/
    │   ├── KukuvaiaUser.java       (id, displayName, preferences JSONB)
    │   ├── KukuvaiaSession.java    (id, userId, name, metadata JSONB, status)
    │   ├── ConversationSnapshot.java (sessionId, messages JSONB, summary)
    │   ├── MemoryEntry.java        (extended: + embedding, type, relevance, TTL)
    │   └── Plan.java               (id, sessionId, userId, task, steps JSONB)
    ├── repository/
    │   ├── UserRepository.java     (user CRUD)
    │   ├── SessionRepository.java  (session-user linkage)
    │   ├── SmartMemoryRepository.java (vector search, decay, consolidation)
    │   └── JsonChatMemoryRepository.java (implements ChatMemoryRepository — JSONB)
    ├── service/
    │   ├── MemoryExtractionService.java  (LLM extraction post-session)
    │   ├── MemoryRetrievalService.java   (semantic top-K for context)
    │   └── MemoryConsolidationService.java (scheduled: decay, merge)
    ├── advisor/
    │   └── SmartMemoryAdvisor.java (replaces PersistentMemoryAdvisor — top-K retrieval)
    └── config/
        └── MemoryModuleConfig.java (bean definitions)
```

### DB Schema Consolidation

**Decision:** Consolidate from two schemas (`public` + `kukuvaia_agent`) to one schema (`kukuvaia`).

| Before | After | Why |
|--------|-------|-----|
| `public.SPRING_AI_CHAT_MEMORY` | **Dropped** — replaced by `kukuvaia.conversations` (JSONB) | Row-per-message → 1 JSONB per session |
| `kukuvaia.agent_memory` | `kukuvaia.memories` (extended) | Renamed schema + new columns |
| `kukuvaia.agent_plans` | `kukuvaia.plans` (extended) | + user_id FK, UUID PK |
| (no users table) | `kukuvaia.users` | First-class user entity |
| (no sessions table) | `kukuvaia.sessions` | Session-user linkage |

Spring AI `initialize-schema: never` — no more auto-created tables in `public`. Custom `JsonChatMemoryRepository` replaces `JdbcChatMemoryRepository`.

### New DB Schema (V4 Migration)

```sql
-- Schema consolidation
ALTER SCHEMA kukuvaia_agent RENAME TO kukuvaia;
DROP TABLE IF EXISTS public.spring_ai_chat_memory;

-- Users (lightweight — auth is external)
CREATE TABLE kukuvaia.users (
    id           VARCHAR(255) PRIMARY KEY,
    display_name VARCHAR(255),
    created_at   TIMESTAMP DEFAULT NOW(),
    last_seen_at TIMESTAMP DEFAULT NOW(),
    preferences  JSONB DEFAULT '{}'
);

-- Sessions (first-class entity)
CREATE TABLE kukuvaia.sessions (
    id         VARCHAR(255) PRIMARY KEY,
    user_id    VARCHAR(255) NOT NULL REFERENCES kukuvaia.users(id),
    name       VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    metadata   JSONB DEFAULT '{}',
    status     VARCHAR(20) DEFAULT 'active'
);

-- Conversations (JSONB per session — NOT row-by-row)
CREATE TABLE kukuvaia.conversations (
    session_id    VARCHAR(255) PRIMARY KEY REFERENCES kukuvaia.sessions(id),
    messages      JSONB NOT NULL DEFAULT '[]',
    summary       TEXT,
    message_count INT DEFAULT 0,
    token_count   INT DEFAULT 0,
    updated_at    TIMESTAMP DEFAULT NOW()
);

-- Memories (extended agent_memory)
CREATE TABLE kukuvaia.memories (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          VARCHAR(255) NOT NULL REFERENCES kukuvaia.users(id),
    session_id       VARCHAR(255) REFERENCES kukuvaia.sessions(id),
    memory_type      VARCHAR(20) NOT NULL CHECK (memory_type IN ('episodic','semantic','procedural')),
    category         VARCHAR(50) NOT NULL CHECK (category IN ('user','project','feedback','reference')),
    name             VARCHAR(255) NOT NULL,
    description      TEXT NOT NULL,
    content          TEXT NOT NULL,
    embedding        vector(1536),
    relevance_score  FLOAT DEFAULT 1.0,
    access_count     INT DEFAULT 0,
    last_accessed_at TIMESTAMP DEFAULT NOW(),
    expires_at       TIMESTAMP,
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP DEFAULT NOW(),
    UNIQUE(user_id, name)
);

-- Plans (linked to session + user)
CREATE TABLE kukuvaia.plans (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id VARCHAR(255) NOT NULL REFERENCES kukuvaia.sessions(id),
    user_id    VARCHAR(255) NOT NULL REFERENCES kukuvaia.users(id),
    task       TEXT NOT NULL,
    steps      JSONB NOT NULL,
    status     VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_sessions_user ON kukuvaia.sessions(user_id, updated_at DESC);
CREATE INDEX idx_memories_user_type ON kukuvaia.memories(user_id, memory_type);
CREATE INDEX idx_memories_relevance ON kukuvaia.memories(user_id, relevance_score DESC);
CREATE INDEX idx_memories_embedding ON kukuvaia.memories
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX idx_memories_search ON kukuvaia.memories
    USING gin(to_tsvector('english', description || ' ' || content));
```

### JSONB Conversation Format

```json
[
  {"role": "USER", "content": "Check validation for outline abc", "timestamp": "2026-04-10T14:30:00Z"},
  {"role": "ASSISTANT", "content": "Running validation...", "timestamp": "2026-04-10T14:30:02Z", "tool_calls": ["run_validation"]},
  {"role": "TOOL", "content": "{\"errors\": 3}", "tool_name": "run_validation", "timestamp": "2026-04-10T14:30:05Z"},
  {"role": "ASSISTANT", "content": "Found 3 errors...", "timestamp": "2026-04-10T14:30:06Z"}
]
```

**1 session = 1 row** vs current 4 rows. At 1000 users x 50 sessions = **50,000 rows** vs **5,000,000**.

### Custom ChatMemoryRepository (JSONB)

```java
public class JsonChatMemoryRepository implements ChatMemoryRepository {
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        jdbc.update("""
            INSERT INTO kukuvaia.conversations (session_id, messages, message_count)
            VALUES (?, ?::jsonb, ?)
            ON CONFLICT (session_id) DO UPDATE SET
                messages = EXCLUDED.messages, message_count = EXCLUDED.message_count, updated_at = NOW()
            """, conversationId, serialize(messages), messages.size());
    }
    // findByConversationId, deleteByConversationId — similar JSONB operations
}
```

### Memory Pipeline (3 Phases)

```
Session ends
     │
     ▼
EXTRACTION
  LLM analyzes conversation, extracts:
  • Episodic: "what happened" (distilled summary)
  • Semantic: "new facts" (entities, preferences)
  • Procedural: "what worked" (from user feedback)
  Embedding model generates vectors per entry
     │
     ▼
CONSOLIDATION (scheduled job)
  • New fact conflicts with existing? → UPDATE
  • Similar fact exists? → MERGE
  • New? → INSERT
  • Unused >30 days: relevance_score *= 0.8
  • Episodic >90 days: summarize → delete original
     │
     ▼
RETRIEVAL (per request)
  User sends message → embedding query
  Hybrid search: vector similarity + BM25 keyword
  Ranking: cosine_similarity * relevance_score
  Top-K: max 5-10 memories → context window
  Format: concise bullet points
```

### SmartMemoryAdvisor (Replaces PersistentMemoryAdvisor)

```
BEFORE (PersistentMemoryAdvisor):
  Load ALL user+feedback memories → dump into system prompt → noise

AFTER (SmartMemoryAdvisor):
  1. Embed user message
  2. Vector search against memories (cosine similarity)
  3. Rank by: similarity * relevance_score
  4. Take top 5-10
  5. Format as concise bullets
  6. Inject into system prompt
```

## Entity Relationships

```
User (users)
 │
 ├── 1:N → Session (sessions)
 │          │   id, user_id, name, metadata, status
 │          │
 │          ├── 1:1 → Conversation (conversations)
 │          │          session_id, messages (JSONB), summary
 │          │
 │          ├── 1:N → Plan (plans)
 │          │          session_id, task, steps (JSONB)
 │          │
 │          └── origin → Memory (memories)
 │                        session_id (nullable — where memory came from)
 │
 └── 1:N → Memory (memories)
              user_id, memory_type, category, embedding
              relevance_score, access_count, expires_at
```

## Module Dependencies

```
kukuvaia-memory                    ← standalone infrastructure
  └── Spring JDBC, pgvector, Spring AI (ChatMemoryRepository interface)

kukuvaia-core                      ← depends on :kukuvaia-memory
  └── Uses SmartMemoryAdvisor, MemoryRetrievalService, SessionRepository

kukuvaia-agents                    ← depends on :kukuvaia-memory + :kukuvaia-core
  └── Embabel agents inject SmartMemoryRepository, SessionRepository

kukuvaia-app                       ← depends on all
  └── Boot entry + Flyway migrations + application.yaml
```

Files migrating from kukuvaia-core to kukuvaia-memory:
- `agent/memory/MemoryEntry.java` → `memory/model/MemoryEntry.java` (extended)
- `agent/memory/MemoryRepository.java` → `memory/repository/SmartMemoryRepository.java` (extended)
- `agent/memory/PersistentMemoryAdvisor.java` → `memory/advisor/SmartMemoryAdvisor.java` (rewritten)
- `config/MemoryConfig.java` → `memory/config/MemoryModuleConfig.java` (extended)
- `tools/MemoryTools.java` → stays in kukuvaia-core (tools are domain, not infrastructure)
- `tools/PlanningTools.java` → stays in kukuvaia-core (tools are domain)

## Implementation Phases

See `.maister/docs/project/implementation-plan.md` Phase 4 for detailed steps.

### Phase 4A: Module + Schema (foundation)
- Create kukuvaia-memory module
- V4 Flyway migration (new tables)
- Move memory classes from core to memory module
- Update build.gradle dependency graph

### Phase 4B: JSONB Conversations + Session Model
- JsonChatMemoryRepository (implements Spring AI interface)
- UserRepository, SessionRepository
- Session-user linkage
- Data migration: SPRING_AI_CHAT_MEMORY → conversations JSONB

### Phase 4C: Semantic Search + SmartMemoryAdvisor
- pgvector extension in PostgreSQL
- Embedding generation (via Spring AI embedding model)
- Hybrid search (vector + BM25)
- SmartMemoryAdvisor replacing PersistentMemoryAdvisor
- Relevance scoring + access tracking

### Phase 4D: Extraction Pipeline + Lifecycle
- MemoryExtractionService (LLM extracts facts post-session)
- MemoryConsolidationService (scheduled: decay, merge, cleanup)
- TTL for episodic memories
- Three memory types: episodic, semantic, procedural

### Phase 4E: Embabel Agent Integration
- Embabel agents access SmartMemoryRepository via DI
- Cross-process context via Embabel ContextRepository backed by kukuvaia-memory
- Agents use memory for planning decisions

## Dependencies

- PostgreSQL with pgvector extension
- Spring AI 1.1.0+ (ChatMemoryRepository interface, EmbeddingModel)
- Spring Boot 3.4.4+ (JdbcTemplate, @Scheduled)
- pgvector Java library (`com.pgvector:pgvector`)
