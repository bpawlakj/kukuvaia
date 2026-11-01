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
}
