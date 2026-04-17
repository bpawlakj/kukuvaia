package ai.kukuvaia.harness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RuleRecord(
        UUID id,
        UUID ruleSetId,
        String key,
        String type,        // instruction, constraint, context, preference, persona_modifier
        String content,
        List<String> tags,
        Map<String, Object> activation,
        int priority,
        boolean enabled,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
    public RuleRecord {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content required");
        if (tags == null) tags = List.of();
        if (activation == null) activation = Map.of("always", true);
    }
}
