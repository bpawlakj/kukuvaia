# P04: Conversation Summarization -- Context Compression via Summaries

## Problem

kukuvaia-engine uses a `MessageWindowChatMemory` with a 20-message sliding window. When a conversation exceeds 20 messages, older messages are dropped silently. The LLM loses all context from earlier in the conversation -- it does not know what was discussed, what decisions were made, or what tasks were completed.

This creates two user-facing problems:
1. **Context amnesia:** The agent forgets earlier conversation context mid-session. A user who discussed architecture decisions in messages 1-15 finds the agent has no memory of them by message 25.
2. **Session resume failure:** When a user returns to an old session, the 20-message window contains only the tail end of the conversation. There is no summary of what happened before.

The `kukuvaia.conversations` table has a `summary TEXT` column (created in V2 migration) that is currently unused. The infrastructure for storing summaries exists -- the logic to generate and inject them does not.

## Current State

### Message window configuration

```java
// MemoryModuleConfig.java
private static final int DEFAULT_MAX_MESSAGES = 20;

@Bean
ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(chatMemoryRepository)
            .maxMessages(DEFAULT_MAX_MESSAGES)
            .build();
}
```

### Conversations table schema (V2 migration)

```sql
CREATE TABLE kukuvaia.conversations (
    session_id    VARCHAR(255) PRIMARY KEY REFERENCES kukuvaia.sessions(id),
    messages      JSONB NOT NULL DEFAULT '[]',
    summary       TEXT,              -- <-- EXISTS, UNUSED
    message_count INT DEFAULT 0,
    token_count   INT DEFAULT 0,
    updated_at    TIMESTAMP DEFAULT NOW()
);
```

### Memory extraction (existing)

`MemoryExtractionService` extracts durable **facts** (user preferences, project context) from conversations. This is different from summarization:
- **Extraction** = long-term cross-session memories (stored in `kukuvaia.memories`)
- **Summarization** = within-session context compression (stored in `kukuvaia.conversations.summary`)

Both are complementary. Extraction distills timeless facts. Summarization preserves session-specific context.

### SmartMemoryAdvisor

Injects persistent memories (from `kukuvaia.memories`) into the system prompt. Does NOT inject conversation summaries.

### MessageChatMemoryAdvisor

Spring AI's built-in advisor that loads the last N messages from `ChatMemoryRepository` and appends them to the conversation. This is the 20-message window.

### Key files

- `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/config/MemoryModuleConfig.java` -- ChatMemory bean
- `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/advisor/SmartMemoryAdvisor.java` -- persistent memory injection
- `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/extraction/MemoryExtractionService.java` -- fact extraction
- `kukuvaia-memory/src/main/java/ai/kukuvaia/memory/repository/SessionRepository.java` -- session data access
- `kukuvaia-core/src/main/java/ai/kukuvaia/config/ChatClientConfig.java` -- advisor chain
- `kukuvaia-core/src/main/java/ai/kukuvaia/agent/AgentService.java` -- chat orchestration
- `kukuvaia-app/src/main/resources/db/migration/V2__create_users_and_sessions.sql` -- conversations table

## Architecture

```
Chat request (message N)
        |
        v
ChatClient advisor chain:
  1. ProviderAuditLog (HIGHEST_PRECEDENCE)
  2. ToolResultSanitizingAdvisor (+1)
  3. SmartMemoryAdvisor (+5)          -- injects persistent memories
  4. ConversationSummaryAdvisor (+8)  -- NEW: injects session summary
  5. MessageChatMemoryAdvisor (+10)   -- injects last 20 messages
  6. ToolCallAdvisor                  -- tool calling loop

System prompt construction:
+-----------------------------------------------+
| Base persona prompt                            |
+-----------------------------------------------+
| SECURITY BOUNDARY (ToolResultSanitizingAdv.)  |
+-----------------------------------------------+
| PERSISTENT MEMORY (SmartMemoryAdvisor)         |
|   - User Profile: ...                          |
|   - Project Context: ...                       |
+-----------------------------------------------+
| CONVERSATION SUMMARY (ConversationSummaryAdv.) |  <-- NEW
|   This session began on 2026-04-10.            |
|   You discussed architecture decisions...      |
|   Key decisions: ...                           |
|   Current task: ...                            |
+-----------------------------------------------+
| Last 20 messages (MessageChatMemoryAdvisor)    |
+-----------------------------------------------+

Summarization trigger flow:
                                 AgentService.streamChat()
                                        |
                                  [chat succeeds]
                                        |
                         +---------- fork ---------+
                         |                         |
               MemoryExtractionService   ConversationSummarizationService
               (extract facts)           (update summary if needed)
                         |                         |
                  async, background          async, background
                         |                         |
                  save to memories          save to conversations.summary
```

### When to summarize

| Trigger | Condition | Rationale |
|---------|-----------|-----------|
| Message count threshold | `message_count % SUMMARIZE_EVERY == 0` (default: every 10 messages) | Keeps summary current without LLM cost on every message |
| Session resume | First message in a session with existing summary | Refresh stale summary with fresh context |
| Context overflow | Approaching token budget | Emergency compression when conversation is very long |
| Manual trigger | `/summarize` command | User-initiated summary refresh |

The primary trigger is the message count threshold. This balances cost (one cheap LLM call every 10 messages) with freshness.

### Summary storage model

The summary is a single text blob stored in `kukuvaia.conversations.summary`. It is NOT appended (no growing list of summaries). Each summarization replaces the previous summary with an updated version that incorporates the latest messages.

The summarization prompt receives:
1. The previous summary (if any)
2. The messages since the last summarization
3. Instructions to produce a concise updated summary

This is a "rolling summary" pattern -- each summary builds on the previous one, avoiding the need to re-read the entire conversation.

## Implementation

### Step 1: Create ConversationSummarizationService

**`kukuvaia-memory/src/main/java/ai/kukuvaia/memory/summarization/ConversationSummarizationService.java`:**

```java
package ai.kukuvaia.memory.summarization;

import ai.kukuvaia.memory.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Generates and updates conversation summaries for context compression.
 * Triggered after every N messages to keep the summary current.
 *
 * Uses a cheap model (same as extraction) to minimize cost.
 * Summary is stored in kukuvaia.conversations.summary column.
 */
@Service
public class ConversationSummarizationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizationService.class);
    private static final int SUMMARIZE_EVERY = 10;
    private static final int MIN_MESSAGES_FOR_SUMMARY = 6;

    private final ChatModel chatModel;
    private final ChatMemoryRepository chatMemoryRepository;
    private final SessionRepository sessionRepository;
    private final SummarizationConfig config;

    public ConversationSummarizationService(ChatModel chatModel,
                                             ChatMemoryRepository chatMemoryRepository,
                                             SessionRepository sessionRepository,
                                             SummarizationConfig config) {
        this.chatModel = chatModel;
        this.chatMemoryRepository = chatMemoryRepository;
        this.sessionRepository = sessionRepository;
        this.config = config;
    }

    /**
     * Check if summarization is needed and generate/update summary.
     * Called asynchronously after each chat turn.
     */
    public void summarizeIfNeeded(String sessionId) {
        try {
            var allMessages = chatMemoryRepository.findByConversationId(sessionId);
            int totalMessages = allMessages.size();

            if (totalMessages < MIN_MESSAGES_FOR_SUMMARY) {
                log.debug("Summarization skipped: only {} messages (min={})",
                        totalMessages, MIN_MESSAGES_FOR_SUMMARY);
                return;
            }

            int lastSummarizedAt = sessionRepository.getSummarizationCursor(sessionId);
            int messagesSinceSummary = totalMessages - lastSummarizedAt;

            int interval = config.getInterval() > 0 ? config.getInterval() : SUMMARIZE_EVERY;
            if (messagesSinceSummary < interval) {
                log.debug("Summarization skipped: {} messages since last summary (interval={})",
                        messagesSinceSummary, interval);
                return;
            }

            // Get existing summary
            String existingSummary = sessionRepository.getConversationSummary(sessionId);

            // Get messages since last summary
            var newMessages = allMessages.subList(Math.max(0, lastSummarizedAt), totalMessages);
            String transcript = formatTranscript(newMessages);

            // Generate summary
            String prompt = buildSummarizationPrompt(existingSummary, transcript);
            var response = chatModel.call(new Prompt(prompt));
            String newSummary = response.getResult().getOutput().getText();

            if (newSummary != null && !newSummary.isBlank()) {
                // Strip markdown code fences if present
                newSummary = newSummary.strip();
                if (newSummary.startsWith("```")) {
                    newSummary = newSummary.replaceAll("^```[a-z]*\\n?", "")
                                          .replaceAll("\\n?```$", "").strip();
                }

                sessionRepository.updateConversationSummary(sessionId, newSummary);
                sessionRepository.updateSummarizationCursor(sessionId, totalMessages);

                log.info("Conversation summarized: sessionId={}, messages={}, summaryLength={}",
                        sessionId, totalMessages, newSummary.length());
            }

        } catch (Exception e) {
            log.warn("Conversation summarization failed for session {}: {}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * Force-summarize a session (used by /summarize command or session resume).
     */
    public String forceSummarize(String sessionId) {
        var allMessages = chatMemoryRepository.findByConversationId(sessionId);
        if (allMessages.isEmpty()) {
            return "No messages to summarize.";
        }

        String existingSummary = sessionRepository.getConversationSummary(sessionId);
        String transcript = formatTranscript(allMessages);
        String prompt = buildSummarizationPrompt(existingSummary, transcript);

        var response = chatModel.call(new Prompt(prompt));
        String summary = response.getResult().getOutput().getText();

        if (summary != null && !summary.isBlank()) {
            summary = summary.strip();
            sessionRepository.updateConversationSummary(sessionId, summary);
            sessionRepository.updateSummarizationCursor(sessionId, allMessages.size());
        }

        return summary;
    }

    private String formatTranscript(List<Message> messages) {
        var sb = new StringBuilder();
        for (var msg : messages) {
            if (msg instanceof UserMessage um) {
                sb.append("USER: ").append(um.getText()).append("\n\n");
            } else if (msg instanceof AssistantMessage am) {
                String text = am.getText();
                if (text != null && text.length() > 300) {
                    text = text.substring(0, 300) + "... [truncated]";
                }
                sb.append("ASSISTANT: ").append(text).append("\n\n");
            }
        }
        return sb.toString();
    }

    private String buildSummarizationPrompt(String existingSummary, String transcript) {
        String previousContext = (existingSummary != null && !existingSummary.isBlank())
                ? "Previous summary:\n" + existingSummary + "\n\n"
                : "No previous summary (this is the first summarization).\n\n";

        return """
                You are a conversation summarizer. Your job is to produce a concise, factual summary \
                of the conversation so far. This summary will be injected into the system prompt \
                to give the assistant context about earlier conversation.

                ## Guidelines
                - Focus on: decisions made, tasks completed, tasks in progress, key questions asked
                - Preserve: specific names, technologies, file paths, numbers mentioned
                - Omit: greetings, filler, repeated explanations
                - Keep the summary under 500 words
                - Write in third person ("The user asked...", "The assistant suggested...")
                - If updating a previous summary, incorporate new information and remove outdated details

                ## Previous context
                %s
                ## Recent conversation
                %s
                ## Output
                Return ONLY the updated summary text. No markdown headers, no explanation, no preamble.
                """.formatted(previousContext, transcript);
    }
}
```

### Step 2: Create SummarizationConfig

**`kukuvaia-memory/src/main/java/ai/kukuvaia/memory/summarization/SummarizationConfig.java`:**

```java
package ai.kukuvaia.memory.summarization;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for conversation summarization.
 */
@Component
@ConfigurationProperties(prefix = "kukuvaia.summarization")
public class SummarizationConfig {

    private int interval = 10;           // Summarize every N messages
    private int minMessages = 6;         // Minimum messages before first summary
    private int maxSummaryTokens = 500;  // Max tokens for summary generation
    private boolean enabled = true;

    public int getInterval() { return interval; }
    public void setInterval(int interval) { this.interval = interval; }

    public int getMinMessages() { return minMessages; }
    public void setMinMessages(int minMessages) { this.minMessages = minMessages; }

    public int getMaxSummaryTokens() { return maxSummaryTokens; }
    public void setMaxSummaryTokens(int maxSummaryTokens) { this.maxSummaryTokens = maxSummaryTokens; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
```

### Step 3: Create ConversationSummaryAdvisor

This advisor runs in the ChatClient advisor chain and injects the stored summary into the system prompt.

**`kukuvaia-memory/src/main/java/ai/kukuvaia/memory/advisor/ConversationSummaryAdvisor.java`:**

```java
package ai.kukuvaia.memory.advisor;

import ai.kukuvaia.memory.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Injects stored conversation summary into the system prompt.
 * Runs after SmartMemoryAdvisor (persistent memories) and before
 * MessageChatMemoryAdvisor (last N messages).
 *
 * The summary bridges the gap between long-term memory (cross-session facts)
 * and short-term memory (last 20 messages), providing mid-session context
 * that would otherwise be lost when the message window scrolls.
 */
@Component
public class ConversationSummaryAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryAdvisor.class);
    private static final String CTX_SESSION_ID = "chat_memory_conversation_id";

    private final SessionRepository sessionRepository;

    public ConversationSummaryAdvisor(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public int getOrder() {
        // After SmartMemoryAdvisor (+5), before MessageChatMemoryAdvisor (default)
        return Ordered.HIGHEST_PRECEDENCE + 8;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String sessionId = (String) request.context().getOrDefault(CTX_SESSION_ID, null);
        if (sessionId == null) {
            return request;
        }

        String summary = sessionRepository.getConversationSummary(sessionId);
        if (summary == null || summary.isBlank()) {
            return request;
        }

        String summaryBlock = """

                --- CONVERSATION SUMMARY (earlier in this session) ---
                %s
                --- END SUMMARY ---
                Note: The summary above covers messages from earlier in this session \
                that are no longer in your message history. Use it for context continuity.
                """.formatted(summary);

        log.debug("Injected conversation summary for session {} ({} chars)",
                sessionId, summary.length());

        return request.mutate()
                .prompt(request.prompt().augmentSystemMessage(summaryBlock))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }
}
```

### Step 4: Add summarization cursor to sessions table

**`kukuvaia-app/src/main/resources/db/migration/V7__add_summarization_cursor.sql`:**

```sql
-- Summarization cursor: tracks which messages have been included in the summary.
ALTER TABLE kukuvaia.sessions
    ADD COLUMN IF NOT EXISTS summarization_cursor_idx INT DEFAULT 0;
```

### Step 5: Update SessionRepository

Add methods to read/write summary and summarization cursor.

**`kukuvaia-memory/src/main/java/ai/kukuvaia/memory/repository/SessionRepository.java` -- add methods:**

```java
/**
 * Get the stored conversation summary for a session.
 */
public String getConversationSummary(String sessionId) {
    try {
        return jdbc.queryForObject(
                "SELECT summary FROM kukuvaia.conversations WHERE session_id = ?",
                String.class, sessionId);
    } catch (EmptyResultDataAccessException e) {
        return null;
    }
}

/**
 * Update the conversation summary.
 */
public void updateConversationSummary(String sessionId, String summary) {
    int updated = jdbc.update(
            "UPDATE kukuvaia.conversations SET summary = ?, updated_at = NOW() WHERE session_id = ?",
            summary, sessionId);
    if (updated == 0) {
        // Conversation row might not exist yet -- upsert
        jdbc.update("""
                INSERT INTO kukuvaia.conversations (session_id, summary, updated_at)
                VALUES (?, ?, NOW())
                ON CONFLICT (session_id) DO UPDATE SET summary = ?, updated_at = NOW()
                """, sessionId, summary, summary);
    }
}

/**
 * Get the summarization cursor (messages included in last summary).
 */
public int getSummarizationCursor(String sessionId) {
    try {
        Integer cursor = jdbc.queryForObject(
                "SELECT summarization_cursor_idx FROM kukuvaia.sessions WHERE id = ?",
                Integer.class, sessionId);
        return cursor != null ? cursor : 0;
    } catch (EmptyResultDataAccessException e) {
        return 0;
    }
}

/**
 * Update the summarization cursor.
 */
public void updateSummarizationCursor(String sessionId, int cursor) {
    jdbc.update("UPDATE kukuvaia.sessions SET summarization_cursor_idx = ? WHERE id = ?",
            cursor, sessionId);
}
```

### Step 6: Wire ConversationSummaryAdvisor into ChatClient

**`kukuvaia-core/src/main/java/ai/kukuvaia/config/ChatClientConfig.java`:**

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder,
                      ChatMemory chatMemory,
                      ProviderAuditLog providerAuditLog,
                      ToolResultSanitizingAdvisor toolResultSanitizingAdvisor,
                      SmartMemoryAdvisor smartMemoryAdvisor,
                      ConversationSummaryAdvisor conversationSummaryAdvisor,
                      Collection<ToolCallbackProvider> toolCallbackProviders) {

    var clientBuilder = builder
            .defaultAdvisors(
                    providerAuditLog,                   // +0   (HIGHEST_PRECEDENCE)
                    toolResultSanitizingAdvisor,         // +1
                    smartMemoryAdvisor,                  // +5
                    conversationSummaryAdvisor,           // +8   (NEW)
                    MessageChatMemoryAdvisor.builder(chatMemory).build()  // +10
            );

    for (ToolCallbackProvider provider : toolCallbackProviders) {
        clientBuilder.defaultToolCallbacks(provider.getToolCallbacks());
    }

    return clientBuilder.build();
}
```

### Step 7: Trigger summarization after chat

**`kukuvaia-core/src/main/java/ai/kukuvaia/agent/AgentService.java`:**

Add `ConversationSummarizationService` dependency. Trigger summarization alongside extraction in the async post-chat hook.

```java
private final ConversationSummarizationService summarizationService;

// In streamChat(), after successful response:
triggerExtraction(USER_ID, sessionId);
triggerSummarization(sessionId);

private void triggerSummarization(String sessionId) {
    CompletableFuture.runAsync(
            () -> summarizationService.summarizeIfNeeded(sessionId),
            extractionExecutor  // reuse single-thread executor
    ).exceptionally(ex -> {
        log.warn("Async summarization failed: {}", ex.getMessage());
        return null;
    });
}
```

Note: Both extraction and summarization share the single-thread `memoryExtractionExecutor`. This means they run serially, which is intentional -- prevents flooding the LLM with concurrent background requests.

### Step 8: Create /summarize command (optional)

**`kukuvaia-core/src/main/java/ai/kukuvaia/commands/SummarizeCommand.java`:**

```java
package ai.kukuvaia.commands;

import ai.kukuvaia.memory.summarization.ConversationSummarizationService;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /summarize — force-generate a conversation summary for the current session.
 */
@Component
public class SummarizeCommand implements SlashCommand {

    private final ConversationSummarizationService summarizationService;

    public SummarizeCommand(ConversationSummarizationService summarizationService) {
        this.summarizationService = summarizationService;
    }

    @Override
    public String name() { return "summarize"; }

    @Override
    public String description() {
        return "Generate or refresh the conversation summary for context compression";
    }

    @Override
    public List<OutputBlock> execute(String args, String sessionId) {
        String summary = summarizationService.forceSummarize(sessionId);
        if (summary == null || summary.isBlank()) {
            return List.of(new TextBlock("No messages to summarize.", null));
        }
        return List.of(
                new TextBlock("Conversation summary updated:", null),
                new TextBlock(summary, "muted")
        );
    }
}
```

## Configuration

### application.yaml additions

```yaml
kukuvaia:
  summarization:
    enabled: true
    interval: 10          # Summarize every N messages
    min-messages: 6       # Minimum messages before first summary
    max-summary-tokens: 500
```

### Cost estimate

Summarization uses the same `ChatModel` as memory extraction (injected by Spring). To use a cheap model specifically for summarization, use the model routing from P03:

```java
// Future: resolve cheap model for summarization
ChatModel cheapModel = modelRoutingService.resolvePrimary("worker");
```

Estimated cost per summarization call:
- Input: ~300-500 tokens (transcript + previous summary + prompt)
- Output: ~200-400 tokens (summary)
- With nova-lite: ~$0.0001 per call
- At 10-message intervals in an active session: ~$0.001 per 100-message session

## Dependencies

No new external dependencies. All functionality uses existing:

| Component | Purpose |
|-----------|---------|
| `ChatModel` (existing bean) | LLM call for summarization |
| `ChatMemoryRepository` (Spring AI) | Read conversation messages |
| `SessionRepository` (existing) | Read/write summary and cursor |
| `JdbcTemplate` (existing) | Database access |

New internal classes:

| Class | Module | Purpose |
|-------|--------|---------|
| `ConversationSummarizationService` | kukuvaia-memory | Summarization logic |
| `SummarizationConfig` | kukuvaia-memory | Config properties |
| `ConversationSummaryAdvisor` | kukuvaia-memory | System prompt injection |
| `SummarizeCommand` | kukuvaia-core | /summarize slash command |
| `V7__add_summarization_cursor.sql` | kukuvaia-app | Migration |

## Verification

### 1. Build passes

```bash
./gradlew clean build
```

### 2. Migration applies cleanly

```bash
./gradlew :kukuvaia-app:bootRun
# Check logs for: "Successfully applied 1 migration to schema 'kukuvaia' (V7__add_summarization_cursor)"
```

### 3. Summary generated after N messages

```bash
# Send 12 messages to a session
for i in $(seq 1 12); do
  curl -X POST http://localhost:8080/api/chat \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"test-sum-1\",\"message\":\"Message $i about Java Spring Boot\"}"
  sleep 2
done

# Check if summary was stored
psql -U kukuvaia -d kukuvaia -c \
  "SELECT length(summary), summary FROM kukuvaia.conversations WHERE session_id = 'test-sum-1'"
# Expected: non-null summary after message 10+
```

### 4. Summary injected into system prompt

Enable `DEBUG` logging for `ConversationSummaryAdvisor`:

```yaml
logging:
  level:
    ai.kukuvaia.memory.advisor.ConversationSummaryAdvisor: DEBUG
```

Then send a message to the session and check logs for:
```
Injected conversation summary for session test-sum-1 (XXX chars)
```

### 5. /summarize command works

```bash
curl -X POST http://localhost:8080/api/commands/summarize \
  -H "Content-Type: application/json" \
  -d '{"args":"","sessionId":"test-sum-1"}'
# Expected: JSON with summary text
```

### 6. Unit test: ConversationSummarizationService

```java
@Test
@DisplayName("summarizeIfNeeded generates summary when message count exceeds interval")
void summarizeIfNeeded_exceedsInterval_generatesSummary() {
    // Given: 12 messages in session, cursor at 0, interval = 10
    when(chatMemoryRepository.findByConversationId("sess-1"))
            .thenReturn(createMessages(12));
    when(sessionRepository.getSummarizationCursor("sess-1")).thenReturn(0);
    when(sessionRepository.getConversationSummary("sess-1")).thenReturn(null);
    when(chatModel.call(any(Prompt.class)))
            .thenReturn(createResponse("Summary of the conversation."));

    summarizationService.summarizeIfNeeded("sess-1");

    verify(sessionRepository).updateConversationSummary("sess-1", "Summary of the conversation.");
    verify(sessionRepository).updateSummarizationCursor("sess-1", 12);
}

@Test
@DisplayName("summarizeIfNeeded skips when below interval threshold")
void summarizeIfNeeded_belowInterval_skips() {
    when(chatMemoryRepository.findByConversationId("sess-1"))
            .thenReturn(createMessages(8));
    when(sessionRepository.getSummarizationCursor("sess-1")).thenReturn(0);

    summarizationService.summarizeIfNeeded("sess-1");

    verify(sessionRepository, never()).updateConversationSummary(any(), any());
}

@Test
@DisplayName("summarizeIfNeeded includes previous summary in prompt")
void summarizeIfNeeded_hasPreviousSummary_includesIt() {
    when(chatMemoryRepository.findByConversationId("sess-1"))
            .thenReturn(createMessages(22));
    when(sessionRepository.getSummarizationCursor("sess-1")).thenReturn(10);
    when(sessionRepository.getConversationSummary("sess-1"))
            .thenReturn("Previous summary about Java project.");
    when(chatModel.call(any(Prompt.class)))
            .thenReturn(createResponse("Updated summary."));

    summarizationService.summarizeIfNeeded("sess-1");

    // Verify the prompt includes previous summary
    var promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    String promptText = promptCaptor.getValue().getInstructions().get(0).getText();
    assertThat(promptText).contains("Previous summary about Java project.");
}
```

### 7. Unit test: ConversationSummaryAdvisor

```java
@Test
@DisplayName("before injects summary into system prompt when available")
void before_summaryExists_injectsIntoSystemPrompt() {
    when(sessionRepository.getConversationSummary("sess-1"))
            .thenReturn("The user discussed Spring Boot configuration.");

    ChatClientRequest request = createRequestWithSessionId("sess-1");
    ChatClientRequest result = advisor.before(request, chain);

    String systemPrompt = extractSystemMessage(result);
    assertThat(systemPrompt).contains("CONVERSATION SUMMARY");
    assertThat(systemPrompt).contains("The user discussed Spring Boot configuration.");
}

@Test
@DisplayName("before passes through when no summary exists")
void before_noSummary_passesThrough() {
    when(sessionRepository.getConversationSummary("sess-1")).thenReturn(null);

    ChatClientRequest request = createRequestWithSessionId("sess-1");
    ChatClientRequest result = advisor.before(request, chain);

    assertThat(result).isSameAs(request); // No modification
}
```

## Effort Estimate

| Task | Effort | Notes |
|------|--------|-------|
| Create SummarizationConfig (Step 2) | 0.5h | Config properties bean |
| Create ConversationSummarizationService (Step 1) | 2h | Core logic + unit tests |
| Create ConversationSummaryAdvisor (Step 3) | 1h | Advisor + unit test |
| V7 migration (Step 4) | 0.5h | SQL + verify |
| Update SessionRepository (Step 5) | 1h | New methods + tests |
| Wire advisor into ChatClient (Step 6) | 0.5h | Config change |
| Trigger from AgentService (Step 7) | 0.5h | Async hook |
| Create /summarize command (Step 8) | 0.5h | Slash command |
| Integration testing | 1h | End-to-end flow |
| **Total** | **~7.5h** | |

## Priority & Prerequisites

**Priority:** Medium-High -- directly impacts conversation quality for sessions exceeding 20 messages (common in development sessions).

**Prerequisites:**
- `kukuvaia.conversations` table must exist (V2 migration -- already applied).
- `SessionRepository` must be accessible from `kukuvaia-memory` module. Currently, `SessionRepository` is in `kukuvaia-memory`. This is correct.
- The `ChatModel` bean must be available for summarization calls. Currently, only one `ChatModel` is auto-configured. If P03 (model fallback) is implemented first, summarization can use a cheap model explicitly.

**Sequencing:**
- Independent of P01 (observability), P02 (structured output), and P05 (eval pipeline).
- Benefits from P03 (model fallback chain): can route summarization to the cheapest model via `modelRoutingService.resolvePrimary("worker")`. Without P03, summarization uses the same model as interactive chat (more expensive).
- The `ConversationSummaryAdvisor` must be ordered between `SmartMemoryAdvisor` (+5) and `MessageChatMemoryAdvisor`. The ordering value (+8) is chosen to fit in this gap.

**Risks:**
- **Summary quality:** LLM-generated summaries may miss important details or hallucinate. Mitigation: the summary is supplementary context, not the sole source of truth. The 20-message window still provides exact recent messages.
- **Cost:** One extra LLM call every 10 messages. With a cheap model (nova-lite at ~$0.0001/call), this is negligible. With the primary model, it adds ~5% cost overhead.
- **Race condition:** Summarization and extraction both run on the same single-thread executor. They cannot race, but one may delay the other. In practice, both are fast (sub-second with cheap models).
- **Summary drift:** Over many summarization cycles, the rolling summary may drift from the original conversation. Mitigation: the force-summarize command (`/summarize`) re-processes the full conversation.

---

## Koog-Inspired Enhancement: Adaptive Summarization Interval (2026-04-15)

After evaluating Koog AI's history compression strategies (see `docs/analyzes/koog-ai-evaluation.md`), one enhancement is added to P04.

### Context

Koog offers 4 compression strategies: WholeHistory, FromLastNMessages(N), Chunked(N), RetrieveFactsFromHistory. Kukuvaia already covers 3 of 4 via existing mechanisms (MessageWindowChatMemory, MemoryExtractionService, and this P04 plan). The Chunked strategy is not needed — rolling summary is superior for our use case (narrative coherence, no recombination).

The useful idea from Koog: **different sessions need different compression intensity**. Rather than implementing multiple strategy classes, a simple adaptive interval achieves the same result.

### Change

In `ConversationSummarizationService.summarizeIfNeeded()`, replace the fixed `config.getInterval()` with adaptive logic:

```java
/**
 * Compute summarization interval based on session length.
 * Short sessions: skip (window covers all).
 * Normal sessions: default interval.
 * Long sessions: more frequent to keep summary fresh.
 */
private int computeInterval(int totalMessages) {
    if (totalMessages < 20) return Integer.MAX_VALUE; // window covers all, skip
    if (totalMessages < 50) return config.getInterval(); // default: 10
    return Math.max(5, config.getInterval() / 2);        // aggressive for long sessions
}
```

Then in `summarizeIfNeeded()`:

```java
// Replace:
// int interval = config.getInterval() > 0 ? config.getInterval() : SUMMARIZE_EVERY;

// With:
int interval = computeInterval(totalMessages);
```

### Behavior

| Session length | Interval | Rationale |
|---|---|---|
| < 20 messages | Never | MessageWindowChatMemory covers everything |
| 20-49 messages | Every 10 (default) | Standard P04 behavior |
| 50+ messages | Every 5 | Long sessions need fresher summaries |

### Effort

~0.5h additional on top of P04's ~7.5h = **~8h total**.

### Why skip Chunked(N)

Koog's Chunked strategy divides history into fixed chunks, summarizes each independently, then recombines. Kukuvaia's rolling summary achieves the same compression with less complexity: each summary builds on the previous, maintaining narrative coherence. No recombination needed. Single-thread executor avoids LLM flooding.

## Migration Number Note

The original plan references `V7__add_summarization_cursor.sql`. As of 2026-04-15, migrations V7-V11 already exist. The actual migration should be **`V12__add_summarization_cursor.sql`**. Update the migration filename accordingly when implementing.
