package ai.kukuvaia.memory.model;

import java.time.Instant;
import java.util.Map;

/**
 * Platform user identity with display preferences.
 * Maps to the {@code users} table in {@code kukuvaia} schema.
 */
public record KukuvaiaUser(
        String id,
        String displayName,
        Map<String, Object> preferences,
        Instant createdAt,
        Instant lastSeenAt
) {

    public KukuvaiaUser {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName required");
    }
}
