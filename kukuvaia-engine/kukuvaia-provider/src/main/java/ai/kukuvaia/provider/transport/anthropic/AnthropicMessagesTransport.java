package ai.kukuvaia.provider.transport.anthropic;

import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.transport.ChatTransport;
import ai.kukuvaia.provider.transport.TransportSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Transport for the Anthropic Messages API ({@code POST /v1/messages}). Used for
 * Claude models — both behind the GitHub Copilot proxy ({@code api.githubcopilot.com
 * /v1/messages}) and against the native Anthropic API ({@code api.anthropic.com
 * /v1/messages}). Spring AI 1.1 has no first-class Anthropic Messages client; we
 * speak it directly.
 *
 * <p>Phase 3 capabilities mirror the Phase 2 OpenAI Responses transport:
 * <ul>
 *   <li><b>Tool calling</b> — Variant B: the loop runs inside the transport, with
 *       Claude's pattern of replaying the assistant turn (verbatim {@code tool_use}
 *       blocks) plus a fresh user turn carrying {@code tool_result} blocks.</li>
 *   <li><b>Streaming</b> — text-only via SSE ({@code content_block_delta} events).
 *       Tool flow degrades to sync because the loop spans multiple HTTP turns.</li>
 *   <li><b>Reasoning / thinking</b> — concat of {@code thinking} content blocks
 *       (opt-in via {@code model.config.thinking=true}) surfaced as
 *       {@code Generation.metadata.reasoning}.</li>
 * </ul>
 *
 * <p>Authentication is driven by {@code provider.config.auth-header}:
 * <ul>
 *   <li>Default — {@code Authorization: Bearer <key>} (Copilot proxy, OpenRouter, etc.)</li>
 *   <li>{@code "auth-header": "x-api-key"} — {@code x-api-key: <key>} (native Anthropic API)</li>
 * </ul>
 * The provider config should also include {@code "anthropic-version": "2023-06-01"}
 * in {@code headers} for any path that hits the native API.
 */
public final class AnthropicMessagesTransport implements ChatTransport {

    private static final Logger log = LoggerFactory.getLogger(AnthropicMessagesTransport.class);

    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "content-type", "accept", "x-api-key");

    private static final int MAX_TOOL_ITERATIONS = 20;

    private final TransportSpec spec;
    private final ModelRecord model;
    private final AnthropicMessagesMapper mapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final String authHeaderName;
    private final boolean useBearerPrefix;

    public AnthropicMessagesTransport(TransportSpec spec, ModelRecord model,
                                      HttpClient httpClient, ObjectMapper objectMapper,
                                      Duration requestTimeout, String authHeaderName) {
        this.spec = spec;
        this.model = model;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.mapper = new AnthropicMessagesMapper(objectMapper);
        this.requestTimeout = requestTimeout;
        this.authHeaderName = (authHeaderName == null || authHeaderName.isBlank()) ? "Authorization" : authHeaderName;
        this.useBearerPrefix = "Authorization".equalsIgnoreCase(this.authHeaderName);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<ToolCallback> callbacks = toolCallbacks(prompt);
        Map<String, Object> body = mapper.buildRequestBody(prompt, model);
        mapper.addTools(body, callbacks);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");

        Map<String, Object> lastRoot = null;
        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            String payload = mapper.serialiseRequest(body);
            String responseBody = sendSync(payload);
            lastRoot = mapper.parseResponseJson(responseBody);

            String stopReason = stringOrNull(lastRoot.get("stop_reason"));
            List<AnthropicMessagesMapper.ToolUse> toolUses = AnthropicMessagesMapper.extractToolUses(lastRoot);

            if (!"tool_use".equals(stopReason) || toolUses.isEmpty()) {
                return mapper.toChatResponse(lastRoot, model.modelId());
            }
            if (callbacks.isEmpty()) {
                throw new AnthropicTransportException(
                        "Model asked for tools but the prompt provided no ToolCallbacks — id="
                                + toolUses.get(0).id() + " name=" + toolUses.get(0).name());
            }
            log.debug("[anthropic] iteration={} model returned {} tool_use block(s)", iteration + 1, toolUses.size());

            List<Map<String, Object>> assistantContent = AnthropicMessagesMapper.extractAssistantContent(lastRoot);
            AnthropicMessagesMapper.appendAssistantToolUses(messages, assistantContent);

            List<String> outputs = new ArrayList<>(toolUses.size());
            for (AnthropicMessagesMapper.ToolUse use : toolUses) {
                outputs.add(executeToolCallback(callbacks, use));
            }
            AnthropicMessagesMapper.appendToolResults(messages, toolUses, outputs);
        }

        log.warn("[anthropic] tool loop exceeded {} iterations — returning last partial response", MAX_TOOL_ITERATIONS);
        return mapper.toChatResponse(lastRoot, model.modelId());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        List<ToolCallback> callbacks = toolCallbacks(prompt);
        if (!callbacks.isEmpty()) {
            log.debug("[anthropic] tools present — degrading stream() to sync call()");
            return Flux.just(call(prompt));
        }

        Map<String, Object> body = mapper.buildRequestBody(prompt, model);
        body.put("stream", true);
        String payload = mapper.serialiseRequest(body);

        return Flux.create(sink -> {
            try {
                HttpRequest request = newRequest(payload);
                HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String fallback = collectErrorBody(response);
                    sink.error(new AnthropicTransportException(
                            "Streaming Anthropic call failed: HTTP %d — %s"
                                    .formatted(response.statusCode(), truncate(fallback, 500))));
                    return;
                }
                AnthropicMessagesSseDecoder decoder = new AnthropicMessagesSseDecoder(mapper, model.modelId());
                try (Stream<String> lines = response.body()) {
                    lines.forEach(line -> decoder.feed(line).ifPresent(sink::next));
                }
                decoder.complete().ifPresent(sink::next);
                sink.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sink.error(new AnthropicTransportException("Streaming Anthropic call interrupted"));
            } catch (Exception e) {
                sink.error(new AnthropicTransportException("Streaming Anthropic call failed: " + e.getMessage(), e));
            }
        });
    }

    @Override
    public String describe() {
        return "anthropic-messages@" + spec.url();
    }

    /* ====================== Internals ====================== */

    private String sendSync(String payload) {
        try {
            HttpRequest request = newRequest(payload);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (code < 200 || code >= 300) {
                throw new AnthropicTransportException(
                        "Anthropic call failed: HTTP %d — %s".formatted(code, truncate(body, 500)));
            }
            return body;
        } catch (AnthropicTransportException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AnthropicTransportException("Anthropic call interrupted");
        } catch (Exception e) {
            throw new AnthropicTransportException("Anthropic call failed: " + e.getMessage(), e);
        }
    }

    private HttpRequest newRequest(String payload) {
        String authValue = useBearerPrefix ? "Bearer " + spec.apiKey() : spec.apiKey();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(spec.url()))
                .timeout(requestTimeout)
                .header(authHeaderName, authValue)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        applyExtraHeaders(builder, spec.extraHeaders());
        return builder.build();
    }

    private static String collectErrorBody(HttpResponse<Stream<String>> response) {
        try (Stream<String> body = response.body()) {
            return body == null ? "" : String.join("\n", body.limit(50).toList());
        } catch (Exception e) {
            return "(unreadable body: " + e.getMessage() + ")";
        }
    }

    private static List<ToolCallback> toolCallbacks(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (!(options instanceof ToolCallingChatOptions toolOptions)) return List.of();
        List<ToolCallback> callbacks = toolOptions.getToolCallbacks();
        return callbacks == null ? List.of() : new ArrayList<>(callbacks);
    }

    private String executeToolCallback(List<ToolCallback> callbacks, AnthropicMessagesMapper.ToolUse use) {
        for (ToolCallback cb : callbacks) {
            if (use.name().equals(cb.getToolDefinition().name())) {
                try {
                    String args;
                    try {
                        args = objectMapper.writeValueAsString(use.input());
                    } catch (Exception e) {
                        args = "{}";
                    }
                    return cb.call(args);
                } catch (Exception e) {
                    log.warn("[anthropic] Tool '{}' threw: {}", use.name(), e.getMessage());
                    return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                }
            }
        }
        log.warn("[anthropic] Model requested unknown tool '{}' (id={}) — returning stub error",
                use.name(), use.id());
        return "{\"error\":\"unknown tool: " + escapeJson(use.name()) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static void applyExtraHeaders(HttpRequest.Builder builder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) continue;
            if (RESERVED_HEADERS.contains(key.toLowerCase())) {
                log.debug("[anthropic] Skipping reserved header override: {}", key);
                continue;
            }
            builder.header(key, entry.getValue());
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public static final class AnthropicTransportException extends RuntimeException {
        public AnthropicTransportException(String message) { super(message); }
        public AnthropicTransportException(String message, Throwable cause) { super(message, cause); }
    }
}
