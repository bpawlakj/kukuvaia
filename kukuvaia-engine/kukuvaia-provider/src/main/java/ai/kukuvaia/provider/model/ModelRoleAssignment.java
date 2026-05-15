package ai.kukuvaia.provider.model;

import java.util.UUID;

/**
 * Single role → model assignment.
 */
public record ModelRoleAssignment(
        String role,
        UUID modelId,
        String description
) {

    public ModelRoleAssignment {
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role required");
        if (modelId == null) throw new IllegalArgumentException("modelId required");
    }
}
