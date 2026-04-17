package ai.kukuvaia.advisors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects tool-call loops and triggers escalation to more powerful models.
 *
 * Tracks per-session tool-call rounds. When thresholds are exceeded:
 * - Warning: injects "wrap up" hint into next request
 * - Escalation: sets flag for {@link ModelRoutingAdvisor} to switch to advisor model
 * - Abort: returns error, resets state
 *
 * State is reset when a response completes without tool calls (task finished).
 */
@Component
public class LoopDetectionAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoopDetectionAdvisor.class);

    private final ConcurrentHashMap<String, SessionToolState> sessionStates = new ConcurrentHashMap<>();

    @Value("${kukuvaia.routing.loop-warning-rounds:5}")
    private int warningThreshold;

    @Value("${kukuvaia.routing.loop-escalation-rounds:8}")
    private int escalationThreshold;

    @Value("${kukuvaia.routing.loop-abort-rounds:15}")
    private int abortThreshold;

    @Override
    public int getOrder() {
        // Run AFTER ModelRoutingAdvisor (which reads the escalate flag we set)
        // but BEFORE memory advisors
        return Ordered.HIGHEST_PRECEDENCE + 13;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String sessionId = extractSessionId(request);
        if (sessionId == null) return request;

        SessionToolState state = sessionStates.get(sessionId);
        if (state == null) return request;

        // Hard abort — stop execution immediately
        if (state.toolRounds >= abortThreshold) {
            log.error("Loop abort: sessionId={}, toolRounds={} — stopping execution",
                    sessionId, state.toolRounds);
            sessionStates.remove(sessionId);
            throw new ToolLoopAbortException(sessionId, state.toolRounds);
        }

        // Check if we need to escalate
        if (state.toolRounds >= escalationThreshold) {
            log.warn("Loop escalation: sessionId={}, toolRounds={} — switching to advisor model",
                    sessionId, state.toolRounds);
            return request.mutate()
                    .context(ModelRoutingAdvisor.CTX_ESCALATE, true)
                    .build();
        }

        // Check if we need to warn
        if (state.toolRounds >= warningThreshold) {
            log.info("Loop warning: sessionId={}, toolRounds={} — injecting wrap-up hint",
                    sessionId, state.toolRounds);
            // Add a system hint to wrap up
            return request.mutate()
                    .context("kukuvaia.loop-warning",
                            "You have been calling tools for %d rounds. Please synthesize your findings and provide a final answer."
                                    .formatted(state.toolRounds))
                    .build();
        }

        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        String sessionId = extractSessionIdFromResponse(response);
        if (sessionId == null) return response;

        boolean hasToolCalls = hasToolCallsInResponse(response);

        if (hasToolCalls) {
            SessionToolState state = sessionStates.computeIfAbsent(sessionId,
                    k -> new SessionToolState());
            state.toolRounds++;
            state.lastToolCallAt = Instant.now();

            log.debug("Tool round tracked: sessionId={}, rounds={}", sessionId, state.toolRounds);
        } else {
            // No tool calls = task completed successfully. Reset state.
            SessionToolState removed = sessionStates.remove(sessionId);
            if (removed != null && removed.toolRounds > 0) {
                log.debug("Loop state reset: sessionId={}, completedAfter={} rounds",
                        sessionId, removed.toolRounds);
            }
        }

        return response;
    }

    private String extractSessionId(ChatClientRequest request) {
        Object id = request.context().get("chat_memory_conversation_id");
        return id != null ? id.toString() : null;
    }

    private String extractSessionIdFromResponse(ChatClientResponse response) {
        Object id = response.context().get("chat_memory_conversation_id");
        return id != null ? id.toString() : null;
    }

    private boolean hasToolCallsInResponse(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResults() == null) return false;

        for (Generation gen : chatResponse.getResults()) {
            if (gen.getOutput() != null && gen.getOutput().getToolCalls() != null
                    && !gen.getOutput().getToolCalls().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Current tracked sessions (for monitoring/testing).
     */
    public int trackedSessionCount() {
        return sessionStates.size();
    }

    /**
     * Get tool rounds for a session (for testing).
     */
    int getToolRounds(String sessionId) {
        SessionToolState state = sessionStates.get(sessionId);
        return state != null ? state.toolRounds : 0;
    }

    static class SessionToolState {
        volatile int toolRounds;
        volatile Instant lastToolCallAt;
    }

    /**
     * Thrown when tool-call loop exceeds abort threshold — breaks out of ToolCallAdvisor loop.
     */
    public static class ToolLoopAbortException extends RuntimeException {
        private final String sessionId;
        private final int toolRounds;

        public ToolLoopAbortException(String sessionId, int toolRounds) {
            super("Tool-call loop aborted after %d rounds (session=%s)".formatted(toolRounds, sessionId));
            this.sessionId = sessionId;
            this.toolRounds = toolRounds;
        }

        public String getSessionId() { return sessionId; }
        public int getToolRounds() { return toolRounds; }
    }
}
