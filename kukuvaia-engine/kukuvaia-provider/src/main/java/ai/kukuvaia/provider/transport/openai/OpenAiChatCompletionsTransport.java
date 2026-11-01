package ai.kukuvaia.provider.transport.openai;

import ai.kukuvaia.provider.transport.ChatTransport;
import ai.kukuvaia.provider.transport.TransportSpec;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * Legacy OpenAI {@code /chat/completions} transport. Delegates straight to a
 * pre-built Spring AI {@link ChatModel} (typically {@code OpenAiChatModel}), so
 * the rest of Spring AI's tool-calling and streaming machinery keeps working
 * unchanged for providers that still speak Chat Completions.
 *
 * <p>Spring AI 2.x {@code OpenAiChatModel} no longer merges its own default options into
 * a prompt that already carries options, and it requires {@link OpenAiChatOptions}. The
 * prompt options built by {@code ChatClient} (on top of {@code RoutingChatModel}) do not
 * know the per-model defaults (model id, temperature, max tokens, reasoning effort), so
 * this transport layers the request options over the model defaults before delegating.
 */
public final class OpenAiChatCompletionsTransport implements ChatTransport {

    private final ChatModel delegate;
    private final TransportSpec spec;
    private final OpenAiChatOptions modelDefaults;

    public OpenAiChatCompletionsTransport(ChatModel delegate, TransportSpec spec, OpenAiChatOptions modelDefaults) {
        this.delegate = delegate;
        this.spec = spec;
        this.modelDefaults = modelDefaults;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(withModelDefaults(prompt));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(withModelDefaults(prompt));
    }

    @Override
    public String describe() {
        return "openai-chat-completions@" + spec.url();
    }

    Prompt withModelDefaults(Prompt prompt) {
        ChatOptions requestOptions = prompt.getOptions();
        OpenAiChatOptions.Builder merged = modelDefaults.mutate();
        if (requestOptions != null) {
            merged.combineWith(requestOptions.mutate());
        }
        return prompt.mutate().chatOptions(merged.build()).build();
    }
}
