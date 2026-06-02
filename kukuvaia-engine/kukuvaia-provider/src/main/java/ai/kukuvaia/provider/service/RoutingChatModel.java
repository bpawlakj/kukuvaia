package ai.kukuvaia.provider.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Delegating ChatModel that resolves the actual ChatModel from the config-backed
 * {@link ChatModelCache} at call time. Replaces Spring AI's auto-configured OpenAiChatModel
 * so that all credentials come from {@code application.yaml}, not environment variables
 * on the {@code spring.ai.openai.*} path.
 *
 * <p>Resolution strategy:
 * <ol>
 *   <li>Read model ID from prompt options (set by ModelRoutingAdvisor)</li>
 *   <li>Look up the ModelRecord via ChatModelCache (config index)</li>
 *   <li>Get or create the ChatModel from cache</li>
 *   <li>Fallback to supervisor role if no model specified</li>
 * </ol>
 */
@Component
@Primary
public class RoutingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RoutingChatModel.class);

    private final ChatModelCache chatModelCache;

    private final OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
            .internalToolExecutionEnabled(false)
            .build();

    public RoutingChatModel(ChatModelCache chatModelCache) {
        this.chatModelCache = chatModelCache;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return defaultOptions;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatModel target = resolve(prompt);
        log.debug("RoutingChatModel.call: model={}", extractModelId(prompt));
        return target.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return resolve(prompt).stream(prompt);
    }

    private ChatModel resolve(Prompt prompt) {
        String modelId = extractModelId(prompt);

        if (modelId != null) {
            ChatModel resolved = resolveByModelId(modelId);
            if (resolved != null) return resolved;
        }

        ChatModel supervisor = chatModelCache.getByRole("supervisor");
        if (supervisor != null) {
            log.debug("Resolved ChatModel via supervisor fallback");
            return supervisor;
        }

        throw new IllegalStateException(
                "No ChatModel available — ensure kukuvaia.llm-providers is configured correctly");
    }

    private ChatModel resolveByModelId(String modelId) {
        return chatModelCache.getModelRecord(modelId)
                .map(model -> {
                    log.debug("Resolved ChatModel from config index: modelId={}", modelId);
                    return chatModelCache.getByModelId(model.id());
                })
                .orElse(null);
    }

    private String extractModelId(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options instanceof OpenAiChatOptions aiOptions) {
            return aiOptions.getModel();
        }
        return null;
    }
}
