package ai.kukuvaia.provider.registry;

/**
 * Model discovered from a provider's /v1/models endpoint.
 */
public record DiscoveredModel(
        String modelId,
        String ownedBy
) {}
