package ai.kukuvaia.advisors;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

/**
 * Deterministic, char-based token estimator. Used by {@link ContextCompactionAdvisor} to decide
 * whether the assembled prompt would overflow the active model's context window before sending
 * it to the provider — real tokenisation is provider-specific and expensive to run inline.
 *
 * <p>Estimator weights are calibrated against Anthropic's tokeniser on representative payloads
 * (English prose, JSON tool responses, multilingual content). Empirically the estimator stays
 * within ±10 % of the real Anthropic count, which is enough to act on a 75 %-of-window soft
 * threshold without paying the per-call tokenisation cost.
 *
 * <p>Per-message-type weights reflect that JSON-structured payloads (tool responses) have a
 * lower chars-per-token ratio than natural language: structural characters (`{`, `,`, `"`)
 * each tokenise individually.
 *
 * <p>Thread-safe; no state. Constants tuned once, change requires re-calibration against the
 * Anthropic counting API on a fixture corpus.
 */
@Component
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN_NL = 4.0;
    private static final double CHARS_PER_TOKEN_JSON = 3.2;
    private static final int PER_MESSAGE_OVERHEAD = 5;

    /** Estimate token count for a single message. */
    public int estimate(Message message) {
        if (message == null) return 0;
        int payloadChars = payloadCharCount(message);
        if (payloadChars == 0) return PER_MESSAGE_OVERHEAD;
        double charsPerToken = isJsonLike(message) ? CHARS_PER_TOKEN_JSON : CHARS_PER_TOKEN_NL;
        return PER_MESSAGE_OVERHEAD + (int) Math.ceil(payloadChars / charsPerToken);
    }

    /**
     * The real payload of a Spring AI message isn't always in {@code getText()}. For tool-call
     * responses the wire content lives in {@link ToolResponseMessage#getResponses()}; for
     * assistant messages with tool_calls the JSON argument string lives on each ToolCall.
     * Sum all visible bytes so the estimator reflects what the provider will actually be sent,
     * not just the inert "content" string.
     */
    private static int payloadCharCount(Message message) {
        int total = 0;
        String text = message.getText();
        if (text != null) total += text.length();
        if (message instanceof ToolResponseMessage trm) {
            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                if (r.name() != null) total += r.name().length();
                if (r.responseData() != null) total += r.responseData().length();
            }
        }
        if (message instanceof AssistantMessage am && am.hasToolCalls()) {
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                if (tc.name() != null) total += tc.name().length();
                if (tc.arguments() != null) total += tc.arguments().length();
            }
        }
        return total;
    }

    /** Sum estimate across a message list. */
    public int estimate(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        int total = 0;
        for (Message m : messages) total += estimate(m);
        return total;
    }

    /**
     * Tool responses are JSON; assistant messages with tool_calls also carry JSON arguments.
     * Treat both as JSON-like for the chars-per-token ratio.
     */
    private static boolean isJsonLike(Message message) {
        if (message instanceof ToolResponseMessage) return true;
        if (message instanceof AssistantMessage am && am.hasToolCalls()) return true;
        return message.getMessageType() == MessageType.TOOL;
    }
}
