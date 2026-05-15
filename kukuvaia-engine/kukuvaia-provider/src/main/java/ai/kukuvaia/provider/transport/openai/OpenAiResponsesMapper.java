package ai.kukuvaia.provider.transport.openai;

import ai.kukuvaia.provider.model.ModelRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates between Spring AI {@link Prompt} / {@link ChatResponse} and the OpenAI
 * Responses API JSON shape.
 *
 * <p>Phase 1 covered text-only. Phase 2 adds tool definitions, {@code function_call}
 * / {@code function_call_output} continuation items, and {@code reasoning} content
 * surfaced via {@link ChatGenerationMetadata}.
 *
 * <p>Request shape (Phase 2 subset):
 * <pre>
 * {
 *   "model": "gpt-5.4-mini",
 *   "input": [
 *     {"role": "system",    "content": "..."},
 *     {"role": "user",      "content": "..."},
 *     // ↓ continuation items (added by the transport's tool loop)
 *     {"type": "function_call",        "call_id": "call_1", "name": "get_weather", "arguments": "..."},
 *     {"type": "function_call_output", "call_id": "call_1", "output": "Warsaw: 5°C"}
 *   ],
 *   "tools": [
 *     {"type": "function", "name": "get_weather",
 *      "description": "...", "parameters": {...JSON Schema...}}
 *   ],
 *   "max_output_tokens": 4096,
 *   "temperature": 0.2
 * }
 * </pre>
 *
 * <p>Response shape (Phase 2 subset):
 * <pre>
 * {
 *   "id": "resp_abc",
 *   "model": "gpt-5.4-mini-2026-01-01",
 *   "output": [
 *     {"type": "reasoning", "summary": [{"text": "..."}, ...]},
 *     {"type": "function_call", "call_id": "call_1", "name": "get_weather", "arguments": "{...}"},
 *     {"type": "message", "role": "assistant",
 *      "content": [{"type": "output_text", "text": "..."}]}
 *   ],
 *   "usage": {"input_tokens": 12, "output_tokens": 3, "total_tokens": 15}
 * }
 * </pre>
 */
final class OpenAiResponsesMapper {

    /** Discriminator strings emitted/recognised in the Responses {@code output} array. */
    static final String ITEM_MESSAGE = "message";
    static final String ITEM_FUNCTION_CALL = "function_call";
    static final String ITEM_FUNCTION_CALL_OUTPUT = "function_call_output";
    static final String ITEM_REASONING = "reasoning";

    private final ObjectMapper objectMapper;

    OpenAiResponsesMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /* ====================== Prompt → request JSON ====================== */

    String serialiseRequest(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ResponsesProtocolException("Failed to serialise Responses request: " + e.getMessage(), e);
        }
    }

    Map<String, Object> buildRequestBody(Prompt prompt, ModelRecord model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.modelId());
        body.put("input", buildInput(prompt));

        Integer maxTokens = model.maxTokens();
        if (maxTokens != null && maxTokens > 0) body.put("max_output_tokens", maxTokens);

        Map<String, Object> config = model.config() == null ? Map.of() : model.config();
        Object temperature = config.get("temperature");
        if (temperature instanceof Number n) body.put("temperature", n.doubleValue());
        else if (temperature != null) {
            try {
                body.put("temperature", Double.parseDouble(temperature.toString()));
            } catch (NumberFormatException ignored) {
                // fall through — leave provider default
            }
        }

        Object instructions = config.get("instructions");
        if (instructions != null) body.put("instructions", instructions.toString());

        Object previousResponseId = config.get("previous_response_id");
        if (previousResponseId != null) body.put("previous_response_id", previousResponseId.toString());

        return body;
    }

    /**
     * Add the {@code tools} array to a request body, derived from Spring AI
     * {@link ToolCallback ToolCallbacks}. No-op when the list is empty.
     */
    void addTools(Map<String, Object> body, List<ToolCallback> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) return;
        List<Map<String, Object>> tools = new ArrayList<>(callbacks.size());
        for (ToolCallback cb : callbacks) {
            ToolDefinition def = cb.getToolDefinition();
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("name", def.name());
            if (def.description() != null) tool.put("description", def.description());
            tool.put("parameters", parseSchema(def.inputSchema()));
            tools.add(tool);
        }
        body.put("tools", tools);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            // Minimal valid empty-object schema accepted by Responses API.
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return objectMapper.readValue(schemaJson, Map.class);
        } catch (Exception e) {
            throw new ResponsesProtocolException(
                    "Tool inputSchema is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** Append a verbatim function_call item to the request input (continuation). */
    static void appendFunctionCall(List<Map<String, Object>> input, FunctionCall call) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", ITEM_FUNCTION_CALL);
        item.put("call_id", call.callId());
        item.put("name", call.name());
        item.put("arguments", call.arguments());
        input.add(item);
    }

    /** Append the matching function_call_output to the request input (continuation). */
    static void appendFunctionCallOutput(List<Map<String, Object>> input, String callId, String output) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", ITEM_FUNCTION_CALL_OUTPUT);
        item.put("call_id", callId);
        item.put("output", output == null ? "" : output);
        input.add(item);
    }

    private static List<Map<String, Object>> buildInput(Prompt prompt) {
        List<Map<String, Object>> input = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            String role = mapRole(message.getMessageType());
            if (role == null) continue;
            String text = message.getText();
            if (text == null) text = "";
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", role);
            item.put("content", text);
            input.add(item);
        }
        return input;
    }

    private static String mapRole(MessageType type) {
        if (type == null) return null;
        return switch (type) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            // TOOL messages are surfaced via function_call_output items — never via role.
            // If a Spring AI prompt arrives with TOOL messages it means the upstream caller
            // built the history itself, in which case we skip them and let the transport's
            // function_call loop carry the tool results.
            case TOOL -> null;
        };
    }

    /* ====================== Response JSON → ChatResponse ====================== */

    @SuppressWarnings("unchecked")
    Map<String, Object> parseResponseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            throw new ResponsesProtocolException("Failed to parse Responses response: " + e.getMessage(), e);
        }
    }

    /** Build a final {@link ChatResponse} once the tool loop has produced terminal output. */
    ChatResponse toChatResponse(Map<String, Object> root, String requestedModel) {
        if (root.get("error") instanceof Map<?, ?> err) {
            Object msg = err.get("message");
            throw new ResponsesProtocolException(
                    "Responses API returned error: " + (msg == null ? err : msg.toString()));
        }

        String text = extractAssistantText(root);
        String reasoning = extractReasoning(root);

        AssistantMessage assistantMessage = new AssistantMessage(text);
        ChatGenerationMetadata.Builder genMeta = ChatGenerationMetadata.builder().finishReason("stop");
        if (!reasoning.isEmpty()) genMeta.metadata("reasoning", reasoning);
        Generation generation = new Generation(assistantMessage, genMeta.build());

        ChatResponseMetadata metadata = buildMetadata(root, requestedModel);
        return new ChatResponse(List.of(generation), metadata);
    }

    /** Compatibility wrapper — Phase 1 callers expected a single (body, model) entry point. */
    ChatResponse parseResponse(String body, String requestedModel) {
        return toChatResponse(parseResponseJson(body), requestedModel);
    }

    /**
     * Extract any {@code function_call} items from the response output. Used by the
     * transport's tool-calling loop. Empty list when the model is done.
     */
    @SuppressWarnings("unchecked")
    static List<FunctionCall> extractFunctionCalls(Map<String, Object> root) {
        Object outputObj = root.get("output");
        if (!(outputObj instanceof List<?> output)) return List.of();
        List<FunctionCall> calls = new ArrayList<>();
        for (Object item : output) {
            if (!(item instanceof Map<?, ?> outItem)) continue;
            if (!ITEM_FUNCTION_CALL.equals(stringOrNull(outItem.get("type")))) continue;
            String callId = stringOrNull(outItem.get("call_id"));
            String name = stringOrNull(outItem.get("name"));
            String arguments = stringOrNull(outItem.get("arguments"));
            if (callId == null || name == null) continue;
            calls.add(new FunctionCall(callId, name, arguments == null ? "{}" : arguments));
        }
        return calls;
    }

    @SuppressWarnings("unchecked")
    private static String extractAssistantText(Map<String, Object> root) {
        Object outputObj = root.get("output");
        if (!(outputObj instanceof List<?> output)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object item : output) {
            if (!(item instanceof Map<?, ?> outItem)) continue;
            if (!ITEM_MESSAGE.equals(stringOrNull(outItem.get("type")))) continue;
            Object contentObj = outItem.get("content");
            if (!(contentObj instanceof List<?> contentList)) continue;
            for (Object c : contentList) {
                if (!(c instanceof Map<?, ?> cMap)) continue;
                String cType = stringOrNull(cMap.get("type"));
                if ("output_text".equals(cType) || "text".equals(cType)) {
                    Object t = cMap.get("text");
                    if (t != null) sb.append(t);
                }
            }
        }
        return sb.toString();
    }

    /**
     * Concatenate every {@code reasoning} item's summary text. Returns an empty string
     * when the model didn't emit reasoning (most non-reasoning models).
     */
    @SuppressWarnings("unchecked")
    private static String extractReasoning(Map<String, Object> root) {
        Object outputObj = root.get("output");
        if (!(outputObj instanceof List<?> output)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object item : output) {
            if (!(item instanceof Map<?, ?> outItem)) continue;
            if (!ITEM_REASONING.equals(stringOrNull(outItem.get("type")))) continue;
            Object summaryObj = outItem.get("summary");
            if (summaryObj instanceof List<?> summary) {
                for (Object s : summary) {
                    if (s instanceof Map<?, ?> sMap) {
                        Object text = sMap.get("text");
                        if (text != null) {
                            if (!sb.isEmpty()) sb.append('\n');
                            sb.append(text);
                        }
                    } else if (s instanceof String str) {
                        if (!sb.isEmpty()) sb.append('\n');
                        sb.append(str);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static ChatResponseMetadata buildMetadata(Map<String, Object> root, String requestedModel) {
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder();
        Object id = root.get("id");
        if (id != null) builder.id(id.toString());
        Object modelName = root.get("model");
        builder.model(modelName != null ? modelName.toString() : requestedModel);

        Object usage = root.get("usage");
        if (usage instanceof Map<?, ?> usageMap) {
            Integer in = readInt(usageMap.get("input_tokens"));
            Integer out = readInt(usageMap.get("output_tokens"));
            Integer total = readInt(usageMap.get("total_tokens"));
            if (in != null || out != null || total != null) {
                int prompt = in != null ? in : 0;
                int completion = out != null ? out : 0;
                int tot = total != null ? total : prompt + completion;
                builder.usage(new DefaultUsage(prompt, completion, tot));
            }
        }
        return builder.build();
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
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

    /** One function_call returned by the model — call_id pairs back to function_call_output. */
    record FunctionCall(String callId, String name, String arguments) {}

    static final class ResponsesProtocolException extends RuntimeException {
        ResponsesProtocolException(String message) { super(message); }
        ResponsesProtocolException(String message, Throwable cause) { super(message, cause); }
    }
}
