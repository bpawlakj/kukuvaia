package ai.kukuvaia.provider.registry;

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
 * Delegating ChatModel that resolves the actual ChatModel from the database-backed
 * {@link ChatModelCache} at call time. Replaces Spring AI's auto-configured OpenAiChatModel
 * so that all credentials come from the provider registry (DB), not environment variables.
 *
 * <p>Resolution strategy:
 * <ol>
 *   <li>Read model ID from prompt options (set by ModelRoutingAdvisor)</li>
 *   <li>Find the DB model record by model ID string</li>
 *   <li>Get or create the ChatModel from cache (uses DB provider credentials)</li>
 *   <li>Fallback to supervisor role if no model specified</li>
 * </ol>
 *
 * <p>Returns {@link OpenAiChatOptions} with {@code internalToolExecutionEnabled=false} as default
 * options so that Spring AI's ChatClient includes tool callbacks in the prompt and delegates
 * tool execution to {@link org.springframework.ai.chat.client.advisor.ToolCallAdvisor}.
 */
@Component
@Primary
public class RoutingChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(RoutingChatModel.class);

    private final ChatModelCache chatModelCache;
    private final ModelRepository modelRepository;

    /** Default options that tell Spring AI this model supports external tool calling. */
    private final OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
            .internalToolExecutionEnabled(false)
            .build();

    public RoutingChatModel(ChatModelCache chatModelCache, ModelRepository modelRepository) {
        this.chatModelCache = chatModelCache;
        this.modelRepository = modelRepository;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return defaultOptions;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatModel target = resolve(prompt);
        log.debug("RoutingChatModel.call: targetModel={}, model={}",
                target.getClass().getSimpleName(), extractModelId(prompt));
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

        // Fallback: supervisor role
        ChatModel supervisor = chatModelCache.getByRole("supervisor");
        if (supervisor != null) {
            log.debug("Resolved ChatModel via supervisor fallback");
            return supervisor;
        }

        throw new IllegalStateException(
                "No ChatModel available — assign a supervisor role in the provider registry");
    }

    private ChatModel resolveByModelId(String modelId) {
        return modelRepository.findByModelId(modelId)
                .map(model -> {
                    log.debug("Resolved ChatModel from DB: modelId={}, uuid={}", modelId, model.id());
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
