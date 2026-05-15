package ai.kukuvaia.provider.service;

import ai.kukuvaia.provider.transport.ChatTransport;
import ai.kukuvaia.provider.transport.ChatTransportFactory;
import ai.kukuvaia.provider.transport.TransportChatModelAdapter;
import ai.kukuvaia.provider.transport.TransportSpec;
import ai.kukuvaia.provider.transport.anthropic.AnthropicMessagesTransport;
import ai.kukuvaia.provider.transport.openai.OpenAiChatCompletionsTransport;
import ai.kukuvaia.provider.transport.openai.OpenAiResponsesTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    @Value("${kukuvaia.llm.request-timeout:120}")
    private int requestTimeoutSeconds;

    @Value("${kukuvaia.llm.connect-timeout:10}")
    private int connectTimeoutSeconds;

    public ChatModelFactory(ChatTransportFactory transportFactory, ObjectMapper objectMapper) {
        this.transportFactory = transportFactory;
        this.objectMapper = objectMapper;
        // Single HttpClient shared by all custom transports — honours system proxy and
        // the JVM-wide DNS TTL set in KukuvaiaApplication.
        this.sharedHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
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
        OpenAiChatOptions defaultOptions = buildDefaultOptions(model);

        log.info("Created ChatModel: provider={}, model={}, transport={}, source={}",
                provider.name(), model.modelId(), transport.describe(), spec.source());
        return new TransportChatModelAdapter(transport, defaultOptions);
    }

    private ChatTransport buildTransport(TransportSpec spec, ProviderRecord provider, ModelRecord model) {
        return switch (spec.type()) {
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiChatCompletionsTransport(
                    buildOpenAiChatModel(spec, provider, model), spec);
            case OPENAI_RESPONSES -> new OpenAiResponsesTransport(
                    spec, model, sharedHttpClient, objectMapper, Duration.ofSeconds(requestTimeoutSeconds));
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesTransport(
                    spec, model, sharedHttpClient, objectMapper,
                    Duration.ofSeconds(requestTimeoutSeconds),
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

    private ChatModel buildOpenAiChatModel(TransportSpec spec, ProviderRecord provider, ModelRecord model) {
        var requestFactory = new ReactorClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(requestTimeoutSeconds));

        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        spec.extraHeaders().forEach(restClientBuilder::defaultHeader);

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(spec.baseUrl())
                .apiKey(spec.apiKey())
                .restClientBuilder(restClientBuilder)
                .completionsPath(spec.path());

        OpenAiChatOptions options = buildDefaultOptions(model);
        log.debug("[factory] OpenAI ChatCompletions transport: path={} headers={}", spec.path(), spec.extraHeaders().keySet());

        return OpenAiChatModel.builder()
                .openAiApi(apiBuilder.build())
                .defaultOptions(options)
                .build();
    }

    private OpenAiChatOptions buildDefaultOptions(ModelRecord model) {
        double temperature = extractDouble(model.config(), "temperature", 0.2);
        boolean thinking = model.config() != null && Boolean.TRUE.equals(model.config().get("thinking"));
        String reasoningEffort = thinking ? extractString(model.config(), "reasoning_effort", "medium") : null;

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(model.modelId())
                .maxTokens(model.maxTokens())
                .temperature(temperature)
                .internalToolExecutionEnabled(false);
        if (reasoningEffort != null) optionsBuilder.reasoningEffort(reasoningEffort);
        return optionsBuilder.build();
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
