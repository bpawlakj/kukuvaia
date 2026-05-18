package ai.kukuvaia.advisors;

import ai.kukuvaia.memory.repository.ConversationSummaryRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * P24 Phase D resume-side advisor — injects the persisted rolling summary as a second
 * {@link SystemMessage} (after the persona system prompt) so the LLM sees the long-session
 * context it lost when the writer side (Phase D in
 * {@link ContextCompactionAdvisor}) replaced older turns with a single summary message.
 *
 * <p>Position in the advisor chain: between {@code MessageChatMemoryAdvisor}
 * ({@code HIGHEST_PRECEDENCE + 1000}, which loads the conversation history) and
 * {@link ContextCompactionAdvisor} ({@code HIGHEST_PRECEDENCE + 1100}, which may further
 * compact). We run at {@code HIGHEST_PRECEDENCE + 1050}, so the summary is in place
 * before the compactor evaluates the prompt — and Phase A/B/D have the summary in their
 * pin policy without special-casing.
 *
 * <p>No-op when the persisted summary is empty (most sessions), the session id is
 * missing, or the prompt already contains a "Conversation summary so far:" SystemMessage
 * (avoids double-injection if the same advisor fires twice in one turn).
 */
@Component
public class ConversationSummaryAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummaryAdvisor.class);

    static final String SUMMARY_PREFIX = "Conversation summary so far:";

    private final ConversationSummaryRepository summaryRepository;
    private final boolean enabled;

    public ConversationSummaryAdvisor(
            ConversationSummaryRepository summaryRepository,
            @Value("${kukuvaia.context-compaction.summarisation-enabled:true}") boolean enabled) {
        this.summaryRepository = summaryRepository;
        this.enabled = enabled;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1050;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) return request;

        String sessionId = sessionIdOrNull(request);
        if (sessionId == null) return request;

        List<Message> messages = request.prompt().getInstructions();
        if (messages == null || messages.isEmpty()) return request;
        if (containsSummary(messages)) return request;

        Optional<String> summary = summaryRepository.findBySessionId(sessionId);
        if (summary.isEmpty()) return request;

        List<Message> injected = injectAfterFirstSystemMessage(messages, summary.get());
        log.debug("Injected conversation summary into request for session '{}' (summary chars={})",
                sessionId, summary.get().length());

        return request.mutate()
                .prompt(new Prompt(injected, request.prompt().getOptions()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /**
     * Insert the summary as a {@link SystemMessage} immediately after the first existing
     * SystemMessage (the persona). If no SystemMessage is present, prepend at index 0.
     */
    private static List<Message> injectAfterFirstSystemMessage(List<Message> messages, String summary) {
        List<Message> out = new ArrayList<>(messages.size() + 1);
        SystemMessage note = new SystemMessage(SUMMARY_PREFIX + "\n" + summary);
        boolean inserted = false;
        for (int i = 0; i < messages.size(); i++) {
            out.add(messages.get(i));
            if (!inserted && messages.get(i) instanceof SystemMessage) {
                out.add(note);
                inserted = true;
            }
        }
        if (!inserted) out.add(0, note);
        return out;
    }

    private static boolean containsSummary(List<Message> messages) {
        for (Message m : messages) {
            if (m instanceof SystemMessage sm && sm.getText() != null
                    && sm.getText().startsWith(SUMMARY_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    private static String sessionIdOrNull(ChatClientRequest request) {
        Object sid = request.context().get("chat_memory_conversation_id");
        return sid != null ? sid.toString() : null;
    }
}
