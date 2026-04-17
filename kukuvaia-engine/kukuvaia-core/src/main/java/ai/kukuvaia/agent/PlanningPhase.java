package ai.kukuvaia.agent;

/**
 * Server-enforced planning phases. Transitions are deterministic (string matching),
 * never LLM-driven.
 */
public enum PlanningPhase {
    DISCOVERY,
    DRAFTING,
    APPROVAL
}
