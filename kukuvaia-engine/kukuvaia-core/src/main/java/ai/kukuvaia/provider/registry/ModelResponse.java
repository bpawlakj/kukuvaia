package ai.kukuvaia.provider.registry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Model response DTO with provider context.
 */
public record ModelResponse(
        UUID id,
        UUID providerId,
        String providerName,
        String modelId,
        String displayName,
        List<String> capabilities,
        String tier,
        int maxTokens,
        Integer contextWindow,
        boolean enabled,
        Map<String, Object> config,
        Instant discoveredAt,
        Instant createdAt
) {

    public static ModelResponse from(ModelRecord record, String providerName) {
        return new ModelResponse(
                record.id(), record.providerId(), providerName,
                record.modelId(), record.displayName(), record.capabilities(),
                record.tier(), record.maxTokens(), record.contextWindow(),
                record.enabled(), record.config(), record.discoveredAt(), record.createdAt()
        );
    }
}
