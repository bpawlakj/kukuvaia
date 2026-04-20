package ai.kukuvaia.memory.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A lightweight commitment — something the user said they need to do,
 * tracked separately from structured plans.
 */
public record Commitment(
        UUID id,
        String userId,
        String sessionId,
        String summary,
        String detail,
        String status,   // open | in_progress | done | dropped
        String source,   // extracted | explicit | tool
        String dueHint,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        double relevanceScore
) {}
