package ai.kukuvaia.agent.subagent;

/**
 * Result of a single worker execution within a parallel batch.
 *
 * @param specialistType specialist that executed the task
 * @param status         outcome (COMPLETED, FAILED, TIMEOUT)
 * @param result         sub-agent response (null on failure)
 * @param error          error message (null on success)
 * @param durationMs     wall-clock execution time
 */
public record WorkerResult(
        String specialistType,
        WorkerStatus status,
        String result,
        String error,
        long durationMs
) {

    public enum WorkerStatus { COMPLETED, FAILED, TIMEOUT }
}
