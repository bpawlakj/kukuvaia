package ai.kukuvaia.agent.daemon.exec;

/**
 * Runs one kind of daemon work. Implementations are Spring beans; the runtime picks one by
 * {@link #kind()}. Executors do not touch the audit table, the budget or the overlap guard —
 * {@code DaemonRuntime} owns those, so every kind gets the same guards.
 *
 * <p>Executors must honour interruption: the runtime cancels the run when the deadline passes.
 */
public interface DaemonExecutor {

    JobKind kind();

    ExecutionOutcome execute(JobInvocation invocation, JobLimits limits) throws Exception;

    /**
     * Check a job definition of this kind at load time (e.g. the target exists).
     *
     * @return an error message, or empty if the definition is runnable
     */
    default java.util.Optional<String> validate(ai.kukuvaia.agent.daemon.def.DaemonJobDefinition definition) {
        return java.util.Optional.empty();
    }
}
