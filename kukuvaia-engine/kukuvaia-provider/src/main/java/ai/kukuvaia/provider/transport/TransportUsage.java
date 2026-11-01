package ai.kukuvaia.provider.transport;

import java.util.HashMap;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;

/** Cumulative usage and completeness across native HTTP rounds hidden from the outer ChatModel. */
public final class TransportUsage {
    public static final String COMPLETE = "kukuvaia.usage.complete";
    private int promptTokens;
    private int completionTokens;
    private boolean complete = true;

    public void add(ChatResponse response) {
        var usage = response.getMetadata().getUsage();
        if (usage == null || usage instanceof EmptyUsage || usage.getPromptTokens() == null
                || usage.getCompletionTokens() == null || usage.getTotalTokens() == null || usage.getTotalTokens() <= 0) {
            complete = false;
        }
        if (usage != null) {
            promptTokens = Math.addExact(promptTokens, usage.getPromptTokens() == null ? 0 : usage.getPromptTokens());
            completionTokens = Math.addExact(completionTokens, usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
        }
    }

    public ChatResponse finish(ChatResponse last) {
        var metadata = last.getMetadata();
        var extra = new HashMap<String, Object>();
        metadata.entrySet().forEach(entry -> extra.put(entry.getKey(), entry.getValue()));
        return new ChatResponse(last.getResults(), ChatResponseMetadata.builder().metadata(extra)
                .id(metadata.getId()).model(metadata.getModel()).rateLimit(metadata.getRateLimit())
                .promptMetadata(metadata.getPromptMetadata()).usage(new DefaultUsage(promptTokens, completionTokens))
                .keyValue(COMPLETE, complete).build());
    }
}
