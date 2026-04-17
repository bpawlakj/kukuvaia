package ai.kukuvaia.advisors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Classifies user intent to optimize tool loading and model usage.
 * Adds kukuvaia.intent to advisor context for downstream use.
 *
 * Intents: CONVERSATION (no tools), DOCUMENT_READ, DOCUMENT_WRITE, ANALYSIS (all tools).
 */
@Component
public class IntentDetectionAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(IntentDetectionAdvisor.class);

    private static final Set<String> GREETING_WORDS = Set.of(
            "hi", "hello", "hey", "thanks", "bye", "ok", "yes", "no");
    private static final Set<String> READ_KEYWORDS = Set.of(
            "read", "show", "display", "get", "find", "search", "list", "look", "check");
    private static final Set<String> WRITE_KEYWORDS = Set.of(
            "write", "create", "edit", "update", "modify", "change", "fix", "add", "remove", "delete");
    private static final Set<String> ANALYSIS_KEYWORDS = Set.of(
            "analyze", "compare", "validate", "verify", "summarize", "report", "plan", "review");

    public enum Intent { SIMPLE_CONVERSATION, CONVERSATION, DOCUMENT_READ, DOCUMENT_WRITE, ANALYSIS }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        var messages = request.prompt().getInstructions();
        if (messages.isEmpty()) return request;

        String lastMessage = messages.getLast().getText().toLowerCase();
        Intent intent = classify(lastMessage);

        log.debug("Intent detected: {} for message: {}...", intent,
                lastMessage.substring(0, Math.min(50, lastMessage.length())));

        return request.mutate()
                .context(java.util.Map.of("kukuvaia.intent", intent.name()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    Intent classify(String message) {
        String lower = message.toLowerCase();
        String[] words = lower.split("\\s+");

        boolean hasRead = containsAny(words, READ_KEYWORDS);
        boolean hasWrite = containsAny(words, WRITE_KEYWORDS);
        boolean hasAnalysis = containsAny(words, ANALYSIS_KEYWORDS);

        if (hasAnalysis) return Intent.ANALYSIS;
        if (hasWrite) return Intent.DOCUMENT_WRITE;
        if (hasRead) return Intent.DOCUMENT_READ;

        // Short messages with only greetings/acknowledgments
        if (words.length <= 3 && containsAny(words, GREETING_WORDS)) {
            return Intent.SIMPLE_CONVERSATION;
        }

        return Intent.CONVERSATION;
    }

    private boolean containsAny(String[] words, Set<String> keywords) {
        for (String word : words) {
            if (keywords.contains(word)) return true;
        }
        return false;
    }
}
