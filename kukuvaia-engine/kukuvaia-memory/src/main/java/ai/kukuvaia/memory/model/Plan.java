package ai.kukuvaia.memory.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent planning artifact tracking a task and its execution steps.
 * Maps to the {@code plans} table in {@code kukuvaia} schema.
 * The {@code steps} field holds raw JSONB; deserialization happens in the repository layer.
 */
public record Plan(
        UUID id,
        String sessionId,
        String userId,
        String task,
        String steps,
        String status,
        Instant createdAt,
        Instant updatedAt
) {

    public Plan {
        if (id == null) throw new IllegalArgumentException("id required");
        if (task == null || task.isBlank()) throw new IllegalArgumentException("task required");
        if (status == null || status.isBlank()) throw new IllegalArgumentException("status required");
    }
}
