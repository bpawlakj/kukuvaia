package ai.kukuvaia.agent;

import java.time.Instant;

/**
 * Immutable per-session planning state. Phase and fact transitions produce a new instance.
 */
public record PlanningSession(
        String task,
        PlanningPhase phase,
        Instant startedAt,
        DiscoveryFacts facts
) {

    public PlanningSession {
        facts = facts == null ? DiscoveryFacts.empty() : facts;
    }

    public PlanningSession withPhase(PlanningPhase newPhase) {
        return new PlanningSession(task, newPhase, startedAt, facts);
    }

    public PlanningSession withFacts(DiscoveryFacts newFacts) {
        return new PlanningSession(task, phase, startedAt, newFacts);
    }
}
