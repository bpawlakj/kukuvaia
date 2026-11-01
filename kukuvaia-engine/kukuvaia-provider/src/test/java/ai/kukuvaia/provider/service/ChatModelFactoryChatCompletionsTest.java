package ai.kukuvaia.provider.service;

import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.model.ProviderRecord;
import ai.kukuvaia.provider.secret.SecretResolver;
import ai.kukuvaia.provider.transport.ChatTransportFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-level test of the Spring AI 2.x / openai-java Chat Completions path built by
 * {@link ChatModelFactory}: a local HTTP server stands in for the provider.
 */
@DisplayName("ChatModelFactory — OpenAI Chat Completions over the wire")
class ChatModelFactoryChatCompletionsTest {

    private static final String COMPLETION = """
            {"id":"c1","object":"chat.completion","created":1,"model":"gpt-4o",
             "choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}""";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final AtomicReference<String> customHeader = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicBoolean failWith503 = new AtomicBoolean();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Test"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.incrementAndGet();
            if (failWith503.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] response = COMPLETION.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ChatModelFactory factory(String resolvedKey) {
        return factory(resolvedKey, 1);
    }

    private ChatModelFactory factory(String resolvedKey, int maxAttempts) {
        SecretResolver secrets = reference -> resolvedKey;
        return new ChatModelFactory(new ChatTransportFactory(secrets), json, 120, 10, maxAttempts);
    }

    private ProviderRecord provider(Map<String, Object> config) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new ProviderRecord(UUID.randomUUID(), "local", "openai", baseUrl, "API_KEY", true, 0,
                config, Instant.now(), Instant.now());
    }

    private static ModelRecord model() {
        // "gpt-4o" routes to the Chat Completions transport by heuristic.
        return new ModelRecord(UUID.randomUUID(), UUID.randomUUID(), "gpt-4o", "GPT-4o",
                List.of("chat"), "standard", 4096, null, true, Map.of("temperature", 0.3), null,
                Instant.now(), Instant.now());
    }

    @Test
    void completionTokenCapReachesTheNativeChatCompletionsRequest() {
        ChatModel chatModel = factory("test-key").create(provider(Map.of()), model());
        chatModel.call(new Prompt("hi", org.springframework.ai.openai.OpenAiChatOptions.builder()
                .maxCompletionTokens(32).build()));

        var request = json.readTree(body.get());
        assertThat(request.path("max_completion_tokens").asInt()).isEqualTo(32);
        assertThat(request.has("max_tokens")).isFalse();
    }

    @Test
    @DisplayName("model defaults are merged under request options; extra-body, auth and headers reach the wire")
    void chatCompletions_mergesDefaultsAndSendsExtraBody() {
        ChatModel chatModel = factory("secret-key").create(provider(Map.of(
                "headers", Map.of("X-Test", "yes"),
                "extra-body", Map.of("request_timeout", 120))), model());

        // Generic ChatOptions, as produced by a ChatClient — only overrides temperature.
        ChatResponse response = chatModel.call(
                new Prompt("ping", ChatOptions.builder().temperature(0.7).build()));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("pong");
        assertThat(path.get()).isEqualTo("/v1/chat/completions");
        assertThat(authHeader.get()).isEqualTo("Bearer secret-key");
        assertThat(customHeader.get()).isEqualTo("yes");

        JsonNode request = json.readTree(body.get());
        assertThat(request.get("model").asString()).isEqualTo("gpt-4o");          // model default
        assertThat(request.get("temperature").asDouble()).isEqualTo(0.7);         // request override
        JsonNode maxTokens = request.has("max_tokens") ? request.get("max_tokens") : request.get("max_completion_tokens");
        assertThat(maxTokens.asInt()).isEqualTo(4096);                            // model default
        assertThat(request.get("request_timeout").asInt()).isEqualTo(120);        // provider extra-body
    }

    @Test
    @DisplayName("without extra-body nothing extra is sent (strict providers reject unknown fields)")
    void chatCompletions_noExtraBodyByDefault() {
        factory("secret-key").create(provider(Map.of()), model()).call(new Prompt("ping"));

        JsonNode request = json.readTree(body.get());
        assertThat(request.has("request_timeout")).isFalse();
        assertThat(request.get("temperature").asDouble()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("empty API key = no-auth mode: Authorization header is not sent")
    void chatCompletions_emptyKey_noAuthorizationHeader() {
        factory("").create(provider(Map.of()), model()).call(new Prompt("ping"));

        assertThat(authHeader.get()).isNull();
    }

    @Test
    @DisplayName("retries follow spring.ai.retry.max-attempts (total attempts, not retries)")
    void chatCompletions_retriesFollowMaxAttempts() {
        failWith503.set(true);
        ChatModel chatModel = factory("k", 2).create(provider(Map.of()), model());

        assertThatThrownBy(() -> chatModel.call(new Prompt("ping"))).isInstanceOf(RuntimeException.class);
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a completions path the SDK cannot express fails fast")
    void chatCompletions_unsupportedPath_failsFast() {
        var config = Map.<String, Object>of("paths", Map.of("openai-chat-completions", "/v1/custom-endpoint"));

        assertThatThrownBy(() -> factory("k").create(provider(config), model()))
                .isInstanceOf(ChatModelFactory.UnsupportedTransportException.class)
                .hasMessageContaining("/chat/completions");
    }

    @Test
    @DisplayName("resolveExtraBody — ignores missing / empty / non-map values")
    void resolveExtraBody_edgeCases() {
        assertThat(ChatModelFactory.resolveExtraBody(null)).isNull();
        assertThat(ChatModelFactory.resolveExtraBody(Map.of())).isNull();
        assertThat(ChatModelFactory.resolveExtraBody(Map.of("extra-body", "nope"))).isNull();
        assertThat(ChatModelFactory.resolveExtraBody(Map.of("extra-body", Map.of()))).isNull();
        assertThat(ChatModelFactory.resolveExtraBody(Map.of("extra-body", Map.of("a", 1))))
                .containsExactly(Map.entry("a", 1));
    }
}
