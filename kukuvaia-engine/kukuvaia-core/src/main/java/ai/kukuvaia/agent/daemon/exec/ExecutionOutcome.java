package ai.kukuvaia.agent.daemon.exec;

import tools.jackson.databind.JsonNode;

/**
 * What an executor returns for a successful run. Failures are signalled by exceptions; the
 * runtime maps them to FAILED / TIMEOUT / CANCELLED.
 *
 * @param text             human-readable result (stored in {@code daemon_tasks.result})
 * @param output           optional structured result (stored in {@code daemon_tasks.output})
 * @param promptTokens     prompt tokens spent on our account
 * @param completionTokens completion tokens spent on our account
 * @param usageReported    {@code false} if an LLM run reported no usage — the runtime then charges
 *                         the job's {@code maxTokens} (fail closed)
 * @param provider         provider label used (for the audit row), may be {@code null}
 * @param model            model id used, may be {@code null}
 */
public record ExecutionOutcome(String text, JsonNode output, int promptTokens, int completionTokens,
                               boolean usageReported, String provider, String model) {

    public static ExecutionOutcome ofText(String text) {
        return new ExecutionOutcome(text, null, 0, 0, true, null, null);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
