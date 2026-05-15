package ai.kukuvaia.provider.dto;

import java.util.UUID;

/**
 * Role assignment response with resolved model and provider details.
 */
public record ModelRoleResponse(
        String role,
        UUID modelId,
        String modelDisplayName,
        String modelTier,
        String providerName,
        String description
) {}
