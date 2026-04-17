# Implementation Plan: Phase 4C — pgvector Semantic Search + SmartMemoryAdvisor

## Task Groups

### Group 1: pgvector Setup + V5 Migration

**Specialty**: Database
**Dependencies**: None

**Steps**:
- [ ] 1.1 Create V5__enable_pgvector.sql migration: CREATE EXTENSION IF NOT EXISTS vector; CREATE INDEX idx_memories_embedding
- [ ] 1.2 Update kukuvaia-memory/build.gradle: add `implementation 'com.pgvector:pgvector:0.1.6'`
- [ ] 1.3 Verify: `./gradlew clean build -x test` passes

---

### Group 2: EmbeddingService

**Specialty**: Spring AI integration
**Dependencies**: Group 1

**Steps**:
- [ ] 2.1 Write tests for EmbeddingService — mock EmbeddingModel, verify embed() returns float[], handles empty text
- [ ] 2.2 Create `EmbeddingService.java` in ai.kukuvaia.memory.service: wraps Spring AI EmbeddingModel, embed(String text) → float[]
- [ ] 2.3 Run tests

---

### Group 3: SmartMemoryRepository Vector Methods

**Specialty**: Data access
**Dependencies**: Group 1

**Steps**:
- [ ] 3.1 Write tests for new vector methods — semanticSearch, hybridSearch, updateAccessStats, saveWithEmbedding
- [ ] 3.2 Add methods to SmartMemoryRepository:
  - saveWithEmbedding(userId, category, name, description, content, memoryType, relevanceScore, sessionId, float[] embedding) — INSERT with embedding vector
  - semanticSearch(userId, float[] queryEmbedding, int limit) — SELECT ORDER BY embedding <=> ?::vector LIMIT ?
  - hybridSearch(userId, String query, float[] queryEmbedding, int limit) — combines tsvector rank + cosine similarity
  - updateAccessStats(UUID memoryId) — UPDATE access_count = access_count + 1, last_accessed_at = NOW()
- [ ] 3.3 Run tests

---

### Group 4: MemoryRetrievalService

**Specialty**: Business logic
**Dependencies**: Groups 2, 3

**Steps**:
- [ ] 4.1 Write tests for MemoryRetrievalService — mock EmbeddingService + SmartMemoryRepository, verify top-K retrieval, formatting, access stats update
- [ ] 4.2 Create `MemoryRetrievalService.java` in ai.kukuvaia.memory.service:
  - retrieve(String userMessage, String userId, int maxResults) → List<String> formatted bullets
  - Embeds message → hybridSearch → rank by similarity*relevance → format top-K → update access stats
- [ ] 4.3 Run tests

---

### Group 5: SmartMemoryAdvisor Rewrite

**Specialty**: Spring AI advisor
**Dependencies**: Group 4

**Steps**:
- [ ] 5.1 Write tests for rewritten SmartMemoryAdvisor — verify it calls MemoryRetrievalService, injects top-K results, handles empty results, preserves advisor order
- [ ] 5.2 Rewrite SmartMemoryAdvisor.before():
  - Extract user message from request
  - Call memoryRetrievalService.retrieve(message, userId, 10)
  - If non-empty: inject as concise system prompt section "### Relevant Context"
  - Keep advisor order HIGHEST_PRECEDENCE + 5
- [ ] 5.3 Update ChatClientConfig if needed (SmartMemoryAdvisor constructor changed)
- [ ] 5.4 Run full test suite: `./gradlew clean build`

---

### Group 6: Final Verification

**Specialty**: Integration

**Steps**:
- [ ] 6.1 `./gradlew clean build` — all modules pass
- [ ] 6.2 Verify SmartMemoryAdvisor no longer loads ALL memories (grep for findByUserAndCategory in advisor)
- [ ] 6.3 Verify pgvector dependency present in build
