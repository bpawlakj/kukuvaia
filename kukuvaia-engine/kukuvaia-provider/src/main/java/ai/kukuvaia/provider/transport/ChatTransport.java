package ai.kukuvaia.provider.transport;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Single-direction conversation transport — given a Spring AI {@link Prompt}, return a
 * {@link ChatResponse}. Implementations hide the actual wire format (OpenAI Chat
 * Completions, OpenAI Responses, Anthropic Messages, ...) so the rest of the registry
 * sees one homogeneous abstraction.
 *
 * <p>Spring AI's {@link org.springframework.ai.chat.model.ChatModel} is fronted by
 * {@code TransportChatModelAdapter}; everything underneath flows through this interface.
 */
public interface ChatTransport {

    /** Synchronous call — produce one final {@link ChatResponse}. */
    ChatResponse call(Prompt prompt);

    /**
     * Streaming variant. Default implementation degrades to a single-element flux so
     * Phase 1 transports without native streaming still satisfy the interface.
     */
    default Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    /** Short, log-friendly description: dialect + URL. */
    String describe();
}
