package ai.kukuvaia.harness;

import java.time.Instant;
import java.util.UUID;

public record RuleSetRecord(
        UUID id,
        String name,
        String description,
        String scope,       // platform, group, user, project, session
        String ownerType,   // system, group, user
        String ownerId,     // group UUID or user string ID
        int priority,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    public RuleSetRecord {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("scope required");
    }
}
