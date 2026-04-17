package ai.kukuvaia.provider.registry;

import java.util.List;

/**
 * Result of syncing models from a provider's /v1/models endpoint.
 */
public record SyncModelsResponse(
        int discovered,
        int added,
        int disabled,
        int unchanged,
        List<SyncedModel> models
) {

    public record SyncedModel(String modelId, String status) {}
}
