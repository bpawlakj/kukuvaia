# Implementation Plan: Phase 4D — Memory Extraction Pipeline + Lifecycle Management

## Task Groups

### Group 1: Repository Extensions
**Specialty**: Data access
**Dependencies**: None

**Steps**:
- [ ] 1.1 Write tests for new ConversationRepository methods (updateSummary, findUnprocessed)
- [ ] 1.2 Add updateSummary() and findUnprocessed() to ConversationRepository
- [ ] 1.3 Write tests for new SmartMemoryRepository methods (findExpired, deleteExpired, batchDecayRelevance, findByEmbeddingSimilarity, updateRelevanceScore, findLowRelevance)
- [ ] 1.4 Add lifecycle methods to SmartMemoryRepository
- [ ] 1.5 Run repository tests

---

### Group 2: ConversationSummaryService
**Specialty**: LLM integration
**Dependencies**: Group 1

**Steps**:
- [ ] 2.1 Write tests for ConversationSummaryService — mock ChatClient, verify summary format, handles empty conversations
- [ ] 2.2 Create ConversationSummaryService: uses ChatClient to generate 1-2 sentence summary from conversation JSONB, stores via ConversationRepository.updateSummary()
- [ ] 2.3 Run tests

---

### Group 3: MemoryExtractionService
**Specialty**: LLM integration + data pipeline
**Dependencies**: Groups 1, 2

**Steps**:
- [ ] 3.1 Write tests for MemoryExtractionService — mock ChatClient + SmartMemoryRepository + EmbeddingService, verify 3 memory type extraction, conflict resolution, embedding generation
- [ ] 3.2 Create MemoryExtractionService:
  - extractFromConversation(String sessionId): load conversation JSONB → LLM extracts → categorize → generate embeddings → save with conflict resolution
  - Conflict resolution: semantic search for similar → cosine > 0.90 → UPDATE instead of INSERT
  - Set expires_at for episodic memories (90 days from now)
- [ ] 3.3 Run tests

---

### Group 4: MemoryConsolidationService
**Specialty**: Scheduled jobs
**Dependencies**: Group 1

**Steps**:
- [ ] 4.1 Write tests for MemoryConsolidationService — verify decay logic, TTL enforcement, duplicate merge
- [ ] 4.2 Create MemoryConsolidationService with @Scheduled(cron = "0 0 3 * * *"):
  - decayUnusedMemories(): relevance_score *= 0.8 for memories not accessed in 30 days
  - enforceExpiration(): delete expired episodic memories
  - mergeDuplicates(): find pairs with cosine > 0.95, merge content, delete duplicate
- [ ] 4.3 Update MemoryModuleConfig: add @EnableScheduling
- [ ] 4.4 Run tests

---

### Group 5: Integration + Final Verification
**Specialty**: Integration

**Steps**:
- [ ] 5.1 Verify @Scheduled job is registered (test Spring context loads)
- [ ] 5.2 `./gradlew clean build` — all modules pass
- [ ] 5.3 Verify no compilation warnings related to memory module
