package ai.kukuvaia.agent.daemon;

import java.time.Instant;

/**
 * Immutable result of a daemon task execution.
 * Used for audit trail (Finding #15) and notification (Finding #13).
 */
public record DaemonTaskResult(
        long taskId,
        String name,
        String specialistType,
        String provider,
        DaemonTaskStatus status,
        String result,
        String error,
        int tokenUsage,
        long durationMs,
        String triggerSource,
        Instant startedAt,
        Instant completedAt
) {

    public enum DaemonTaskStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }
}
