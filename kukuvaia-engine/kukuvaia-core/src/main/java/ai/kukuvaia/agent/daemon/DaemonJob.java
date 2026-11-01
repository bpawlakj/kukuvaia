package ai.kukuvaia.agent.daemon;

/**
 * A deterministic daemon job implemented in Java ({@code kind: java}). Implementations are Spring
 * beans; a YAML job selects one with {@code target: <name()>}.
 *
 * <p>Jobs get no direct LLM access. If a job needs a model it calls
 * {@link JobContext#callSpecialist}, which goes through the specialist guards, the daemon provider
 * policy and the daily budget. Long-running jobs must check {@link JobContext#isCancelled()} and
 * honour interruption — the runtime cancels them at the deadline.
 */
public interface DaemonJob {

    /** Unique job bean name, referenced by {@code target:} in a YAML job definition. */
    String name();

    JobResult run(JobContext context) throws Exception;
}
