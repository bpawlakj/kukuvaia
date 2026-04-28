package ai.kukuvaia.memory.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Delegating wrapper that prevents {@code null} / blank assistant content from
 * reaching the persistence layer.
 *
 * <p>The underlying Spring AI JDBC schema defines {@code SPRING_AI_CHAT_MEMORY.content}
 * as {@code NOT NULL}. Thinking / reasoning models (Kimi K2.6, DeepSeek R1, o-series)
 * occasionally return responses where the normal {@code content} field is null and
 * the actual output lives in {@code reasoning} / {@code reasoning_content} which
 * Spring AI does not map. Attempting to persist such messages violates the
 * constraint and fails the whole chat turn.
 *
 * <p>Strategy: rewrite null / blank assistant messages to a stable placeholder
 * before delegating to the real repository. The user-facing response is still
 * produced by {@code AgentService.extractResponseText} (which surfaces reasoning
 * if present) — this wrapper only protects the audit trail.
 */
public class SanitizingChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(SanitizingChatMemoryRepository.class);

    /** Placeholder written in place of null / blank assistant content. Never surfaced to the user. */
    static final String EMPTY_PLACEHOLDER = "(empty)";

    private final ChatMemoryRepository delegate;

    public SanitizingChatMemoryRepository(ChatMemoryRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<String> findConversationIds() {
        return delegate.findConversationIds();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return delegate.findByConversationId(conversationId);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            delegate.saveAll(conversationId, messages);
            return;
        }
        List<Message> sanitized = new ArrayList<>(messages.size());
        int replaced = 0;
        for (Message message : messages) {
            if (message instanceof AssistantMessage am && (am.getText() == null || am.getText().isBlank())) {
                sanitized.add(AssistantMessage.builder()
                        .content(EMPTY_PLACEHOLDER)
                        .properties(am.getMetadata())
                        .toolCalls(am.getToolCalls())
                        .media(am.getMedia())
                        .build());
                replaced++;
            } else {
                sanitized.add(message);
            }
        }
        if (replaced > 0) {
            log.warn("Sanitized {} assistant message(s) with null/blank content for conversationId={} — " +
                            "likely a thinking model returned reasoning-only output. Persisting placeholder.",
                    replaced, conversationId);
        }
        delegate.saveAll(conversationId, sanitized);
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        delegate.deleteByConversationId(conversationId);
    }
}
