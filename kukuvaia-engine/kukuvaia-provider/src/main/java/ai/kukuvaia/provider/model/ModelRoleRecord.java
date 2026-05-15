package ai.kukuvaia.provider.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps a routing role (e.g., "advisor", "worker", "supervisor") to a specific model.
 * Maps to {@code kukuvaia.model_roles} table.
 */
public record ModelRoleRecord(
        UUID id,
        String role,
        UUID modelId,
        String description,
        Instant createdAt,
        Instant updatedAt
) {

    public ModelRoleRecord {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role required");
        if (modelId == null) throw new IllegalArgumentException("modelId required");
    }
}
