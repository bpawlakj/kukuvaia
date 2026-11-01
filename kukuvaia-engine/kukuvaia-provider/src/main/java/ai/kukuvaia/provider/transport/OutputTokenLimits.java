package ai.kukuvaia.provider.transport;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** Resolves output limits consistently across native transports and provider-specific option names. */
public final class OutputTokenLimits {
    private OutputTokenLimits() {}

    public static Integer requested(ChatOptions options) {
        if (options == null) return null;
        Integer cap = positive(options.getMaxTokens());
        return options instanceof OpenAiChatOptions openAi
                ? minimum(cap, positive(openAi.getMaxCompletionTokens())) : cap;
    }

    public static Integer effective(Prompt prompt, Integer configured) {
        return minimum(positive(configured), requested(prompt.getOptions()));
    }

    public static ChatOptions cap(ChatOptions options, int limit) {
        int cap = minimum(requested(options), limit);
        if (options instanceof OpenAiChatOptions openAi && openAi.getMaxCompletionTokens() != null) {
            // OpenAI's maxTokens setter is ignored while maxCompletionTokens is set. Clear both
            // before restoring the preferred field, including options merged by Spring AI.
            return openAi.mutate().maxCompletionTokens(null).maxTokens(null).maxCompletionTokens(cap).build();
        }
        return options.mutate().maxTokens(cap).build();
    }

    private static Integer positive(Integer n) { return n != null && n > 0 ? n : null; }

    private static Integer minimum(Integer first, Integer second) {
        return first == null ? second : second == null ? first : Math.min(first, second);
    }
}
