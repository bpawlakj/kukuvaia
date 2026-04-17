package ai.kukuvaia.memory.model;

import java.time.Instant;

/**
 * Snapshot of a session's conversation state including message history and summary.
 * Maps to the {@code conversations} table in {@code kukuvaia} schema.
 * The {@code messages} field holds raw JSONB; deserialization happens in the repository layer.
 */
public record ConversationSnapshot(
        String sessionId,
        String messages,
        String summary,
        int messageCount,
        int tokenCount,
        Instant updatedAt
) {

    public ConversationSnapshot {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId required");
    }
}
