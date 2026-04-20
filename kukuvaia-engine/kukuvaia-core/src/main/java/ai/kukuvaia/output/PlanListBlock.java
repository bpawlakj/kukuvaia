package ai.kukuvaia.output;

import ai.kukuvaia.plans.PlanEntry;

import java.util.List;

/**
 * P21 — interactive registry listing. CLI auto-opens the picker on receipt.
 * Server-side representation carries only what the picker needs to render +
 * launch actions (resume / combine / new-from / abandon).
 */
public record PlanListBlock(List<PlanEntry> plans, String statusFilter) implements OutputBlock {

    public PlanListBlock {
        plans = plans == null ? List.of() : List.copyOf(plans);
    }
}
