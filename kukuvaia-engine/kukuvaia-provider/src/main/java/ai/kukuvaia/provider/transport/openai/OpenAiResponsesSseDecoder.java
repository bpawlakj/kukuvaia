package ai.kukuvaia.provider.transport.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Line-by-line decoder for the OpenAI Responses SSE stream format. Lives outside the
 * transport so it can be unit-tested without a real {@code HttpClient}.
 *
 * <p>Stream shape (one event = {@code event:} line followed by one or more
 * {@code data:} lines, terminated by a blank line):
 * <pre>
 * event: response.created
 * data: {"type":"response.created","response":{"id":"resp_xxx"}}
 *
 * event: response.output_text.delta
 * data: {"type":"response.output_text.delta","delta":"Hello"}
 *
 * event: response.completed
 * data: {"type":"response.completed","response":{"id":"resp_xxx", ...}}
 * </pre>
 *
 * <p>Emission strategy matches Spring AI's text-streaming convention: each text delta
 * is a {@link ChatResponse} carrying only the new fragment (an empty Generation when
 * none). The single {@code response.completed} event produces a final
 * {@link ChatResponse} with the full metadata (usage, id, model, reasoning,
 * {@code finishReason}). {@link reactor.core.publisher.Flux} consumers (e.g.
 * Spring AI's {@code MessageAggregator}) concatenate the deltas and pick up metadata
 * from the terminal frame.
 */
final class OpenAiResponsesSseDecoder {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesSseDecoder.class);

    private final OpenAiResponsesMapper mapper;
    private final ObjectMapper objectMapper;
    private final String requestedModel;

    private String currentEvent;
    private final StringBuilder currentData = new StringBuilder();

    private boolean finalEmitted;

    OpenAiResponsesSseDecoder(OpenAiResponsesMapper mapper, String requestedModel) {
        this.mapper = mapper;
        this.objectMapper = new ObjectMapper(); // small, line-bounded payloads — own instance is fine
        this.requestedModel = requestedModel;
    }

    /**
     * Feed one raw line from the SSE stream. Returns an emission when this line
     * completes an event that carries a user-visible delta.
     */
    Optional<ChatResponse> feed(String rawLine) {
        if (rawLine == null) return Optional.empty();
        String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;

        if (line.isEmpty()) {
            return flushCurrentEvent();
        }
        if (line.startsWith(":")) {
            // SSE comment / keep-alive — ignore.
            return Optional.empty();
        }
        if (line.startsWith("event:")) {
            currentEvent = line.substring("event:".length()).trim();
            return Optional.empty();
        }
        if (line.startsWith("data:")) {
            if (currentData.length() > 0) currentData.append('\n');
            currentData.append(line.substring("data:".length()).trim());
            return Optional.empty();
        }
        // Unknown line shape — silently drop.
        return Optional.empty();
    }

    /**
     * Called once the stream closes — flushes any pending event and returns the final
     * frame if it wasn't already emitted by the loop.
     */
    Optional<ChatResponse> complete() {
        return flushCurrentEvent();
    }

    private Optional<ChatResponse> flushCurrentEvent() {
        if (currentData.length() == 0 && currentEvent == null) {
            return Optional.empty();
        }
        String event = currentEvent;
        String data = currentData.toString();
        currentEvent = null;
        currentData.setLength(0);
        if (data.isEmpty() || "[DONE]".equals(data)) return Optional.empty();

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(data, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("[responses-sse] dropping malformed event data: {}", e.getMessage());
            return Optional.empty();
        }

        String type = event != null ? event : stringField(payload, "type");
        if (type == null) return Optional.empty();

        return switch (type) {
            case "response.output_text.delta" -> handleTextDelta(payload);
            case "response.completed" -> handleCompleted(payload);
            case "response.failed", "error" -> handleFailure(payload);
            default -> Optional.empty();
        };
    }

    private Optional<ChatResponse> handleTextDelta(Map<String, Object> payload) {
        Object delta = payload.get("delta");
        if (delta == null) return Optional.empty();
        String text = delta.toString();
        if (text.isEmpty()) return Optional.empty();
        Generation generation = new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().build());
        return Optional.of(new ChatResponse(List.of(generation)));
    }

    @SuppressWarnings("unchecked")
    private Optional<ChatResponse> handleCompleted(Map<String, Object> payload) {
        if (finalEmitted) return Optional.empty();
        Object response = payload.get("response");
        if (!(response instanceof Map<?, ?> respMap)) return Optional.empty();
        finalEmitted = true;
        // Build a terminal ChatResponse from the full response object — text aggregated
        // from output items; usage + metadata + reasoning. The terminal frame's text
        // duplicates what came through deltas, but MessageAggregator only keeps the last
        // non-empty content of the final frame for metadata purposes; consumers that
        // concatenate take deltas during the stream. Emit an empty AssistantMessage on
        // the terminal frame so we don't double-count text.
        ChatResponse fromFull = mapper.toChatResponse((Map<String, Object>) respMap, requestedModel);
        Generation original = fromFull.getResult();
        Generation terminal = new Generation(new AssistantMessage(""),
                original.getMetadata() != null ? original.getMetadata()
                        : ChatGenerationMetadata.builder().finishReason("stop").build());
        return Optional.of(new ChatResponse(List.of(terminal), fromFull.getMetadata()));
    }

    private Optional<ChatResponse> handleFailure(Map<String, Object> payload) {
        Object error = payload.get("error");
        String message = "Responses stream failed";
        if (error instanceof Map<?, ?> errMap && errMap.get("message") != null) {
            message += ": " + errMap.get("message");
        } else if (payload.get("response") instanceof Map<?, ?> respMap
                && respMap.get("error") instanceof Map<?, ?> respErr
                && respErr.get("message") != null) {
            message += ": " + respErr.get("message");
        }
        throw new OpenAiResponsesTransport.ResponsesTransportException(message);
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }
}
