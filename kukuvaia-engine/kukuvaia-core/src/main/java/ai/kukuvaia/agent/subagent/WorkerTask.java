package ai.kukuvaia.agent.subagent;

/**
 * Input for parallel worker execution.
 *
 * @param task           task description for the sub-agent
 * @param specialistType specialist name (e.g., "analyst", "summarizer")
 */
public record WorkerTask(String task, String specialistType) {

    public WorkerTask {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("Worker task must not be blank");
        }
        if (specialistType == null || specialistType.isBlank()) {
            throw new IllegalArgumentException("Specialist type must not be blank");
        }
    }
}
