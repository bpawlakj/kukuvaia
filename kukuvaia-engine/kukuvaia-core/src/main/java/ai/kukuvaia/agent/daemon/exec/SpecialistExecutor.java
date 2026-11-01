package ai.kukuvaia.agent.daemon.exec;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.SubAgentResult;
import ai.kukuvaia.provider.model.ExecutionContext;
import org.springframework.stereotype.Component;

/**
 * {@code kind: specialist} — runs an LLM specialist through {@link SubAgentFactory} with
 * {@link ExecutionContext#DAEMON} (daemon provider policy) and the daemon tool allowlist. All
 * sub-agent guards apply: delegation tools stripped, depth 0, prompt hardening, tool-result
 * sanitizing, max_tool_rounds, max_tokens and the call timeout.
 */
@Component
public class SpecialistExecutor implements DaemonExecutor {

    private final SubAgentFactory subAgentFactory;
    private final DaemonProperties properties;

    public SpecialistExecutor(SubAgentFactory subAgentFactory, DaemonProperties properties) {
        this.subAgentFactory = subAgentFactory;
        this.properties = properties;
    }

    @Override
    public JobKind kind() {
        return JobKind.SPECIALIST;
    }

    @Override
    public java.util.Optional<String> validate(ai.kukuvaia.agent.daemon.def.DaemonJobDefinition definition) {
        if (!subAgentFactory.availableSpecialists().contains(definition.target())) {
            return java.util.Optional.of("unknown specialist '" + definition.target() + "' (available: "
                    + new java.util.TreeSet<>(subAgentFactory.availableSpecialists()) + ")");
        }
        if (definition.prompt() == null || definition.prompt().isBlank()) {
            return java.util.Optional.of("a specialist job needs a prompt");
        }
        return java.util.Optional.empty();
    }

    @Override
    public ExecutionOutcome execute(JobInvocation invocation, JobLimits limits) {
        SubAgentResult run = subAgentFactory.executeForResult(invocation.prompt(), invocation.target(),
                ExecutionContext.DAEMON, invocation.provider(), 0, properties.toolScopeOrNull(),
                limits.maxToolRounds(), limits.maxTokens());
        return new ExecutionOutcome(run.content(), null, run.promptTokens(), run.completionTokens(),
                run.usageReported(), invocation.provider() != null ? invocation.provider() : "daemon-default", null);
    }
}
