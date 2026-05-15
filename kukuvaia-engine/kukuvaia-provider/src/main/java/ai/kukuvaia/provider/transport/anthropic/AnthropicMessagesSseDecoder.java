package ai.kukuvaia.provider.transport.anthropic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Line-by-line decoder for Anthropic's Messages streaming format. Lives outside the
 * transport so it can be unit-tested without a real {@code HttpClient}.
 *
 * <p>Event sequence on a successful run (subset):
 * <pre>
 * event: message_start
 * data: {"type":"message_start","message":{"id":"msg_xxx","model":"...","usage":{"input_tokens":10,"output_tokens":1}}}
 *
 * event: content_block_start
 * data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
 *
 * event: content_block_delta
 * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
 *
 * event: content_block_stop
 * data: {"type":"content_block_stop","index":0}
 *
 * event: message_delta
 * data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":15}}
 *
 * event: message_stop
 * data: {"type":"message_stop"}
 * </pre>
 *
 * <p>{@code tool_use} blocks stream their JSON input via {@code input_json_delta} events
 * — Phase 3 ignores those because the streaming path bails out to sync the moment tools
 * are involved (see {@link AnthropicMessagesTransport#stream}).
 */
final class AnthropicMessagesSseDecoder {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessagesSseDecoder.class);

    private final AnthropicMessagesMapper mapper;
    private final ObjectMapper objectMapper;
    private final String requestedModel;

    private String currentEvent;
    private final StringBuilder currentData = new StringBuilder();

    private String messageId;
    private String responseModel;
    private int inputTokens;
    private int outputTokens;
    private String stopReason;
    private boolean terminalEmitted;

    AnthropicMessagesSseDecoder(AnthropicMessagesMapper mapper, String requestedModel) {
        this.mapper = mapper;
        this.objectMapper = new ObjectMapper();
        this.requestedModel = requestedModel;
    }

    Optional<ChatResponse> feed(String rawLine) {
        if (rawLine == null) return Optional.empty();
        String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;

        if (line.isEmpty()) return flushCurrentEvent();
        if (line.startsWith(":")) return Optional.empty();
        if (line.startsWith("event:")) {
            currentEvent = line.substring("event:".length()).trim();
            return Optional.empty();
        }
        if (line.startsWith("data:")) {
            if (currentData.length() > 0) currentData.append('\n');
            currentData.append(line.substring("data:".length()).trim());
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Called once the stream closes — emit the terminal frame if message_stop was missed. */
    Optional<ChatResponse> complete() {
        Optional<ChatResponse> pending = flushCurrentEvent();
        if (pending.isPresent()) return pending;
        if (!terminalEmitted && messageId != null) {
            return Optional.of(buildTerminal());
        }
        return Optional.empty();
    }

    private Optional<ChatResponse> flushCurrentEvent() {
        if (currentData.length() == 0 && currentEvent == null) return Optional.empty();
        String event = currentEvent;
        String data = currentData.toString();
        currentEvent = null;
        currentData.setLength(0);
        if (data.isEmpty()) return Optional.empty();

        Map<String, Object> payload;
        try {
            payload = objectMapper.readValue(data, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("[anthropic-sse] dropping malformed event data: {}", e.getMessage());
            return Optional.empty();
        }

        String type = event != null ? event : stringField(payload, "type");
        if (type == null) return Optional.empty();

        return switch (type) {
            case "message_start" -> { handleMessageStart(payload); yield Optional.empty(); }
            case "content_block_delta" -> handleContentBlockDelta(payload);
            case "message_delta" -> { handleMessageDelta(payload); yield Optional.empty(); }
            case "message_stop" -> handleMessageStop();
            case "error" -> handleFailure(payload);
            default -> Optional.empty();
        };
    }

    private void handleMessageStart(Map<String, Object> payload) {
        if (!(payload.get("message") instanceof Map<?, ?> msg)) return;
        Object id = msg.get("id");
        if (id != null) messageId = id.toString();
        Object modelName = msg.get("model");
        if (modelName != null) responseModel = modelName.toString();
        if (msg.get("usage") instanceof Map<?, ?> usage) {
            Integer in = readInt(usage.get("input_tokens"));
            if (in != null) inputTokens = in;
            Integer out = readInt(usage.get("output_tokens"));
            if (out != null) outputTokens = out;
        }
    }

    private Optional<ChatResponse> handleContentBlockDelta(Map<String, Object> payload) {
        if (!(payload.get("delta") instanceof Map<?, ?> delta)) return Optional.empty();
        if (!"text_delta".equals(stringField(asMap(delta), "type"))) return Optional.empty();
        Object text = delta.get("text");
        if (text == null) return Optional.empty();
        String t = text.toString();
        if (t.isEmpty()) return Optional.empty();
        Generation generation = new Generation(new AssistantMessage(t),
                ChatGenerationMetadata.builder().build());
        return Optional.of(new ChatResponse(List.of(generation)));
    }

    private void handleMessageDelta(Map<String, Object> payload) {
        if (payload.get("delta") instanceof Map<?, ?> delta) {
            Object reason = delta.get("stop_reason");
            if (reason != null) stopReason = reason.toString();
        }
        if (payload.get("usage") instanceof Map<?, ?> usage) {
            Integer out = readInt(usage.get("output_tokens"));
            if (out != null) outputTokens = out;
        }
    }

    private Optional<ChatResponse> handleMessageStop() {
        if (terminalEmitted) return Optional.empty();
        return Optional.of(buildTerminal());
    }

    private ChatResponse buildTerminal() {
        terminalEmitted = true;
        ChatGenerationMetadata.Builder genMeta = ChatGenerationMetadata.builder()
                .finishReason(stopReason != null ? stopReason : "end_turn");
        Generation generation = new Generation(new AssistantMessage(""), genMeta.build());

        ChatResponseMetadata.Builder respMeta = ChatResponseMetadata.builder();
        if (messageId != null) respMeta.id(messageId);
        respMeta.model(responseModel != null ? responseModel : requestedModel);
        if (inputTokens > 0 || outputTokens > 0) {
            respMeta.usage(new DefaultUsage(inputTokens, outputTokens, inputTokens + outputTokens));
        }
        return new ChatResponse(List.of(generation), respMeta.build());
    }

    private Optional<ChatResponse> handleFailure(Map<String, Object> payload) {
        String message = "Anthropic stream failed";
        if (payload.get("error") instanceof Map<?, ?> err && err.get("message") != null) {
            message += ": " + err.get("message");
        }
        throw new AnthropicMessagesTransport.AnthropicTransportException(message);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }

    private static String stringField(Map<String, Object> payload, String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer readInt(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    AnthropicMessagesMapper mapper() {
        return mapper;
    }
}
