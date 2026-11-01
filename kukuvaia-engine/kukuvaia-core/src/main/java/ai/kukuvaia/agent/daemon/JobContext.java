package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.subagent.SubAgentResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** What a {@link DaemonJob} may use while it runs. */
public interface JobContext {

    long taskId();

    String jobName();

    /** Sanitized input fields declared by the job definition (never a raw trigger payload). */
    Map<String, String> fields();

    Instant deadline();

    default Duration remaining() {
        Duration left = Duration.between(Instant.now(), deadline());
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** True once the deadline passed or the run was interrupted — stop work promptly. */
    boolean isCancelled();

    /**
     * Ask an LLM specialist, under the daemon guards: provider policy (no implicit Copilot), daemon
     * tool allowlist, specialist limits, and a daily-budget reservation. Tokens are added to this
     * task's usage immediately, so they are accounted even if the job fails later.
     *
     * @throws IllegalStateException if the daily token budget does not allow the call or the run is cancelled
     */
    SubAgentResult callSpecialist(String specialist, String prompt);
}
