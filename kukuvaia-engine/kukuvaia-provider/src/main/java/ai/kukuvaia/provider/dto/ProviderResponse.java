package ai.kukuvaia.provider.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ai.kukuvaia.provider.model.ProviderRecord;

/**
 * Provider response DTO. Never contains {@code apiKeyRef} — secret references are internal only.
 * {@code config} carries non-secret per-provider overrides (e.g.
 * {@code completions-path}, {@code models-path}, custom headers) so the admin UI can
 * round-trip them on edit.
 */
public record ProviderResponse(
        UUID id,
        String name,
        String type,
        String baseUrl,
        boolean enabled,
        int priority,
        long modelCount,
        Map<String, Object> config,
        Instant createdAt
) {

    public static ProviderResponse from(ProviderRecord record, long modelCount) {
        return new ProviderResponse(
                record.id(), record.name(), record.type(), record.baseUrl(),
                record.enabled(), record.priority(), modelCount,
                record.config() != null ? record.config() : Map.of(),
                record.createdAt()
        );
    }
}
