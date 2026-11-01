package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.exec.JobLimits;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.SubAgentSpec;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Backward-compatible facade: run a specialist as a daemon task, synchronously.
 *
 * <p>All execution, guards and auditing live in {@link DaemonRuntime}; this class only turns the
 * legacy {@code (taskName, specialist, prompt, trigger, provider)} call into an ad-hoc specialist job
 * whose limits come from the specialist's own spec. New code should define YAML jobs and use the
 * runtime directly.
 */
@Service
public class DaemonAgentService {

    /** Extra wall-clock margin so the sub-agent's own timeout fires before the runtime deadline. */
    private static final Duration DEADLINE_MARGIN = Duration.ofSeconds(5);

    private final DaemonRuntime runtime;
    private final SubAgentFactory subAgentFactory;

    public DaemonAgentService(DaemonRuntime runtime, SubAgentFactory subAgentFactory) {
        this.runtime = runtime;
        this.subAgentFactory = subAgentFactory;
    }

    /**
     * Execute a daemon task with all security guards (budget reservation, overlap, audit, deadline).
     *
     * @param taskName         unique task name (used for overlap detection)
     * @param specialistType   specialist to delegate to
     * @param prompt           sanitized prompt for sub-agent
     * @param triggerSource    "cron", "webhook:ci", "pg-notify:channel", "manual"
     * @param providerOverride explicit provider or null for daemon default
     * @return task result with full audit metadata
     */
    public DaemonTaskResult execute(String taskName, String specialistType,
                                     String prompt, String triggerSource,
                                     String providerOverride) {
        DaemonJobDefinition job = DaemonJobDefinition.adhocSpecialist(
                taskName, specialistType, providerOverride, limitsFor(specialistType));
        return runtime.runSync(job, JobRequest.of(prompt, triggerSource));
    }

    private JobLimits limitsFor(String specialistType) {
        return subAgentFactory.spec(specialistType)
                .map(DaemonAgentService::fromSpec)
                .orElse(JobLimits.defaults());
    }

    static JobLimits fromSpec(SubAgentSpec spec) {
        Duration specTimeout = Duration.ofSeconds(spec.timeoutSeconds() > 0 ? spec.timeoutSeconds() : 300);
        // Usage is cumulative over all tool rounds: budget max_tokens × (max_tool_rounds + 1).
        int tokenBudget = Math.max(1, spec.maxTokens()) * (Math.max(0, spec.maxToolRounds()) + 1);
        return new JobLimits(specTimeout.plus(DEADLINE_MARGIN), spec.maxTokens(), spec.maxToolRounds(), 20, tokenBudget);
    }
}
