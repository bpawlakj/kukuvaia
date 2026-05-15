package ai.kukuvaia.provider.transport;

import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.model.ProviderRecord;
import ai.kukuvaia.provider.secret.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@DisplayName("ChatTransportFactory — three-tier routing (model > rule > heuristic)")
@ExtendWith(MockitoExtension.class)
class ChatTransportFactoryTest {

    @Mock private SecretResolver secretResolver;
    private ChatTransportFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ChatTransportFactory(secretResolver);
        lenient().when(secretResolver.resolve(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("resolved-key");
    }

    private ProviderRecord provider(Map<String, Object> config) {
        return new ProviderRecord(UUID.randomUUID(), "test", "custom",
                "https://api.example.com", "API_KEY", true, 0,
                config, Instant.now(), Instant.now());
    }

    private ModelRecord model(String modelId, Map<String, Object> config) {
        return new ModelRecord(UUID.randomUUID(), UUID.randomUUID(), modelId, modelId,
                List.of("text"), "standard", 4096, null, true, config, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("heuristic — gpt-5.x → Responses; older OpenAI → ChatCompletions")
    void heuristic_gpt5_routesToResponses() {
        assertThat(ChatTransportFactory.heuristic("gpt-5.4-mini")).isEqualTo(TransportType.OPENAI_RESPONSES);
        assertThat(ChatTransportFactory.heuristic("gpt-5.5")).isEqualTo(TransportType.OPENAI_RESPONSES);
        assertThat(ChatTransportFactory.heuristic("gpt-4o")).isEqualTo(TransportType.OPENAI_CHAT_COMPLETIONS);
        assertThat(ChatTransportFactory.heuristic("gpt-4.1")).isEqualTo(TransportType.OPENAI_CHAT_COMPLETIONS);
    }

    @Test
    @DisplayName("heuristic — o-series and gemini → Responses; claude → Messages")
    void heuristic_oseriesAndClaude() {
        assertThat(ChatTransportFactory.heuristic("o1")).isEqualTo(TransportType.OPENAI_RESPONSES);
        assertThat(ChatTransportFactory.heuristic("o3-mini")).isEqualTo(TransportType.OPENAI_RESPONSES);
        assertThat(ChatTransportFactory.heuristic("gemini-2.5-pro")).isEqualTo(TransportType.OPENAI_RESPONSES);
        assertThat(ChatTransportFactory.heuristic("claude-sonnet-4.6")).isEqualTo(TransportType.ANTHROPIC_MESSAGES);
        assertThat(ChatTransportFactory.heuristic("claude-haiku-4.5")).isEqualTo(TransportType.ANTHROPIC_MESSAGES);
    }

    @Test
    @DisplayName("heuristic — unknown model → ChatCompletions fallback")
    void heuristic_unknown_fallsBackToChatCompletions() {
        assertThat(ChatTransportFactory.heuristic("eu.aws.weird-2"))
                .isEqualTo(TransportType.OPENAI_CHAT_COMPLETIONS);
        assertThat(ChatTransportFactory.heuristic(null))
                .isEqualTo(TransportType.OPENAI_CHAT_COMPLETIONS);
    }

    @Test
    @DisplayName("resolve — explicit model.config.transport overrides heuristic")
    void resolve_modelConfigTransport_wins() {
        ProviderRecord p = provider(Map.of());
        ModelRecord m = model("gpt-4o", Map.of("transport", "openai-responses"));

        TransportSpec spec = factory.resolve(p, m);

        assertThat(spec.type()).isEqualTo(TransportType.OPENAI_RESPONSES);
        assertThat(spec.source()).isEqualTo(TransportSpec.Source.MODEL_CONFIG);
    }

    @Test
    @DisplayName("resolve — provider transport-rules win over heuristic")
    void resolve_providerRules_winOverHeuristic() {
        Map<String, Object> config = Map.of(
                "transport-rules", List.of(
                        Map.of("prefix", "gpt-4o", "transport", "openai-responses")));
        ProviderRecord p = provider(config);
        ModelRecord m = model("gpt-4o-mini", Map.of());

        TransportSpec spec = factory.resolve(p, m);

        assertThat(spec.type()).isEqualTo(TransportType.OPENAI_RESPONSES);
        assertThat(spec.source()).isEqualTo(TransportSpec.Source.PROVIDER_RULE);
    }

    @Test
    @DisplayName("resolve — model.config beats provider rules")
    void resolve_modelConfig_beatsProviderRule() {
        Map<String, Object> providerConfig = Map.of(
                "transport-rules", List.of(
                        Map.of("prefix", "gpt-", "transport", "openai-chat-completions")));
        ProviderRecord p = provider(providerConfig);
        ModelRecord m = model("gpt-5.4", Map.of("transport", "openai-responses"));

        TransportSpec spec = factory.resolve(p, m);

        assertThat(spec.type()).isEqualTo(TransportType.OPENAI_RESPONSES);
        assertThat(spec.source()).isEqualTo(TransportSpec.Source.MODEL_CONFIG);
    }

    @Test
    @DisplayName("resolve — heuristic source when no overrides")
    void resolve_noOverrides_heuristic() {
        ProviderRecord p = provider(Map.of());
        ModelRecord m = model("gpt-5.4-mini", Map.of());

        TransportSpec spec = factory.resolve(p, m);

        assertThat(spec.type()).isEqualTo(TransportType.OPENAI_RESPONSES);
        assertThat(spec.source()).isEqualTo(TransportSpec.Source.HEURISTIC);
    }

    @Test
    @DisplayName("resolve — paths.openai-responses overrides default path")
    void resolve_pathsMap_overridesDefault() {
        Map<String, Object> config = Map.of(
                "paths", Map.of("openai-responses", "/responses"));
        ProviderRecord p = provider(config);
        ModelRecord m = model("gpt-5.4-mini", Map.of());

        TransportSpec spec = factory.resolve(p, m);

        assertThat(spec.path()).isEqualTo("/responses");
        assertThat(spec.url()).isEqualTo("https://api.example.com/responses");
    }

    @Test
    @DisplayName("resolve — legacy flat 'responses-path' key also honoured")
    void resolve_legacyFlatPath_works() {
        Map<String, Object> config = Map.of("responses-path", "/responses");
        ProviderRecord p = provider(config);
        ModelRecord m = model("gpt-5.4-mini", Map.of());

        TransportSpec spec = factory.resolve(p, m);

        assertThat(spec.path()).isEqualTo("/responses");
    }

    @Test
    @DisplayName("resolve — default Responses path is /v1/responses when nothing configured")
    void resolve_defaultPath_v1Responses() {
        ProviderRecord p = provider(Map.of());
        ModelRecord m = model("gpt-5.4-mini", Map.of());

        TransportSpec spec = factory.resolve(p, m);

        assertThat(spec.path()).isEqualTo("/v1/responses");
    }

    @Test
    @DisplayName("resolve — base URL normalised, /v1 suffix stripped to avoid /v1/v1")
    void resolve_baseUrlNormalised() {
        Map<String, Object> config = Map.of("paths", Map.of("openai-chat-completions", "/v1/chat/completions"));
        ProviderRecord p = new ProviderRecord(UUID.randomUUID(), "x", "custom",
                "https://api.example.com/v1/", "K", true, 0, config, Instant.now(), Instant.now());
        ModelRecord m = model("gpt-4o", Map.of());

        TransportSpec spec = factory.resolve(p, m);

        assertThat(spec.baseUrl()).isEqualTo("https://api.example.com");
        assertThat(spec.url()).isEqualTo("https://api.example.com/v1/chat/completions");
    }

    @Test
    @DisplayName("resolve — provider headers propagated; Authorization not stripped (transports filter reserved)")
    void resolve_headers_propagated() {
        Map<String, Object> config = Map.of(
                "headers", Map.of("Copilot-Integration-Id", "vscode-chat", "Editor-Version", "vscode/1.95.0"));
        ProviderRecord p = provider(config);
        ModelRecord m = model("gpt-4o", Map.of());

        TransportSpec spec = factory.resolve(p, m);

        assertThat(spec.extraHeaders())
                .containsEntry("Copilot-Integration-Id", "vscode-chat")
                .containsEntry("Editor-Version", "vscode/1.95.0");
    }

    @Test
    @DisplayName("resolve — invalid transport string in rule is ignored, falls through to heuristic")
    void resolve_invalidTransport_ignored() {
        Map<String, Object> config = Map.of(
                "transport-rules", List.of(
                        Map.of("prefix", "gpt-5", "transport", "bogus-format")));
        ProviderRecord p = provider(config);
        ModelRecord m = model("gpt-5.4-mini", Map.of());

        TransportSpec spec = factory.resolve(p, m);

        // Falls through to heuristic which also routes to Responses
        assertThat(spec.type()).isEqualTo(TransportType.OPENAI_RESPONSES);
        assertThat(spec.source()).isEqualTo(TransportSpec.Source.HEURISTIC);
    }
}
