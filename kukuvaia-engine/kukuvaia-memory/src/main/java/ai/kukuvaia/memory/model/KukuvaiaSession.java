package ai.kukuvaia.memory.model;

import java.time.Instant;
import java.util.Map;

/**
 * Conversation session linking a user to a chat history.
 * Maps to the {@code sessions} table in {@code kukuvaia} schema.
 */
public record KukuvaiaSession(
        String id,
        String userId,
        String name,
        Map<String, Object> metadata,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    public KukuvaiaSession {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId required");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status required");
    }
}
