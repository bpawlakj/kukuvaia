package ai.kukuvaia.provider.transport.anthropic;

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
 * Translates between Spring AI {@link Prompt} / {@link ChatResponse} and the
 * Anthropic Messages API JSON shape, used natively by {@code api.anthropic.com}
 * and as the canonical transport for Claude models behind the GitHub Copilot
 * proxy ({@code api.githubcopilot.com/v1/messages}). Spring AI 1.1 ships no
 * native client for this surface — we speak it directly.
 *
 * <p>Request shape (subset we emit):
 * <pre>
 * {
 *   "model": "claude-sonnet-4.6",
 *   "max_tokens": 4096,
 *   "system": "be terse",                          // optional — extracted from SystemMessage
 *   "messages": [
 *     {"role": "user",      "content": [{"type": "text", "text": "..."}]},
 *     {"role": "assistant", "content": [
 *        {"type": "text", "text": "..."},
 *        {"type": "tool_use", "id": "toolu_1", "name": "...", "input": {...}}
 *     ]},
 *     {"role": "user", "content": [
 *        {"type": "tool_result", "tool_use_id": "toolu_1", "content": "..."}
 *     ]}
 *   ],
 *   "tools": [
 *     {"name": "...", "description": "...", "input_schema": {...}}
 *   ],
 *   "temperature": 0.2
 * }
 * </pre>
 *
 * <p>Response shape (subset we consume):
 * <pre>
 * {
 *   "id": "msg_xyz",
 *   "type": "message",
 *   "role": "assistant",
 *   "model": "claude-sonnet-4.6-20260301",
 *   "content": [
 *     {"type": "thinking", "thinking": "..."},     // optional (extended thinking)
 *     {"type": "text", "text": "..."},
 *     {"type": "tool_use", "id": "toolu_1", "name": "...", "input": {...}}
 *   ],
 *   "stop_reason": "end_turn",                     // or "tool_use", "max_tokens", "stop_sequence"
 *   "usage": {"input_tokens": 10, "output_tokens": 5}
 * }
 * </pre>
 *
 * <p>Key differences vs OpenAI Chat/Responses:
 * <ul>
 *   <li>{@code system} prompt is a top-level string, NOT a message with role=system.</li>
 *   <li>Content is always an array of typed blocks; even simple text wraps as
 *       {@code [{"type":"text","text":"..."}]}.</li>
 *   <li>Tool results come back as a user message containing {@code tool_result}
 *       blocks, not as a dedicated role.</li>
 *   <li>{@code max_tokens} is REQUIRED (Anthropic rejects without it).</li>
 *   <li>Tool schema is keyed {@code input_schema}, not {@code parameters}.</li>
 * </ul>
 */
final class AnthropicMessagesMapper {

    static final String BLOCK_TEXT = "text";
    static final String BLOCK_TOOL_USE = "tool_use";
    static final String BLOCK_TOOL_RESULT = "tool_result";
    static final String BLOCK_THINKING = "thinking";

    /** Anthropic requires max_tokens — pick a safe default when the model row doesn't specify. */
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final ObjectMapper objectMapper;

    AnthropicMessagesMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /* ====================== Prompt → request JSON ====================== */

    String serialiseRequest(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new AnthropicProtocolException("Failed to serialise Anthropic request: " + e.getMessage(), e);
        }
    }

    Map<String, Object> buildRequestBody(Prompt prompt, ModelRecord model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model.modelId());

        int maxTokens = model.maxTokens() > 0 ? model.maxTokens() : DEFAULT_MAX_TOKENS;
        body.put("max_tokens", maxTokens);

        String system = extractSystemPrompt(prompt);
        if (!system.isEmpty()) body.put("system", system);
        body.put("messages", buildMessages(prompt));

        Map<String, Object> config = model.config() == null ? Map.of() : model.config();
        Object temperature = config.get("temperature");
        if (temperature instanceof Number n) body.put("temperature", n.doubleValue());
        else if (temperature != null) {
            try {
                body.put("temperature", Double.parseDouble(temperature.toString()));
            } catch (NumberFormatException ignored) {
                // leave provider default
            }
        }

        // Extended thinking — Anthropic-specific; opt-in via model.config.thinking=true.
        if (Boolean.TRUE.equals(config.get("thinking"))) {
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("type", "enabled");
            Object budget = config.get("thinking_budget");
            if (budget instanceof Number bn) thinking.put("budget_tokens", bn.intValue());
            body.put("thinking", thinking);
        }

        return body;
    }

    /** Append {@code tools} array (Anthropic uses {@code input_schema}, not {@code parameters}). */
    void addTools(Map<String, Object> body, List<ToolCallback> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) return;
        List<Map<String, Object>> tools = new ArrayList<>(callbacks.size());
        for (ToolCallback cb : callbacks) {
            ToolDefinition def = cb.getToolDefinition();
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", def.name());
            if (def.description() != null) tool.put("description", def.description());
            tool.put("input_schema", parseSchema(def.inputSchema()));
            tools.add(tool);
        }
        body.put("tools", tools);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return objectMapper.readValue(schemaJson, Map.class);
        } catch (Exception e) {
            throw new AnthropicProtocolException(
                    "Tool inputSchema is not valid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Append the assistant turn that produced one or more tool_use blocks (verbatim from
     * the model's content array) plus the matching user turn carrying tool_result blocks.
     * Used by the transport's tool loop.
     */
    static void appendAssistantToolUses(List<Map<String, Object>> messages,
                                        List<Map<String, Object>> assistantContentBlocks) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", assistantContentBlocks);
        messages.add(assistant);
    }

    /** Build a single user message that batches {@code tool_result} blocks for every tool_use. */
    static void appendToolResults(List<Map<String, Object>> messages,
                                  List<ToolUse> tools, List<String> outputs) {
        if (tools.size() != outputs.size()) {
            throw new IllegalArgumentException(
                    "tools.size() must equal outputs.size() — got " + tools.size() + " vs " + outputs.size());
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", BLOCK_TOOL_RESULT);
            result.put("tool_use_id", tools.get(i).id());
            result.put("content", outputs.get(i) == null ? "" : outputs.get(i));
            results.add(result);
        }
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("role", "user");
        user.put("content", results);
        messages.add(user);
    }

    private static String extractSystemPrompt(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            if (message.getMessageType() == MessageType.SYSTEM) {
                if (!sb.isEmpty()) sb.append("\n\n");
                String text = message.getText();
                if (text != null) sb.append(text);
            }
        }
        return sb.toString();
    }

    private static List<Map<String, Object>> buildMessages(Prompt prompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : prompt.getInstructions()) {
            MessageType type = message.getMessageType();
            // System messages are folded into the top-level `system` field, not the
            // messages array. TOOL messages are surfaced via tool_result blocks on a
            // user turn — the transport's tool loop manages those.
            if (type == MessageType.SYSTEM || type == MessageType.TOOL) continue;
            String role = switch (type) {
                case USER -> "user";
                case ASSISTANT -> "assistant";
                default -> null;
            };
            if (role == null) continue;
            String text = message.getText();
            if (text == null) text = "";
            Map<String, Object> textBlock = new LinkedHashMap<>();
            textBlock.put("type", BLOCK_TEXT);
            textBlock.put("text", text);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", role);
            item.put("content", List.of(textBlock));
            messages.add(item);
        }
        return messages;
    }

    /* ====================== Response JSON → ChatResponse ====================== */

    @SuppressWarnings("unchecked")
    Map<String, Object> parseResponseJson(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            throw new AnthropicProtocolException("Failed to parse Anthropic response: " + e.getMessage(), e);
        }
    }

    ChatResponse toChatResponse(Map<String, Object> root, String requestedModel) {
        if (root.get("type") instanceof String t && "error".equals(t)) {
            Object err = root.get("error");
            String msg = "Anthropic API error";
            if (err instanceof Map<?, ?> errMap && errMap.get("message") != null) {
                msg += ": " + errMap.get("message");
            }
            throw new AnthropicProtocolException(msg);
        }

        String text = extractAssistantText(root);
        String reasoning = extractThinking(root);
        String stopReason = stringOrNull(root.get("stop_reason"));

        AssistantMessage assistantMessage = new AssistantMessage(text);
        ChatGenerationMetadata.Builder genMeta = ChatGenerationMetadata.builder()
                .finishReason(stopReason != null ? stopReason : "end_turn");
        if (!reasoning.isEmpty()) genMeta.metadata("reasoning", reasoning);
        Generation generation = new Generation(assistantMessage, genMeta.build());

        ChatResponseMetadata metadata = buildMetadata(root, requestedModel);
        return new ChatResponse(List.of(generation), metadata);
    }

    ChatResponse parseResponse(String body, String requestedModel) {
        return toChatResponse(parseResponseJson(body), requestedModel);
    }

    /** Return the assistant's content blocks verbatim — used by the tool loop to replay. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> extractAssistantContent(Map<String, Object> root) {
        Object content = root.get("content");
        if (!(content instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    /** Extract every tool_use block — paired with tool_result by the transport. */
    @SuppressWarnings("unchecked")
    static List<ToolUse> extractToolUses(Map<String, Object> root) {
        Object content = root.get("content");
        if (!(content instanceof List<?> list)) return List.of();
        List<ToolUse> uses = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> block)) continue;
            if (!BLOCK_TOOL_USE.equals(stringOrNull(block.get("type")))) continue;
            String id = stringOrNull(block.get("id"));
            String name = stringOrNull(block.get("name"));
            Object input = block.get("input");
            if (id == null || name == null) continue;
            uses.add(new ToolUse(id, name, input == null ? Map.of() : (Map<String, Object>) input));
        }
        return uses;
    }

    @SuppressWarnings("unchecked")
    private static String extractAssistantText(Map<String, Object> root) {
        Object content = root.get("content");
        if (!(content instanceof List<?> list)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> block)) continue;
            if (!BLOCK_TEXT.equals(stringOrNull(block.get("type")))) continue;
            Object t = block.get("text");
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String extractThinking(Map<String, Object> root) {
        Object content = root.get("content");
        if (!(content instanceof List<?> list)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> block)) continue;
            if (!BLOCK_THINKING.equals(stringOrNull(block.get("type")))) continue;
            Object t = block.get("thinking");
            if (t != null) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(t);
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
            if (in != null || out != null) {
                int prompt = in != null ? in : 0;
                int completion = out != null ? out : 0;
                builder.usage(new DefaultUsage(prompt, completion, prompt + completion));
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

    /** One tool_use block — id pairs back to tool_result.tool_use_id. */
    record ToolUse(String id, String name, Map<String, Object> input) {}

    static final class AnthropicProtocolException extends RuntimeException {
        AnthropicProtocolException(String message) { super(message); }
        AnthropicProtocolException(String message, Throwable cause) { super(message, cause); }
    }
}
