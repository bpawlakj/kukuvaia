package ai.kukuvaia.plans;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Denormalised plan row for list/registry endpoints. Carries enough data for
 * the CLI picker to render a row without re-querying, plus parent-link ids for
 * the "↳ from: X + Y" display. Does not include full step JSON — picker never
 * needs it; {@code PlanDetail} (below) carries that for the detail view.
 */
public record PlanEntry(
        UUID id,
        String sessionId,
        String userId,
        String name,
        String taskPreview,
        String status,
        String phase,
        Instant createdAt,
        Instant updatedAt,
        List<PlanLinkSummary> parents
) {

    public PlanEntry {
        if (id == null) throw new IllegalArgumentException("id required");
        parents = parents == null ? List.of() : List.copyOf(parents);
    }

    /** Minimal parent-link row used when listing plans — id + relation + parent name for display. */
    public record PlanLinkSummary(UUID parentId, String relation, String parentName) {
    }
}
