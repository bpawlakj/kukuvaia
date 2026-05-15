package ai.kukuvaia.agentrun;

import java.util.Locale;

/**
 * Lifecycle status for an agent run. String values match the V19 schema.
 */
public enum AgentRunStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    SKIPPED;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AgentRunStatus fromWire(String s) {
        if (s == null) return null;
        return AgentRunStatus.valueOf(s.toUpperCase(Locale.ROOT));
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED
                || this == EXPIRED || this == SKIPPED;
    }
}
