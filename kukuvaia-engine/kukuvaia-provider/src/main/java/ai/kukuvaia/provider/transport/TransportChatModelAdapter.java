package ai.kukuvaia.provider.transport;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import ai.kukuvaia.provider.service.ChatModelCache;
import ai.kukuvaia.provider.service.RoutingChatModel;

/**
 * Spring AI's {@link ChatModel} contract sitting in front of a {@link ChatTransport}.
 * Spring AI advisors, {@code RoutingChatModel}, and {@code ChatModelCache} continue to
 * talk to {@link ChatModel}; the transport behind the adapter decides the wire format.
 */
public final class TransportChatModelAdapter implements ChatModel {

    private final ChatTransport transport;
    private final ChatOptions defaultOptions;

    public TransportChatModelAdapter(ChatTransport transport, ChatOptions defaultOptions) {
        this.transport = transport;
        this.defaultOptions = defaultOptions;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return transport.call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return transport.stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return defaultOptions;
    }

    public ChatTransport transport() {
        return transport;
    }
}
