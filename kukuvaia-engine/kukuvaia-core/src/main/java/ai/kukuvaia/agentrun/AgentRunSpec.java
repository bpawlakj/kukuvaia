package ai.kukuvaia.agentrun;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * Input contract for {@link AgentRunService#createAndExecute(AgentRunSpec)}.
 *
 * <p>Caller identity is captured by {@code invokerName} + {@code invokerKind}. There is no
 * {@code userId} on purpose — runs are not owned by humans (P23 invariant: runs never appear in
 * {@code users} / {@code sessions} / {@code SPRING_AI_CHAT_MEMORY} / {@code kukuvaia.memories}).
 *
 * <p>Persona resolution: if {@code personaName} is set AgentRunService loads its system prompt
 * from {@code PersonaService}; otherwise {@code llmFollowup.systemPrompt} is used verbatim.
 * Tool whitelisting from the persona is NOT applied here (Phase A scope) — the agent-run
 * ChatClient bean carries its own tool surface.
 */
public record AgentRunSpec(
        String invokerName,
        String invokerKind,
        String inputType,
        JsonNode input,
        String personaName,
        LlmFollowupSpec llmFollowup,
        UUID parentRunId,
        UUID threadId,
        List<String> tags,
        String severity,
        String taskClass) {

    public AgentRunSpec {
        if (invokerName == null || invokerName.isBlank()) {
            throw new IllegalArgumentException("invokerName is required");
        }
        if (invokerKind == null || invokerKind.isBlank()) {
            throw new IllegalArgumentException("invokerKind is required");
        }
        if (inputType == null || inputType.isBlank()) {
            throw new IllegalArgumentException("inputType is required");
        }
        if (input == null) {
            throw new IllegalArgumentException("input is required");
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
