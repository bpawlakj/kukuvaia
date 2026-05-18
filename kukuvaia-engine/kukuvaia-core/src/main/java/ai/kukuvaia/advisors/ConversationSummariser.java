package ai.kukuvaia.advisors;

import ai.kukuvaia.provider.service.ChatModelCache;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * P24 Phase D summariser — folds the oldest user/assistant turns into a single rolling
 * narrative the LLM can read as context on the next turn. Used by
 * {@link ContextCompactionAdvisor} only when Phases A + B couldn't free enough budget;
 * the call costs an LLM round-trip on the worker tier and we don't want to pay for it
 * on every interactive turn.
 *
 * <p>The summariser is monotonic by design: the previous summary is fed back in as
 * context so each pass strictly absorbs more history without losing earlier facts. The
 * worker prompt is tuned to retain decisions, in-flight artefact ids (rule ids, outline
 * ids), explicit constraints from the operator, and the user's overall goal — and to
 * drop small talk, recovered-from errors, and dead-ends.
 *
 * <p>Failure mode: returns {@link Optional#empty()} on timeout, model unavailability, or
 * empty response. The advisor then falls back to its hard-cap drop (Phase A) — degraded
 * but not crashed.
 */
@Component
public class ConversationSummariser {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummariser.class);

    private static final String SYSTEM_PROMPT = """
            You produce a rolling summary of an AI-agent conversation so the next turn
            can read the gist without all the message history. You will be given the
            previous summary (may be empty on first pass) and a slice of newer turns
            to absorb. Produce ONE updated summary — concise, third-person, factual.

            PRESERVE: decisions made, in-flight artefacts (rule ids, outline ids, file paths),
            explicit constraints the operator stated, the user's overall goal,
            verdict patterns from failed tool attempts.
            DROP: small talk, recovered-from errors the agent already handled,
            verbatim tool payloads, dead-ends the operator abandoned.

            Output the updated summary as plain text only (no headers, no bullet points
            unless they were in the previous summary). Aim for under 800 characters per
            absorption pass — the summary is meant to grow gradually, not explode.
            """;

    private final ChatModelCache chatModelCache;
    private final String role;
    private final Duration timeout;
    private final int maxNewChars;

    public ConversationSummariser(
            ChatModelCache chatModelCache,
            @Value("${kukuvaia.context-compaction.summarisation-role:worker}") String role,
            @Value("${kukuvaia.context-compaction.summarisation-timeout-seconds:10}") int timeoutSeconds,
            @Value("${kukuvaia.context-compaction.summarisation-max-input-chars:30000}") int maxNewChars) {
        this.chatModelCache = chatModelCache;
        this.role = role;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.maxNewChars = Math.max(1_000, maxNewChars);
    }

    /**
     * Produce an updated summary that absorbs {@code newMessages} into {@code previousSummary}.
     * Returns {@link Optional#empty()} on any failure path so the caller can fall through
     * to a cheaper strategy.
     */
    public Optional<String> summarise(String previousSummary, List<Message> newMessages) {
        if (newMessages == null || newMessages.isEmpty()) return Optional.empty();

        ChatModel model = chatModelCache.getByRole(role);
        if (model == null) {
            log.warn("ConversationSummariser: no '{}' role model configured; skipping Phase D", role);
            return Optional.empty();
        }

        String userPrompt = buildUserPrompt(previousSummary, newMessages);
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userPrompt)));

        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                    () -> model.call(prompt).getResult().getOutput().getText());
            String response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response == null || response.isBlank()) {
                log.warn("ConversationSummariser: empty response from '{}' role", role);
                return Optional.empty();
            }
            return Optional.of(response.trim());
        } catch (TimeoutException e) {
            log.warn("ConversationSummariser: timeout after {} ms on role '{}'", timeout.toMillis(), role);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("ConversationSummariser: error on role '{}': {}", role, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Render the previous summary + new messages as a single user prompt. Tool calls and
     * tool responses are flattened to one-line summaries — the worker model doesn't need
     * verbatim JSON to write the narrative.
     */
    private String buildUserPrompt(String previousSummary, List<Message> newMessages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Previous summary:\n");
        sb.append(previousSummary == null || previousSummary.isBlank() ? "(none — first pass)" : previousSummary);
        sb.append("\n\nNew turns to absorb (oldest first):\n");

        int charsWritten = 0;
        for (Message m : newMessages) {
            if (charsWritten >= maxNewChars) {
                sb.append("\n... (further turns truncated to fit summariser input budget)");
                break;
            }
            String line = renderMessage(m);
            if (line == null) continue;
            sb.append(line).append('\n');
            charsWritten += line.length() + 1;
        }
        return sb.toString();
    }

    private static String renderMessage(Message m) {
        if (m instanceof UserMessage um) {
            return "USER: " + truncate(um.getText(), 1_500);
        }
        if (m instanceof AssistantMessage am) {
            StringBuilder sb = new StringBuilder("ASSISTANT: ");
            if (am.getText() != null && !am.getText().isBlank()) {
                sb.append(truncate(am.getText(), 1_500));
            }
            if (am.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    sb.append(" [tool_call: ").append(tc.name()).append("]");
                }
            }
            return sb.toString();
        }
        if (m instanceof ToolResponseMessage trm) {
            StringBuilder sb = new StringBuilder("TOOL_RESULT: ");
            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                sb.append(r.name()).append("=").append(truncate(r.responseData(), 200)).append(" | ");
            }
            return sb.toString();
        }
        if (m instanceof SystemMessage sm) {
            // Older Phase B/D synthetic notes live as SystemMessages; pass them through so
            // the summariser sees the retry-collapse / pointer narrative.
            return "SYSTEM_NOTE: " + truncate(sm.getText(), 500);
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
