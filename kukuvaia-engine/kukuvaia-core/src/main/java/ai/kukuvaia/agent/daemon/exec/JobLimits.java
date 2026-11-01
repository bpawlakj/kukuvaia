package ai.kukuvaia.agent.daemon.exec;

import java.time.Duration;

/**
 * Hard limits for one daemon run. All three DoS controls (AGENTS.md) are represented:
 * {@code timeout}, {@code maxTokens}, and {@code maxToolRounds} / {@code maxActions}.
 *
 * @param timeout       wall-clock deadline for the whole run (enforced by the runtime, call cancelled)
 * @param maxTokens     per-call output token cap; also the conservative charge when usage is unknown
 * @param maxToolRounds tool-call budget for LLM kinds (0 = the specialist's own setting)
 * @param maxActions    action budget for planning agents (Embabel)
 * @param tokenBudget   total token budget for one run (Embabel process budget)
 */
public record JobLimits(Duration timeout, int maxTokens, int maxToolRounds, int maxActions, int tokenBudget) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(300);

    public JobLimits {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) timeout = DEFAULT_TIMEOUT;
        if (maxTokens < 0) maxTokens = 0;
        if (maxToolRounds < 0) maxToolRounds = 0;
        if (maxActions <= 0) maxActions = 20;
        if (tokenBudget <= 0) tokenBudget = 20_000;
    }

    public static JobLimits defaults() {
        return new JobLimits(DEFAULT_TIMEOUT, 0, 0, 20, 20_000);
    }

    public JobLimits withTimeout(Duration t) {
        return new JobLimits(t, maxTokens, maxToolRounds, maxActions, tokenBudget);
    }

    public JobLimits withMaxTokens(int tokens) {
        return new JobLimits(timeout, tokens, maxToolRounds, maxActions, tokenBudget);
    }
}
