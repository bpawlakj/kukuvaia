package ai.kukuvaia.provider.registry;

import java.time.Instant;
import java.util.UUID;

/**
 * Provider response DTO. Never contains {@code apiKeyRef} — secret references are internal only.
 */
public record ProviderResponse(
        UUID id,
        String name,
        String type,
        String baseUrl,
        boolean enabled,
        int priority,
        long modelCount,
        Instant createdAt
) {

    public static ProviderResponse from(ProviderRecord record, long modelCount) {
        return new ProviderResponse(
                record.id(), record.name(), record.type(), record.baseUrl(),
                record.enabled(), record.priority(), modelCount, record.createdAt()
        );
    }
}
