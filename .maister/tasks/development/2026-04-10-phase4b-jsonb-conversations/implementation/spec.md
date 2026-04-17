# Specification: Phase 4B — JSONB Conversations + Session Model

## Scope
Replace Spring AI's `JdbcChatMemoryRepository` (row-per-message) with custom `JsonChatMemoryRepository` that stores entire conversations as JSONB in the `kukuvaia.conversations` table. Implement session auto-creation on first chat with user linkage.

## Out of Scope
- Phase 4C: pgvector semantic search
- Phase 4D: Extraction pipeline
- Phase 4E: Embabel integration

## Prerequisites (from Phase 4A — COMPLETE)
- kukuvaia-memory module exists with models, repositories, advisor, config
- DB tables: kukuvaia.users, kukuvaia.sessions, kukuvaia.conversations (JSONB)
- ConversationSnapshot record: sessionId, messages (String), summary, messageCount, tokenCount, updatedAt
- UserRepository, SessionRepository exist with basic CRUD
- MemoryModuleConfig creates ChatMemory bean wrapping ChatMemoryRepository
- application.yaml: initialize-schema: never

## What Changes

### 1. JsonChatMemoryRepository (NEW)
Implements `org.springframework.ai.chat.memory.ChatMemoryRepository`:
- `saveAll(conversationId, messages)` → serialize messages to JSON → UPSERT into conversations.messages JSONB
- `findByConversationId(conversationId)` → SELECT messages JSONB → deserialize to List<Message>
- `deleteByConversationId(conversationId)` → DELETE FROM conversations
- `findConversationIds()` → SELECT session_id FROM conversations

Message JSON format:
```json
[
  {"role": "USER", "content": "...", "timestamp": "ISO8601"},
  {"role": "ASSISTANT", "content": "...", "timestamp": "ISO8601", "toolCalls": [...]},
  {"role": "TOOL", "content": "...", "toolName": "...", "timestamp": "ISO8601"}
]
```

Uses Jackson ObjectMapper for serialization. Handles Spring AI Message types: UserMessage, AssistantMessage, SystemMessage, ToolResponseMessage.

### 2. ConversationRepository (NEW)
Dedicated repository for kukuvaia.conversations table CRUD:
- save(ConversationSnapshot) → UPSERT
- findBySessionId(sessionId) → Optional<ConversationSnapshot>
- deleteBySessionId(sessionId)
- All queries use `kukuvaia.conversations` schema prefix

### 3. SessionService (NEW)
Service layer for session lifecycle:
- `getOrCreateSession(conversationId, userId)` → finds existing session or creates new one
- Auto-generates session name from first message (truncated to 100 chars)
- Auto-creates user if not exists
- Links conversation_id → session_id (they're the same value)

### 4. MemoryModuleConfig (MODIFY)
- Replace `JdbcChatMemoryRepository` with `JsonChatMemoryRepository` as the ChatMemoryRepository bean
- Remove dependency on `spring-ai-starter-model-chat-memory-repository-jdbc` (no longer needed)
- Keep MessageWindowChatMemory(20) wrapping the new repository

### 5. build.gradle (MODIFY)
- Remove `spring-ai-starter-model-chat-memory-repository-jdbc` (replaced by custom impl)
- Keep spring-ai-client-chat (for ChatMemoryRepository interface)

## Acceptance Criteria
1. `./gradlew clean build` passes
2. All existing tests pass (no regressions)
3. JsonChatMemoryRepository correctly serializes/deserializes Spring AI Message types
4. Session auto-creation works (user + session created on first saveAll)
5. ConversationSnapshot stored as single JSONB row per session
6. No dependency on spring-ai-starter-model-chat-memory-repository-jdbc

## File Inventory
### Create
- `memory/repository/JsonChatMemoryRepository.java`
- `memory/repository/ConversationRepository.java`
- `memory/service/SessionService.java`
- Tests for all new classes

### Modify
- `memory/config/MemoryModuleConfig.java` — new ChatMemoryRepository bean
- `kukuvaia-memory/build.gradle` — remove JDBC chat memory starter
