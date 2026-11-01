package ai.kukuvaia.agent.daemon.exec;

import ai.kukuvaia.agent.daemon.DaemonBudgetGuard;
import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.DaemonJob;
import ai.kukuvaia.agent.daemon.JobContext;
import ai.kukuvaia.agent.daemon.JobResult;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.SubAgentResult;
import ai.kukuvaia.agent.subagent.SubAgentSpec;
import ai.kukuvaia.provider.model.ExecutionContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/** {@code kind: java} — runs a {@link DaemonJob} bean selected by {@code target}. */
@Component
public class JavaJobExecutor implements DaemonExecutor {

    private final Map<String, DaemonJob> jobs = new TreeMap<>();
    private final SubAgentFactory subAgentFactory;
    private final DaemonBudgetGuard budget;
    private final DaemonProperties properties;

    public JavaJobExecutor(List<DaemonJob> jobBeans, SubAgentFactory subAgentFactory,
                           DaemonBudgetGuard budget, DaemonProperties properties) {
        for (DaemonJob job : jobBeans) {
            DaemonJob previous = jobs.put(job.name(), job);
            if (previous != null) {
                throw new IllegalStateException("Two DaemonJob beans named '" + job.name() + "'");
            }
        }
        this.subAgentFactory = subAgentFactory;
        this.budget = budget;
        this.properties = properties;
    }

    @Override
    public JobKind kind() {
        return JobKind.JAVA;
    }

    /** Registered job bean names (for validation and the API). */
    public java.util.Set<String> jobNames() {
        return jobs.keySet();
    }

    @Override
    public java.util.Optional<String> validate(ai.kukuvaia.agent.daemon.def.DaemonJobDefinition definition) {
        return jobs.containsKey(definition.target())
                ? java.util.Optional.empty()
                : java.util.Optional.of("no DaemonJob bean named '" + definition.target() + "' (available: " + jobs.keySet() + ")");
    }

    @Override
    public ExecutionOutcome execute(JobInvocation invocation, JobLimits limits) throws Exception {
        DaemonJob job = jobs.get(invocation.target());
        if (job == null) {
            throw new IllegalArgumentException("No DaemonJob bean named '" + invocation.target() + "'");
        }
        var context = new Context(invocation);
        JobResult result = job.run(context);
        return new ExecutionOutcome(result != null ? result.summary() : null, result != null ? result.output() : null,
                context.promptTokens.get(), context.completionTokens.get(), true, null, null);
    }

    private final class Context implements JobContext {

        private final JobInvocation invocation;
        private final AtomicInteger promptTokens = new AtomicInteger();
        private final AtomicInteger completionTokens = new AtomicInteger();

        Context(JobInvocation invocation) {
            this.invocation = invocation;
        }

        @Override
        public long taskId() {
            return invocation.taskId();
        }

        @Override
        public String jobName() {
            return invocation.jobName();
        }

        @Override
        public Map<String, String> fields() {
            return invocation.fields();
        }

        @Override
        public Instant deadline() {
            return invocation.deadline();
        }

        @Override
        public boolean isCancelled() {
            return invocation.lifetime().isClosed() || Thread.currentThread().isInterrupted()
                    || Instant.now().isAfter(invocation.deadline());
        }

        @Override
        public synchronized SubAgentResult callSpecialist(String specialist, String prompt) {
            if (isCancelled()) {
                throw new IllegalStateException("Daemon job '" + jobName() + "' is cancelled — specialist not called");
            }
            // Usage is cumulative over all tool rounds, so reserve max_tokens × (max_tool_rounds + 1).
            int reserve = subAgentFactory.spec(specialist)
                    .map(s -> Math.max(1, s.maxTokens()) * (Math.max(0, s.maxToolRounds()) + 1))
                    .orElse(JobLimits.defaults().tokenBudget());
            if (!budget.tryReserve(reserve)) {
                throw new IllegalStateException("Daily daemon token budget does not allow calling '" + specialist + "'");
            }
            int failureCharge = reserve; // fail closed if the call throws after it started
            boolean counted = false;
            int outstandingReservation = reserve;
            try {
                budget.precharge(taskId(), reserve, promptTokens.get() + completionTokens.get() + reserve);
                outstandingReservation = 0;
                SubAgentResult result = subAgentFactory.executeForResult(prompt, specialist, ExecutionContext.DAEMON,
                        invocation.provider(), 0, properties.toolScopeOrNull(), 0, 0, invocation.lifetime());
                if (result.usageReported()) {
                    promptTokens.addAndGet(result.promptTokens());
                    completionTokens.addAndGet(result.completionTokens());
                } else {
                    promptTokens.addAndGet(reserve);
                }
                counted = true;
                return result;
            } catch (SubAgentFactory.UnknownSpecialistException
                     | ai.kukuvaia.provider.service.LlmProviderService.DaemonProviderPolicyException
                     | ai.kukuvaia.provider.service.LlmProviderService.ProviderNotAvailableException e) {
                failureCharge = 0; // pre-flight: no model call happened
                throw e;
            } finally {
                if (outstandingReservation > 0) {
                    // Persistence failed before the model started; this reservation was never spent.
                    budget.releaseReservation(outstandingReservation);
                } else {
                    if (!counted) promptTokens.addAndGet(failureCharge);
                    // The conservative charge is already durable; replace it with cumulative actual usage.
                    budget.settle(taskId(), 0, promptTokens.get() + completionTokens.get());
                }
            }
        }
    }
}
