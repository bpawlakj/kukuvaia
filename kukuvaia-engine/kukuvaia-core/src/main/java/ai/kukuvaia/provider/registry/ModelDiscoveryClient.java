package ai.kukuvaia.provider.registry;

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

    /**
     * Discover available models from the provider.
     *
     * @param baseUrl    provider's base URL (e.g., "https://llm.example.com")
     * @param apiKey     resolved API key
     * @return list of discovered models
     * @throws ModelDiscoveryException on connection, auth, or parsing errors
     */
    public List<DiscoveredModel> discover(String baseUrl, String apiKey) {
        String url = normalizeUrl(baseUrl) + "/v1/models";
        log.info("Discovering models from: {}", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return switch (response.statusCode()) {
                case 200 -> parseModelsResponse(response.body());
                case 401, 403 -> throw new ModelDiscoveryException(
                        "Authentication failed (HTTP %d). Check API key for this provider.".formatted(response.statusCode()));
                case 404 -> throw new ModelDiscoveryException(
                        "Endpoint /v1/models not found (HTTP 404). Provider may not support model listing.");
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

    /**
     * Quick connectivity test — calls /v1/models and returns latency.
     *
     * @return latency in milliseconds, or throws on failure
     */
    public long testConnectivity(String baseUrl, String apiKey) {
        long start = System.currentTimeMillis();
        discover(baseUrl, apiKey);
        return System.currentTimeMillis() - start;
    }

    /** Reasoning field aliases known across OpenAI-compatible providers. */
    private static final List<String> REASONING_FIELDS =
            List.of("reasoning", "reasoning_content", "thinking", "think", "thought");

    /**
     * Test a specific model by sending a minimal chat completion request.
     *
     * @return test result with latency, response text, and a thinking-detection signal
     * @throws ModelDiscoveryException on connection, auth, or model errors
     */
    public ModelTestResult testModel(String baseUrl, String apiKey, String modelId) {
        return testModel(baseUrl, apiKey, modelId, Map.of());
    }

    /**
     * Test a specific model with optional extra config (e.g. {@code thinking=true},
     * {@code reasoning_effort=medium}). When {@code thinking} is set the request
     * includes {@code reasoning: {effort: ...}} so thinking models return both
     * reasoning and final content per OpenRouter's convention.
     */
    public ModelTestResult testModel(String baseUrl, String apiKey, String modelId,
                                     Map<String, Object> config) {
        String url = normalizeUrl(baseUrl) + "/v1/chat/completions";
        log.info("Testing model {} at: {} (config keys: {})", modelId, url,
                config != null ? config.keySet() : List.of());

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", modelId);
        body.put("messages", List.of(Map.of("role", "user", "content", "Say hello in one word.")));
        body.put("max_tokens", config != null && Boolean.TRUE.equals(config.get("thinking")) ? 2048 : 10);

        if (config != null && Boolean.TRUE.equals(config.get("thinking"))) {
            Object effort = config.getOrDefault("reasoning_effort", "medium");
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
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = System.currentTimeMillis() - start;

            return switch (response.statusCode()) {
                case 200 -> parseChatResponse(response.body(), latencyMs);
                case 401, 403 -> throw new ModelDiscoveryException(
                        "Authentication failed (HTTP %d).".formatted(response.statusCode()));
                case 404 -> throw new ModelDiscoveryException(
                        "Model '%s' not found or endpoint unavailable (HTTP 404).".formatted(modelId));
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

    public static class ModelDiscoveryException extends RuntimeException {
        public ModelDiscoveryException(String message) {
            super(message);
        }
    }
}
