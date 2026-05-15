package ai.kukuvaia.agentrun;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persisted shape of one row in {@code kukuvaia.agent_runs}. Returned to callers and used
 * internally for status tracking. The {@code embedding} float[] is intentionally NOT exposed
 * here — it is persisted via the repository but never round-tripped to the API surface.
 */
public record AgentRun(
        UUID id,
        UUID threadId,
        UUID parentRunId,
        String invokerName,
        String invokerKind,
        String inputType,
        JsonNode input,
        String instructions,
        String personaName,
        String taskClass,
        String model,
        AgentRunStatus status,
        String errorCode,
        String errorMessage,
        JsonNode output,
        String outputText,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Instant queuedAt,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt,
        List<String> tags,
        String severity) {

    public AgentRun {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
