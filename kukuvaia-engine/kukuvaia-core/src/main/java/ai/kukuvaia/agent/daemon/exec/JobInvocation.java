package ai.kukuvaia.agent.daemon.exec;

import java.time.Instant;
import java.util.Map;

/**
 * One run of a daemon job as seen by an executor.
 *
 * @param taskId   daemon_tasks id (audit row)
 * @param jobName  job definition name
 * @param target   executor-specific target (specialist name, bean name, agent name, connection/tool)
 * @param prompt   rendered prompt (LLM kinds) — built only from declared, sanitized fields
 * @param fields   sanitized input fields (never the raw trigger payload)
 * @param provider explicit provider override, or {@code null} for the daemon default
 * @param deadline the instant the runtime will cancel the run
 * @param args     {@code kind: mcp} argument template from the job definition (rendered by the executor)
 */
public record JobInvocation(long taskId, String jobName, String target, String prompt,
                            Map<String, String> fields, String provider, Instant deadline, Map<String, Object> args) {

    public JobInvocation {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        args = args == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(args));
    }

    public JobInvocation(long taskId, String jobName, String target, String prompt,
                         Map<String, String> fields, String provider, Instant deadline) {
        this(taskId, jobName, target, prompt, fields, provider, deadline, Map.of());
    }
}
