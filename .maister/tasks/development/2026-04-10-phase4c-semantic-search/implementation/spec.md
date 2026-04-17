# Specification: Phase 4C — pgvector Semantic Search + SmartMemoryAdvisor

## Scope

Add pgvector extension for vector similarity search. Generate embeddings when memories are saved. Rewrite SmartMemoryAdvisor to retrieve top-K relevant memories by semantic similarity instead of loading all user+feedback memories.

## Out of Scope

- Phase 4D: Extraction pipeline (memory auto-creation from conversations)
- Phase 4E: Embabel agent integration

## Prerequisites (from Phase 4A+4B — COMPLETE)

- kukuvaia-memory module with SmartMemoryRepository, SmartMemoryAdvisor
- DB: kukuvaia.memories table with embedding vector(1536) column (from V4 migration — column exists but empty)
- MemoryEntry record has embedding field (null until this phase populates it)
- application.yaml has Spring AI OpenAI config (can be used for embedding model)

## What Changes

### 1. V5 Migration — pgvector Extension + Index

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE INDEX IF NOT EXISTS idx_memories_embedding ON kukuvaia.memories 
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

Note: V4 added the `embedding vector(1536)` column. V5 enables the extension and creates the vector index.

### 2. EmbeddingService (NEW)

Service wrapping Spring AI's EmbeddingModel:
- `embed(String text)` → float[] (1536-dimensional vector)
- Uses same LLM provider as chat (OpenAI-compatible embedding endpoint)
- Async-capable for batch processing

### 3. SmartMemoryRepository (MODIFY)

Add methods:
- `saveWithEmbedding(...)` — save memory + embedding vector
- `semanticSearch(userId, float[] queryEmbedding, int limit)` → ORDER BY embedding <=> ? LIMIT ?
- `hybridSearch(userId, String query, float[] queryEmbedding, int limit)` → combines vector similarity + BM25 keyword score
- `updateAccessStats(UUID memoryId)` → access_count++, last_accessed_at = NOW()

### 4. MemoryRetrievalService (NEW)

Orchestrates retrieval:
- Input: user message + userId
- Embed user message via EmbeddingService
- Call SmartMemoryRepository.hybridSearch()
- Rank results by: cosine_similarity * relevance_score
- Return top 5-10 memories, formatted as concise bullets
- Update access stats for returned memories

### 5. SmartMemoryAdvisor (REWRITE)

Replace "load all user+feedback" with "semantic top-K":
- Before each LLM call: get user message → MemoryRetrievalService.retrieve(message, userId)
- Inject top-K results into system prompt (concise bullet format)
- Keep advisor order: HIGHEST_PRECEDENCE + 5

### 6. build.gradle (MODIFY)

- Add: `implementation 'com.pgvector:pgvector:0.1.6'`
- Add: Spring AI embedding model dependency if not already present

## Acceptance Criteria

1. `./gradlew clean build` passes
2. SmartMemoryAdvisor injects only relevant memories (not all)
3. Embedding generation works via EmbeddingService
4. Hybrid search returns semantically relevant results
5. Access stats updated on retrieval
6. V5 migration enables pgvector and creates index

## File Inventory

### Create

- `memory/service/EmbeddingService.java`
- `memory/service/MemoryRetrievalService.java`
- V5__enable_pgvector.sql
- Tests for new classes

### Modify

- `memory/repository/SmartMemoryRepository.java` — add vector search methods
- `memory/advisor/SmartMemoryAdvisor.java` — rewrite for top-K
- `kukuvaia-memory/build.gradle` — add pgvector dependency
