package ai.kukuvaia.memory.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent memory entry for cross-session knowledge.
 * Extends the original core MemoryEntry with memory type classification,
 * relevance scoring, access tracking, session binding, and expiration.
 * Maps to the {@code memories} table in {@code kukuvaia} schema.
 */
public record MemoryEntry(
        UUID id,
        String userId,
        String category,
        String name,
        String description,
        String content,
        Instant createdAt,
        Instant updatedAt,
        String memoryType,
        double relevanceScore,
        int accessCount,
        Instant lastAccessedAt,
        String sessionId,
        Instant expiresAt
) {

    public MemoryEntry {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
    }
}
