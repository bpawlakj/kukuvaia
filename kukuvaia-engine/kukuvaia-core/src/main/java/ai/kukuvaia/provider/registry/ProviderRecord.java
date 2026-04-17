package ai.kukuvaia.provider.registry;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * LLM provider instance. Maps to {@code kukuvaia.providers} table.
 * The {@code apiKeyRef} is a reference (env var name), never the raw secret.
 */
public record ProviderRecord(
        UUID id,
        String name,
        String type,
        String baseUrl,
        String apiKeyRef,
        boolean enabled,
        int priority,
        Map<String, Object> config,
        Instant createdAt,
        Instant updatedAt
) {

    public ProviderRecord {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type required");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl required");
        if (apiKeyRef == null || apiKeyRef.isBlank()) throw new IllegalArgumentException("apiKeyRef required");
        if (config == null) config = Map.of();
    }
}
