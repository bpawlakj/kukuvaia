package ai.kukuvaia.provider.service;

import ai.kukuvaia.provider.transport.ChatTransport;
import ai.kukuvaia.provider.transport.ChatTransportFactory;
import ai.kukuvaia.provider.transport.TransportChatModelAdapter;
import ai.kukuvaia.provider.transport.TransportSpec;
import ai.kukuvaia.provider.transport.anthropic.AnthropicMessagesTransport;
import ai.kukuvaia.provider.transport.openai.OpenAiChatCompletionsTransport;
import ai.kukuvaia.provider.transport.openai.OpenAiSdkClients;
import ai.kukuvaia.provider.transport.openai.OpenAiResponsesTransport;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.model.ProviderRecord;

/**
 * Creates {@link ChatModel} instances from DB-stored provider and model records.
 *
 * <p>Each (provider, model) pair is routed through {@link ChatTransportFactory} to a
 * concrete {@link ChatTransport}. The transport hides whether the wire format is the
 * legacy OpenAI {@code /chat/completions}, the newer {@code /responses}, or Anthropic
 * {@code /v1/messages} — Spring AI sees a single {@link ChatModel}.
 */
@Component
public class ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatModelFactory.class);

    private final ChatTransportFactory transportFactory;
    private final ObjectMapper objectMapper;
    private final HttpClient sharedHttpClient;
    private final Duration requestTimeout;
    private final Duration connectTimeout;
    private final int maxRetries;

    /**
     * @param requestTimeoutSeconds {@code kukuvaia.llm.request-timeout} — whole-request timeout
     * @param connectTimeoutSeconds {@code kukuvaia.llm.connect-timeout} — TCP connect timeout
     * @param maxAttempts           {@code spring.ai.retry.max-attempts} — total attempts per call
     *                              for the Chat Completions transport (retries = attempts - 1)
     */
    public ChatModelFactory(ChatTransportFactory transportFactory,
                            ObjectMapper objectMapper,
                            @Value("${kukuvaia.llm.request-timeout:120}") int requestTimeoutSeconds,
                            @Value("${kukuvaia.llm.connect-timeout:10}") int connectTimeoutSeconds,
                            @Value("${spring.ai.retry.max-attempts:2}") int maxAttempts) {
        this.transportFactory = transportFactory;
        this.objectMapper = objectMapper;
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.connectTimeout = Duration.ofSeconds(connectTimeoutSeconds);
        this.maxRetries = Math.max(0, maxAttempts - 1);
        // Single HttpClient shared by all custom transports — honours system proxy and
        // the JVM-wide DNS TTL set in KukuvaiaApplication.
        this.sharedHttpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /**
     * Create a ChatModel from provider and model records.
     */
    public ChatModel create(ProviderRecord provider, ModelRecord model) {
        log.info("[factory] Building ChatModel: provider.name={} provider.baseUrl={} provider.type={} " +
                        "model.modelId={} model.displayName={} model.tier={} model.maxTokens={} model.config={}",
                provider.name(), provider.baseUrl(), provider.type(),
                model.modelId(), model.displayName(), model.tier(), model.maxTokens(), model.config());

        TransportSpec spec = transportFactory.resolve(provider, model);
        ChatTransport transport = buildTransport(spec, provider, model);
        OpenAiChatOptions defaultOptions = buildDefaultOptions(model).build();

        log.info("Created ChatModel: provider={}, model={}, transport={}, source={}",
                provider.name(), model.modelId(), transport.describe(), spec.source());
        return new TransportChatModelAdapter(transport, defaultOptions);
    }

    private ChatTransport buildTransport(TransportSpec spec, ProviderRecord provider, ModelRecord model) {
        return switch (spec.type()) {
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiChatCompletionsTransport(
                    buildOpenAiChatModel(spec, model), spec,
                    buildDefaultOptions(model).extraBody(resolveExtraBody(provider.config())).build());
            case OPENAI_RESPONSES -> new OpenAiResponsesTransport(
                    spec, model, sharedHttpClient, objectMapper, requestTimeout);
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesTransport(
                    spec, model, sharedHttpClient, objectMapper,
                    requestTimeout,
                    resolveAuthHeader(provider));
        };
    }

    /**
     * Some providers need {@code x-api-key} instead of {@code Authorization: Bearer}
     * (native Anthropic API is the canonical case). Default is {@code Authorization}
     * which the Bearer-style Copilot proxy and OpenRouter expect.
     */
    private static String resolveAuthHeader(ProviderRecord provider) {
        if (provider.config() == null) return "Authorization";
        Object value = provider.config().get("auth-header");
        if (value == null) return "Authorization";
        String s = value.toString().trim();
        return s.isBlank() ? "Authorization" : s;
    }

    /**
     * Spring AI 2.x builds {@link OpenAiChatModel} on the official {@code openai-java} SDK
     * (OkHttp). The SDK clients are built by {@link OpenAiSdkClients} so connect timeout,
     * request timeout, retries and provider headers are all applied at client level. The SDK
     * always appends {@code /chat/completions} to the base
     * URL, so the configured path is translated by {@link #sdkBaseUrl(TransportSpec)}.
     */
    private ChatModel buildOpenAiChatModel(TransportSpec spec, ModelRecord model) {
        var clients = new OpenAiSdkClients(sdkBaseUrl(spec), spec.apiKey(), connectTimeout, requestTimeout,
                maxRetries, spec.extraHeaders());
        OpenAiChatOptions options = buildDefaultOptions(model).build();
        log.debug("[factory] OpenAI ChatCompletions transport: path={} headers={} maxRetries={}",
                spec.path(), spec.extraHeaders().keySet(), maxRetries);

        return OpenAiChatModel.builder()
                .openAiClient(clients.sync())
                .openAiClientAsync(clients.async())
                .options(options)
                .build();
    }

    /**
     * Optional {@code provider.config.extra-body}: extra top-level JSON fields sent with every
     * Chat Completions request. Opt-in, because strict providers (api.openai.com) reject unknown
     * fields. Typical use — litellm proxies such as SmartGate, whose internal default timeout
     * is 10 s: {@code extra-body: {request_timeout: 120}}.
     */
    static Map<String, Object> resolveExtraBody(Map<String, Object> config) {
        if (config == null) return null;
        Object value = config.get("extra-body");
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v != null) result.put(k.toString(), v);
        });
        return result.isEmpty() ? null : Map.copyOf(result);
    }

    /**
     * Converts {@code baseUrl + path} into the base URL the OpenAI SDK expects, i.e. the URL
     * without the trailing {@code /chat/completions} segment the SDK appends itself.
     */
    static String sdkBaseUrl(TransportSpec spec) {
        String suffix = "/chat/completions";
        String path = spec.path();
        if (!path.endsWith(suffix)) {
            throw new UnsupportedTransportException(
                    "OpenAI chat-completions path must end with '" + suffix + "' (got '" + path + "')");
        }
        return spec.baseUrl() + path.substring(0, path.length() - suffix.length());
    }

    private OpenAiChatOptions.Builder buildDefaultOptions(ModelRecord model) {
        double temperature = extractDouble(model.config(), "temperature", 0.2);
        boolean thinking = model.config() != null && Boolean.TRUE.equals(model.config().get("thinking"));
        String reasoningEffort = thinking ? extractString(model.config(), "reasoning_effort", "medium") : null;

        // Tool execution is never done inside the ChatModel in Spring AI 2.x — ToolCallingAdvisor
        // owns the loop — so the old internalToolExecutionEnabled(false) flag is gone.
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(model.modelId())
                .maxTokens(model.maxTokens())
                .temperature(temperature);
        if (reasoningEffort != null) optionsBuilder.reasoningEffort(reasoningEffort);
        return optionsBuilder;
    }

    /* ====================== Back-compat helpers for tests ====================== */

    /**
     * Legacy helper retained for {@code ChatModelFactoryTest} — reads
     * {@code provider.config.completions-path}. New code should rely on
     * {@link ChatTransportFactory} which understands the {@code paths} sub-map and
     * keys per transport type.
     */
    static String resolveCompletionsPath(Map<String, Object> config) {
        if (config == null) return null;
        Object value = config.get("completions-path");
        if (value == null) return null;
        String s = value.toString().trim();
        return s.isBlank() ? null : s;
    }

    /** Legacy helper retained for {@code ChatModelFactoryTest}. */
    static Map<String, String> resolveExtraHeaders(Map<String, Object> config) {
        Map<String, String> result = new LinkedHashMap<>();
        if (config == null) return result;
        Object headers = config.get("headers");
        if (!(headers instanceof Map<?, ?> headerMap)) return result;
        for (Map.Entry<?, ?> entry : headerMap.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().toString().trim();
            if (key.isEmpty()) continue;
            result.put(key, entry.getValue().toString());
        }
        return result;
    }

    private String extractString(Map<String, Object> config, String key, String defaultValue) {
        if (config == null || !config.containsKey(key)) return defaultValue;
        Object value = config.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private double extractDouble(Map<String, Object> config, String key, double defaultValue) {
        if (config == null || !config.containsKey(key)) return defaultValue;
        Object value = config.get(key);
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Thrown when a configured transport type exists but its implementation is not yet shipped. */
    public static class UnsupportedTransportException extends RuntimeException {
        public UnsupportedTransportException(String message) { super(message); }
    }

}
