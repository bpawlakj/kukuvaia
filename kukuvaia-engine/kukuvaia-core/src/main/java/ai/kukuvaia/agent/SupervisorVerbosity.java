package ai.kukuvaia.agent;

/**
 * Supervisor system-prompt verbosity tier.
 *
 * <p>FULL ships the verbose default persona prompt with explicit proactivity
 * rules — tuned for large frontier models that can follow multi-clause
 * instructions reliably.
 *
 * <p>CONCISE ships a bounded, imperative prompt (under 60 words, English only)
 * intended for small local supervisors in the 7B–27B range. Proactivity
 * behaviour moves from the prompt into advisors that inject structured
 * prompts only when the relevant state exists, because small models drop
 * always-on instructions.
 *
 * <p>Rationale documented in docs/plan/P14-tiered-context.md
 * (section "Small-supervisor mode").
 */
public enum SupervisorVerbosity {
    FULL,
    CONCISE
}
