package ai.kukuvaia.agent.daemon;

import tools.jackson.databind.JsonNode;

/** What a {@link DaemonJob} returns: a short human-readable summary and optional structured output. */
public record JobResult(String summary, JsonNode output) {

    public static JobResult of(String summary) {
        return new JobResult(summary, null);
    }
}
