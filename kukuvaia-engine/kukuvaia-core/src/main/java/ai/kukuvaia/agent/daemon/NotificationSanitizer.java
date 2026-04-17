package ai.kukuvaia.agent.daemon;

import org.springframework.stereotype.Component;

/**
 * Sanitizes daemon task results before sending to external notification sinks.
 * Covers: Finding #13 (daemon results in notification sinks leak sensitive data).
 *
 * External sinks (webhook, Slack) receive summary only.
 * Full results available only via authenticated /api/daemon/tasks/{id}.
 */
@Component
public class NotificationSanitizer {

    private static final int MAX_SUMMARY_LENGTH = 200;

    /**
     * Create a safe notification payload for external sinks.
     * Contains: task metadata only, no full result content.
     */
    public NotificationPayload toExternalPayload(DaemonTaskResult result) {
        return new NotificationPayload(
                result.taskId(),
                result.name(),
                result.status().name(),
                result.specialistType(),
                result.provider(),
                result.durationMs(),
                result.tokenUsage(),
                truncateSummary(result.result()),
                result.triggerSource()
        );
    }

    private String truncateSummary(String fullResult) {
        if (fullResult == null || fullResult.isBlank()) {
            return "(no output)";
        }
        // Take first line or first N chars — never full result
        String firstLine = fullResult.lines().findFirst().orElse("");
        if (firstLine.length() > MAX_SUMMARY_LENGTH) {
            return firstLine.substring(0, MAX_SUMMARY_LENGTH) + "...";
        }
        return firstLine;
    }

    public record NotificationPayload(
            long taskId,
            String name,
            String status,
            String specialistType,
            String provider,
            long durationMs,
            int tokenUsage,
            String summary,
            String triggerSource
    ) {}
}
