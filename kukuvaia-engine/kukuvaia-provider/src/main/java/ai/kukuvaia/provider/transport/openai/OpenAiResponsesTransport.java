package ai.kukuvaia.provider.transport.openai;

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
 * Transport for OpenAI's {@code POST /responses} endpoint, used by GPT-5.x, o-series,
 * Gemini-via-Copilot — anything the legacy {@code /chat/completions} surface doesn't
 * accept. Spring AI 1.1 does not yet support this endpoint
 * (<a href="https://github.com/spring-projects/spring-ai/issues/2962">#2962</a>);
 * we speak it directly with {@code java.net.http.HttpClient}.
 *
 * <p>Phase 2 capabilities:
 * <ul>
 *   <li><b>Tool calling</b> — Variant B from {@code docs/architecture/auth-and-providers.md}:
 *       the loop runs inside the transport. {@link ToolCallback}s from the prompt are
 *       executed locally, results are stitched back as {@code function_call_output} items,
 *       and the model is re-invoked until the output contains only a {@code message}.</li>
 *   <li><b>Streaming</b> — text-only streaming via SSE. When tools are present in the
 *       prompt the implementation degrades to a single-element {@link Flux} backed by
 *       {@link #call(Prompt)}, since the Responses tool loop spans multiple HTTP
 *       round-trips and Spring AI advisors expect a single coherent stream.</li>
 *   <li><b>Reasoning content</b> — concatenated into {@code Generation.metadata.reasoning}
 *       inside the mapper, so dashboards and audit logs can record the model's chain
 *       of thought without showing it to the user.</li>
 * </ul>
 */
public final class OpenAiResponsesTransport implements ChatTransport {

    private static final Logger log = LoggerFactory.getLogger(OpenAiResponsesTransport.class);

    private static final Set<String> RESERVED_HEADERS = Set.of(
            "authorization", "content-type", "accept");

    /** Hard cap on the local tool-call loop to keep a misbehaving model from looping forever. */
    private static final int MAX_TOOL_ITERATIONS = 20;

    private final TransportSpec spec;
    private final ModelRecord model;
    private final OpenAiResponsesMapper mapper;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public OpenAiResponsesTransport(TransportSpec spec, ModelRecord model,
                                    HttpClient httpClient, ObjectMapper objectMapper,
                                    Duration requestTimeout) {
        this.spec = spec;
        this.model = model;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.mapper = new OpenAiResponsesMapper(objectMapper);
        this.requestTimeout = requestTimeout;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        List<ToolCallback> callbacks = toolCallbacks(prompt);
        Map<String, Object> body = mapper.buildRequestBody(prompt, model);
        mapper.addTools(body, callbacks);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) body.get("input");

        Map<String, Object> lastRoot = null;
        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            String payload = mapper.serialiseRequest(body);
            String responseBody = sendSync(payload);
            lastRoot = mapper.parseResponseJson(responseBody);

            List<OpenAiResponsesMapper.FunctionCall> calls =
                    OpenAiResponsesMapper.extractFunctionCalls(lastRoot);
            if (calls.isEmpty()) {
                return mapper.toChatResponse(lastRoot, model.modelId());
            }
            if (callbacks.isEmpty()) {
                throw new ResponsesTransportException(
                        "Model asked for tools but the prompt provided no ToolCallbacks — call_id="
                                + calls.get(0).callId() + " name=" + calls.get(0).name());
            }
            log.debug("[responses] iteration={} model returned {} function_call(s)", iteration + 1, calls.size());
            for (OpenAiResponsesMapper.FunctionCall call : calls) {
                OpenAiResponsesMapper.appendFunctionCall(input, call);
                String result = executeToolCallback(callbacks, call);
                OpenAiResponsesMapper.appendFunctionCallOutput(input, call.callId(), result);
            }
        }

        log.warn("[responses] tool loop exceeded {} iterations — returning last partial response", MAX_TOOL_ITERATIONS);
        return mapper.toChatResponse(lastRoot, model.modelId());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        List<ToolCallback> callbacks = toolCallbacks(prompt);
        if (!callbacks.isEmpty()) {
            // Tool flow needs multiple round-trips. We can't interleave that with a single
            // SSE stream cleanly, so fall back to sync — Spring AI advisors get one final
            // ChatResponse, same as call(). True streaming-with-tools is Phase 3 work.
            log.debug("[responses] tools present — degrading stream() to sync call()");
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
                    sink.error(new ResponsesTransportException(
                            "Streaming Responses call failed: HTTP %d — %s"
                                    .formatted(response.statusCode(), truncate(fallback, 500))));
                    return;
                }
                OpenAiResponsesSseDecoder decoder = new OpenAiResponsesSseDecoder(mapper, model.modelId());
                try (Stream<String> lines = response.body()) {
                    lines.forEach(line -> decoder.feed(line).ifPresent(sink::next));
                }
                decoder.complete().ifPresent(sink::next);
                sink.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sink.error(new ResponsesTransportException("Streaming Responses call interrupted"));
            } catch (Exception e) {
                sink.error(new ResponsesTransportException("Streaming Responses call failed: " + e.getMessage(), e));
            }
        });
    }

    @Override
    public String describe() {
        return "openai-responses@" + spec.url();
    }

    /* ====================== Internals ====================== */

    private String sendSync(String payload) {
        try {
            HttpRequest request = newRequest(payload);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            if (code < 200 || code >= 300) {
                throw new ResponsesTransportException(
                        "Responses call failed: HTTP %d — %s".formatted(code, truncate(body, 500)));
            }
            return body;
        } catch (ResponsesTransportException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponsesTransportException("Responses call interrupted");
        } catch (Exception e) {
            throw new ResponsesTransportException("Responses call failed: " + e.getMessage(), e);
        }
    }

    private HttpRequest newRequest(String payload) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(spec.url()))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + spec.apiKey())
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

    private static String executeToolCallback(List<ToolCallback> callbacks,
                                              OpenAiResponsesMapper.FunctionCall call) {
        for (ToolCallback cb : callbacks) {
            if (call.name().equals(cb.getToolDefinition().name())) {
                try {
                    return cb.call(call.arguments());
                } catch (Exception e) {
                    log.warn("[responses] Tool '{}' threw: {}", call.name(), e.getMessage());
                    return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                }
            }
        }
        log.warn("[responses] Model requested unknown tool '{}' (call_id={}) — returning stub error",
                call.name(), call.callId());
        return "{\"error\":\"unknown tool: " + escapeJson(call.name()) + "\"}";
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
                log.debug("[responses] Skipping reserved header override: {}", key);
                continue;
            }
            builder.header(key, entry.getValue());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public static final class ResponsesTransportException extends RuntimeException {
        public ResponsesTransportException(String message) { super(message); }
        public ResponsesTransportException(String message, Throwable cause) { super(message, cause); }
    }
}
