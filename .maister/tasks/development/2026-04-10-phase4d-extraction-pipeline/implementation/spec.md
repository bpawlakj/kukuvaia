# Specification: Phase 4D — Memory Extraction Pipeline + Lifecycle Management

## Scope
Auto-extract knowledge from completed conversations using LLM. Classify into episodic/semantic/procedural memory types. Implement lifecycle management: relevance decay, episodic compaction, TTL enforcement, similar memory merge.

## Out of Scope
- Phase 4E: Embabel agent integration

## Prerequisites (from Phases 4A-4C)
- kukuvaia-memory module with SmartMemoryRepository (with vector search), EmbeddingService, ConversationRepository
- DB: kukuvaia.memories with memory_type, relevance_score, access_count, expires_at, embedding columns
- DB: kukuvaia.conversations with messages JSONB, summary

## What Changes

### 1. MemoryExtractionService (NEW)
Extracts memories from a completed conversation:
- Triggered after session ends or after configurable N messages
- Uses LLM (via Spring AI ChatClient) to analyze conversation
- Extracts three types:
  - **Episodic**: "what happened" — distilled summary of the interaction, includes session context. Stored with expires_at (90 days default).
  - **Semantic**: "facts learned" — entities, user preferences, project details. Upserted (conflict resolution with existing).
  - **Procedural**: "patterns learned" — from user corrections/feedback. Updated by merging with existing procedural memories.
- Generates embedding for each extracted memory via EmbeddingService
- Saves via SmartMemoryRepository.saveWithEmbedding()

LLM prompt structure:
```
Analyze this conversation and extract memories:
1. EPISODIC: What happened? (1-2 sentence summary)
2. SEMANTIC: What new facts were learned? (list)
3. PROCEDURAL: What behavior was corrected or confirmed? (list)

Format as JSON:
{"episodic": [{"name": "...", "description": "...", "content": "..."}],
 "semantic": [...], "procedural": [...]}
```

Conflict resolution:
- Before saving semantic memory: check if similar exists (cosine similarity > 0.90)
- If similar: UPDATE existing with merged content
- If not: INSERT new

### 2. ConversationSummaryService (NEW)
Generates concise conversation summary:
- Called after session ends
- Uses LLM to create 1-2 sentence summary
- Stores in conversations.summary column
- Summary used for session listing in UI

### 3. MemoryConsolidationService (NEW)
Scheduled jobs for memory lifecycle:
- `@Scheduled(cron = "0 0 3 * * *")` — runs daily at 3 AM
- **Relevance decay**: memories not accessed in 30 days → relevance_score *= 0.8
- **Episodic compaction**: episodic memories >90 days → summarize multiple into one → delete originals
- **TTL enforcement**: DELETE WHERE expires_at < NOW()
- **Duplicate merge**: memories with cosine_similarity > 0.95 → consolidate into one

### 4. ConversationRepository (MODIFY)
Add methods:
- `updateSummary(sessionId, summary)` — UPDATE conversations SET summary = ? WHERE session_id = ?
- `findUnprocessed(limit)` — find conversations without summary (summary IS NULL) for batch processing

### 5. SmartMemoryRepository (MODIFY)
Add methods:
- `findExpired()` → SELECT WHERE expires_at < NOW()
- `findLowRelevance(threshold)` → SELECT WHERE relevance_score < threshold
- `findByEmbeddingSimilarity(embedding, threshold)` → for duplicate detection
- `updateRelevanceScore(id, newScore)` → UPDATE relevance_score
- `deleteExpired()` → DELETE WHERE expires_at IS NOT NULL AND expires_at < NOW()
- `batchDecayRelevance(daysSinceAccess, factor)` → UPDATE SET relevance_score = relevance_score * factor WHERE last_accessed_at < NOW() - interval

### 6. MemoryModuleConfig (MODIFY)
- Add @EnableScheduling if not present (for @Scheduled jobs)
- Add bean for MemoryExtractionService and ConversationSummaryService

## Acceptance Criteria
1. `./gradlew clean build` passes
2. MemoryExtractionService extracts 3 memory types from conversation JSON
3. ConversationSummaryService generates concise summaries
4. MemoryConsolidationService decay/TTL/merge logic works correctly
5. Conflict resolution merges similar memories instead of duplicating
6. Scheduled jobs are registered (verifiable via Spring context)

## File Inventory
### Create
- `memory/service/MemoryExtractionService.java`
- `memory/service/ConversationSummaryService.java`
- `memory/service/MemoryConsolidationService.java`
- Tests for all new classes

### Modify
- `memory/repository/ConversationRepository.java` — add updateSummary, findUnprocessed
- `memory/repository/SmartMemoryRepository.java` — add lifecycle methods
- `memory/config/MemoryModuleConfig.java` — @EnableScheduling
