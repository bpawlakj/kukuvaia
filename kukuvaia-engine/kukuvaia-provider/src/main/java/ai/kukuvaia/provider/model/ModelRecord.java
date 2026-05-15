package ai.kukuvaia.provider.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LLM model within a provider. Maps to {@code kukuvaia.models} table.
 * The {@code modelId} is the identifier sent to the provider API (e.g., "haiku", "claude-sonnet-4.5").
 */
public record ModelRecord(
        UUID id,
        UUID providerId,
        String modelId,
        String displayName,
        List<String> capabilities,
        String tier,
        int maxTokens,
        Integer contextWindow,
        boolean enabled,
        Map<String, Object> config,
        Instant discoveredAt,
        Instant createdAt,
        Instant updatedAt
) {

    public ModelRecord {
        if (providerId == null) throw new IllegalArgumentException("providerId required");
        if (modelId == null || modelId.isBlank()) throw new IllegalArgumentException("modelId required");
        if (capabilities == null) capabilities = List.of();
        if (tier == null || tier.isBlank()) tier = "standard";
        if (maxTokens <= 0) maxTokens = 4096;
        if (config == null) config = Map.of();
    }
}
