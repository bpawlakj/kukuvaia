package ai.kukuvaia.provider.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import ai.kukuvaia.provider.model.DiscoveredModel;
import ai.kukuvaia.provider.dto.ModelTestResult;

/**
 * Discovers available models from a provider's OpenAI-compatible /v1/models endpoint.
 * Handles authentication, timeouts, and error responses gracefully.
 */
@Component
public class ModelDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(ModelDiscoveryClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ModelDiscoveryClient(ObjectMapper objectMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.objectMapper = objectMapper;
    }

    /** Replace the HTTP client (visible for testing). */
    void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /** Backward-compat overload — no per-provider config. */
    public List<DiscoveredModel> discover(String baseUrl, String apiKey) {
        return discover(baseUrl, apiKey, Map.of());
    }

    /**
     * Discover available models from the provider.
     *
     * @param baseUrl        provider's base URL (e.g., "https://llm.example.com")
     * @param apiKey         resolved API key
     * @param providerConfig optional per-provider overrides: {@code models-path}
     *                       (defaults to {@code /v1/models}) and {@code headers}
     *                       (extra HTTP headers, e.g. Copilot-Integration-Id)
     * @return list of discovered models
     * @throws ModelDiscoveryException on connection, auth, or parsing errors
     */
    public List<DiscoveredModel> discover(String baseUrl, String apiKey, Map<String, Object> providerConfig) {
        String url = buildModelsUrl(baseUrl, providerConfig);
        log.info("Discovering models from: {}", url);

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .timeout(TIMEOUT)
                    .GET();
            applyExtraHeaders(builder, providerConfig);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            return switch (response.statusCode()) {
                case 200 -> parseModelsResponse(response.body());
                case 401, 403 -> throw new ModelDiscoveryException(
                        "Authentication failed (HTTP %d). Check API key for this provider.".formatted(response.statusCode()));
                case 404 -> throw new ModelDiscoveryException(
                        "Models endpoint not found (HTTP 404) at %s. If the provider does not use the OpenAI /v1 prefix, set config.models-path."
                                .formatted(url));
                default -> throw new ModelDiscoveryException(
                        "Unexpected response (HTTP %d) from %s".formatted(response.statusCode(), url));
            };
        } catch (ModelDiscoveryException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ModelDiscoveryException("Connection timed out after %ds: %s".formatted(TIMEOUT.toSeconds(), url));
        } catch (Exception e) {
            throw new ModelDiscoveryException("Failed to connect to %s: %s".formatted(url, e.getMessage()));
        }
    }

    /** Backward-compat overload — no per-provider config. */
    public long testConnectivity(String baseUrl, String apiKey) {
        return testConnectivity(baseUrl, apiKey, Map.of());
    }

    /** Quick connectivity test — calls the models endpoint and returns latency. */
    public long testConnectivity(String baseUrl, String apiKey, Map<String, Object> providerConfig) {
        long start = System.currentTimeMillis();
        discover(baseUrl, apiKey, providerConfig);
        return System.currentTimeMillis() - start;
    }

    /** Reasoning field aliases known across OpenAI-compatible providers. */
    private static final List<String> REASONING_FIELDS =
            List.of("reasoning", "reasoning_content", "thinking", "think", "thought");

    /** Backward-compat overload — no per-provider config, no request config. */
    public ModelTestResult testModel(String baseUrl, String apiKey, String modelId) {
        return testModel(baseUrl, apiKey, modelId, Map.of(), Map.of());
    }

    /** Backward-compat overload — request config only (thinking/reasoning), no provider config. */
    public ModelTestResult testModel(String baseUrl, String apiKey, String modelId,
                                     Map<String, Object> requestConfig) {
        return testModel(baseUrl, apiKey, modelId, Map.of(), requestConfig);
    }

    /**
     * Test a specific model with optional provider and request config.
     *
     * @param providerConfig per-provider overrides ({@code completions-path}, {@code headers})
     * @param requestConfig  per-call request shape ({@code thinking=true},
     *                       {@code reasoning_effort=medium}) — when {@code thinking} is set
     *                       the request includes {@code reasoning: {effort: ...}}
     */
    public ModelTestResult testModel(String baseUrl, String apiKey, String modelId,
                                     Map<String, Object> providerConfig,
                                     Map<String, Object> requestConfig) {
        String url = buildCompletionsUrl(baseUrl, providerConfig);
        log.info("Testing model {} at: {} (config keys: {})", modelId, url,
                requestConfig != null ? requestConfig.keySet() : List.of());

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", modelId);
        body.put("messages", List.of(Map.of("role", "user", "content", "Say hello in one word.")));
        body.put("max_tokens", requestConfig != null && Boolean.TRUE.equals(requestConfig.get("thinking")) ? 2048 : 10);

        if (requestConfig != null && Boolean.TRUE.equals(requestConfig.get("thinking"))) {
            Object effort = requestConfig.getOrDefault("reasoning_effort", "medium");
            body.put("reasoning", Map.of("effort", effort));
        }

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ModelDiscoveryException("Failed to build test request: " + e.getMessage());
        }

        try {
            long start = System.currentTimeMillis();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody));
            applyExtraHeaders(builder, providerConfig);

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - start;

            return switch (response.statusCode()) {
                case 200 -> parseChatResponse(response.body(), latencyMs);
                case 401, 403 -> throw new ModelDiscoveryException(
                        "Authentication failed (HTTP %d).".formatted(response.statusCode()));
                case 404 -> throw new ModelDiscoveryException(
                        "Model '%s' not found or endpoint unavailable (HTTP 404) at %s. If the provider does not use the OpenAI /v1 prefix, set config.completions-path."
                                .formatted(modelId, url));
                default -> throw new ModelDiscoveryException(
                        "Model test failed (HTTP %d): %s".formatted(response.statusCode(), response.body()));
            };
        } catch (ModelDiscoveryException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ModelDiscoveryException("Model test timed out after 60s");
        } catch (Exception e) {
            throw new ModelDiscoveryException("Model test failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ModelTestResult parseChatResponse(String body, long latencyMs) {
        try {
            Map<String, Object> json = objectMapper.readValue(body, new TypeReference<>() {});
            var choices = (List<Map<String, Object>>) json.get("choices");
            if (choices == null || choices.isEmpty()) {
                return ModelTestResult.ok(latencyMs, "(no choices in response)");
            }
            var message = (Map<String, Object>) choices.getFirst().get("message");
            if (message == null) {
                return ModelTestResult.ok(latencyMs, "(no message in choice)");
            }

            Object content = message.get("content");
            String contentStr = content != null ? content.toString() : "";
            boolean contentBlank = contentStr.isBlank();

            if (!contentBlank) {
                return ModelTestResult.ok(latencyMs, contentStr);
            }

            // Content blank — is there a reasoning field? That's the thinking-model signal.
            for (String field : REASONING_FIELDS) {
                Object val = message.get(field);
                if (val != null && !val.toString().isBlank()) {
                    String preview = truncate(val.toString(), 120);
                    return ModelTestResult.thinkingDetected(latencyMs, preview, field);
                }
            }

            return ModelTestResult.ok(latencyMs, "(empty content, no reasoning field)");
        } catch (Exception e) {
            return ModelTestResult.ok(latencyMs, "(could not parse response: " + e.getMessage() + ")");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @SuppressWarnings("unchecked")
    private List<DiscoveredModel> parseModelsResponse(String body) {
        try {
            Map<String, Object> json = objectMapper.readValue(body, new TypeReference<>() {});
            Object data = json.get("data");
            if (!(data instanceof List<?> dataList)) {
                throw new ModelDiscoveryException("Response missing 'data' array");
            }

            return dataList.stream()
                    .filter(item -> item instanceof Map)
                    .map(item -> (Map<String, Object>) item)
                    .map(item -> new DiscoveredModel(
                            String.valueOf(item.get("id")),
                            item.get("owned_by") != null ? String.valueOf(item.get("owned_by")) : null))
                    .distinct()
                    .toList();
        } catch (ModelDiscoveryException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelDiscoveryException("Failed to parse /v1/models response: " + e.getMessage());
        }
    }

    private String normalizeUrl(String baseUrl) {
        String url = baseUrl.trim();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        // Remove /v1 suffix if already present to avoid /v1/v1/models
        if (url.endsWith("/v1")) url = url.substring(0, url.length() - 3);
        return url;
    }

    /**
     * Resolve the models discovery URL. Honours {@code config.models-path} when set
     * (e.g. {@code /models} for GitHub Copilot); otherwise defaults to
     * {@code /v1/models}. Both absolute paths and paths missing the leading slash
     * are accepted.
     */
    String buildModelsUrl(String baseUrl, Map<String, Object> providerConfig) {
        String path = pathFromConfig(providerConfig, "models-path", "/v1/models");
        return normalizeUrl(baseUrl) + path;
    }

    /**
     * Resolve the chat-completions URL. Honours {@code config.completions-path}
     * (defaults to {@code /v1/chat/completions}).
     */
    String buildCompletionsUrl(String baseUrl, Map<String, Object> providerConfig) {
        String path = pathFromConfig(providerConfig, "completions-path", "/v1/chat/completions");
        return normalizeUrl(baseUrl) + path;
    }

    private static String pathFromConfig(Map<String, Object> providerConfig, String key, String defaultPath) {
        if (providerConfig == null) return defaultPath;
        Object value = providerConfig.get(key);
        if (value == null) return defaultPath;
        String s = value.toString().trim();
        if (s.isBlank()) return defaultPath;
        return s.startsWith("/") ? s : "/" + s;
    }

    /**
     * Apply provider-specific HTTP headers from {@code config.headers}. Skips
     * blank keys and null values. Reserved headers ({@code Authorization},
     * {@code Content-Type}, {@code Accept}) are not overridden — they are set by
     * the caller and must not be silently changed.
     */
    private void applyExtraHeaders(HttpRequest.Builder builder, Map<String, Object> providerConfig) {
        if (providerConfig == null) return;
        Object headers = providerConfig.get("headers");
        if (!(headers instanceof Map<?, ?> headerMap)) return;
        for (Map.Entry<?, ?> entry : headerMap.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().toString().trim();
            if (key.isEmpty()) continue;
            if (key.equalsIgnoreCase("Authorization")
                    || key.equalsIgnoreCase("Content-Type")
                    || key.equalsIgnoreCase("Accept")) {
                log.debug("[discovery] Skipping reserved header override: {}", key);
                continue;
            }
            builder.header(key, entry.getValue().toString());
        }
    }

    public static class ModelDiscoveryException extends RuntimeException {
        public ModelDiscoveryException(String message) {
            super(message);
        }
    }
}
