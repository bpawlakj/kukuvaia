package ai.kukuvaia.agent;

/**
 * Server-enforced planning phases. Transitions are deterministic (string matching),
 * never LLM-driven.
 *
 * <p>{@code EXECUTING} is entered automatically when the user approves a draft.
 * In this phase the plan is immutable: only {@code completeStep} is unblocked.
 * To modify the structure the user must explicitly type {@code /plan revise}
 * (→ {@link #DRAFTING}). To close the plan: {@code /plan done}.
 */
public enum PlanningPhase {
    DISCOVERY,
    DRAFTING,
    APPROVAL,
    EXECUTING
}
