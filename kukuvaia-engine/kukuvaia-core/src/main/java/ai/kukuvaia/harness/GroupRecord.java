package ai.kukuvaia.harness;

import java.time.Instant;
import java.util.UUID;

public record GroupRecord(
        UUID id,
        String name,
        String description,
        UUID parentId,
        Instant createdAt,
        Instant updatedAt
) {
    public GroupRecord {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
    }
}
