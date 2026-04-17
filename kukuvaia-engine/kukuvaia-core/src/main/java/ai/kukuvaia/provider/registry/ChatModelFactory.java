package ai.kukuvaia.provider.registry;

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

import java.time.Duration;

/**
 * Creates {@link ChatModel} instances from DB-stored provider and model records.
 * All providers are OpenAI-compatible (SmartGate, OpenRouter, OpenAI, Anthropic proxy, Ollama).
 * Same factory works for all — only baseUrl, apiKey, and model differ.
 */
@Component
public class ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatModelFactory.class);

    private final SecretResolver secretResolver;

    @Value("${kukuvaia.llm.request-timeout:120}")
    private int requestTimeoutSeconds;

    @Value("${kukuvaia.llm.connect-timeout:10}")
    private int connectTimeoutSeconds;

    public ChatModelFactory(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    /**
     * Create a ChatModel from provider and model records.
     *
     * @param provider the provider with baseUrl and apiKeyRef
     * @param model    the model with modelId, maxTokens, and config
     * @return a ready-to-use ChatModel
     * @throws SecretResolver.SecretNotFoundException if the API key reference cannot be resolved
     */
    public ChatModel create(ProviderRecord provider, ModelRecord model) {
        String apiKey = secretResolver.resolve(provider.apiKeyRef());

        var requestFactory = new ReactorClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(requestTimeoutSeconds));

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(provider.baseUrl())
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .build();

        double temperature = extractDouble(model.config(), "temperature", 0.2);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model.modelId())
                .maxTokens(model.maxTokens())
                .temperature(temperature)
                .internalToolExecutionEnabled(false)
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();

        log.info("Created ChatModel: provider={}, model={}, maxTokens={}, temperature={}",
                provider.name(), model.modelId(), model.maxTokens(), temperature);

        return chatModel;
    }

    private double extractDouble(java.util.Map<String, Object> config, String key, double defaultValue) {
        if (config == null || !config.containsKey(key)) return defaultValue;
        Object value = config.get(key);
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
