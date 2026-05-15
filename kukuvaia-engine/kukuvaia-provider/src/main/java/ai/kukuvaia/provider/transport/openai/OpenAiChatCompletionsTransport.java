package ai.kukuvaia.provider.transport.openai;

import ai.kukuvaia.provider.transport.ChatTransport;
import ai.kukuvaia.provider.transport.TransportSpec;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Legacy OpenAI {@code /chat/completions} transport. Delegates straight to a
 * pre-built Spring AI {@link ChatModel} (typically {@code OpenAiChatModel}), so
 * the rest of Spring AI's tool-calling and streaming machinery keeps working
 * unchanged for providers that still speak Chat Completions.
 */
public final class OpenAiChatCompletionsTransport implements ChatTransport {

    private final ChatModel delegate;
    private final TransportSpec spec;

    public OpenAiChatCompletionsTransport(ChatModel delegate, TransportSpec spec) {
        this.delegate = delegate;
        this.spec = spec;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return delegate.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return delegate.stream(prompt);
    }

    @Override
    public String describe() {
        return "openai-chat-completions@" + spec.url();
    }
}
