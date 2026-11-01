package ai.kukuvaia.agent.daemon;

import java.util.Map;

/**
 * One request to run a daemon job.
 *
 * @param prompt       rendered prompt for LLM kinds (built only from declared, sanitized fields), or {@code null}
 * @param fields       sanitized input fields
 * @param triggerSource audit label: "cron", "webhook:ci", "pg-notify:channel", "manual", …
 * @param parentTaskId the daemon task that spawned this one, or {@code null}
 */
public record JobRequest(String prompt, Map<String, String> fields, String triggerSource, Long parentTaskId) {

    public JobRequest {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        if (triggerSource == null || triggerSource.isBlank()) triggerSource = "manual";
    }

    public static JobRequest of(String prompt, String triggerSource) {
        return new JobRequest(prompt, Map.of(), triggerSource, null);
    }
}
