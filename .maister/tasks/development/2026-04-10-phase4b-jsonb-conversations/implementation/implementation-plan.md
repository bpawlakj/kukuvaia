# Implementation Plan: Phase 4B — JSONB Conversations + Session Model

## Task Groups

### Group 1: ConversationRepository
**Specialty**: Data access
**Dependencies**: None (table exists from V4 migration)
**Files**: ConversationRepository.java, ConversationRepositoryTest.java

**Steps**:
- [ ] 1.1 Write tests for ConversationRepository — save/find/delete round-trips, JSONB handling, message_count update
- [ ] 1.2 Create `ConversationRepository.java` — JdbcTemplate CRUD for kukuvaia.conversations: save (UPSERT messages JSONB, message_count, token_count), findBySessionId, deleteBySessionId, findAllSessionIds
- [ ] 1.3 Run tests: `./gradlew :kukuvaia-memory:test --tests *ConversationRepository*`

**Tests**: 4-6 tests covering UPSERT, find, delete, JSONB serialization

---

### Group 2: JsonChatMemoryRepository
**Specialty**: Spring AI integration
**Dependencies**: Group 1
**Files**: JsonChatMemoryRepository.java, JsonChatMemoryRepositoryTest.java

**Steps**:
- [ ] 2.1 Write tests for JsonChatMemoryRepository — implements ChatMemoryRepository interface, serialize/deserialize UserMessage, AssistantMessage, SystemMessage, ToolResponseMessage
- [ ] 2.2 Create `JsonChatMemoryRepository.java` implementing `ChatMemoryRepository`:
  - saveAll(conversationId, messages): serialize List<Message> to JSON string via Jackson, delegate to ConversationRepository.save()
  - findByConversationId(conversationId): delegate to ConversationRepository.findBySessionId(), deserialize JSONB to List<Message>
  - deleteByConversationId(conversationId): delegate to ConversationRepository.deleteBySessionId()
  - findConversationIds(): delegate to ConversationRepository.findAllSessionIds()
  - Message serialization: handle role (USER/ASSISTANT/SYSTEM/TOOL), content, metadata, toolCalls
- [ ] 2.3 Run tests: `./gradlew :kukuvaia-memory:test --tests *JsonChatMemory*`

**Tests**: 5-8 tests covering all 4 ChatMemoryRepository methods + message type handling

---

### Group 3: SessionService
**Specialty**: Business logic
**Dependencies**: Groups 1-2
**Files**: SessionService.java, SessionServiceTest.java

**Steps**:
- [ ] 3.1 Write tests for SessionService — getOrCreateSession creates user+session if not exist, returns existing if found, auto-generates name
- [ ] 3.2 Create `SessionService.java` in ai.kukuvaia.memory.service:
  - getOrCreateSession(conversationId, userId): check SessionRepository.findById → if empty, create user (UserRepository.save) + session (SessionRepository.save) → return session
  - generateSessionName(firstMessage): truncate to 100 chars, strip markdown
- [ ] 3.3 Run tests: `./gradlew :kukuvaia-memory:test --tests *SessionService*`

**Tests**: 3-5 tests covering create-new, find-existing, name generation

---

### Group 4: Config + Build Updates
**Specialty**: Configuration
**Dependencies**: Groups 1-3
**Files**: MemoryModuleConfig.java, build.gradle

**Steps**:
- [ ] 4.1 Update `MemoryModuleConfig.java`: replace ChatMemoryRepository bean injection — use JsonChatMemoryRepository instead of JdbcChatMemoryRepository. Add @Bean for JsonChatMemoryRepository if needed (or rely on @Component scan).
- [ ] 4.2 Update `kukuvaia-memory/build.gradle`: remove `spring-ai-starter-model-chat-memory-repository-jdbc`, keep `spring-ai-client-chat` (has ChatMemoryRepository interface). Also update kukuvaia-core/build.gradle if it still has the old starter.
- [ ] 4.3 Verify: `./gradlew clean build` — full build passes with all tests
- [ ] 4.4 Verify: no remaining references to JdbcChatMemoryRepository in any Java files

**Tests**: Full build + all tests pass
