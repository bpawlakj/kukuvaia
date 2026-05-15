package ai.kukuvaia.provider.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import ai.kukuvaia.provider.model.DiscoveredModel;

@DisplayName("ModelDiscoveryClient — /v1/models endpoint discovery")
@ExtendWith(MockitoExtension.class)
class ModelDiscoveryClientTest {

    @Mock private HttpClient mockHttpClient;
    private ModelDiscoveryClient client;

    @BeforeEach
    void setUp() {
        client = new ModelDiscoveryClient(new ObjectMapper());
        client.setHttpClient(mockHttpClient);
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int statusCode, String body) throws Exception {
        HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
        lenient().when(response.statusCode()).thenReturn(statusCode);
        lenient().when(response.body()).thenReturn(body);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    @Test
    @DisplayName("discover — valid OpenAI response — returns models")
    void discover_validResponse_returnsModels() throws Exception {
        String body = """
                {"data": [
                    {"id": "haiku", "owned_by": "anthropic"},
                    {"id": "sonnet", "owned_by": "anthropic"},
                    {"id": "opus", "owned_by": "anthropic"}
                ]}
                """;
        stubResponse(200, body);

        List<DiscoveredModel> result = client.discover("https://llm.example.com", "key123");

        assertThat(result).hasSize(3);
        assertThat(result.get(0).modelId()).isEqualTo("haiku");
        assertThat(result.get(0).ownedBy()).isEqualTo("anthropic");
    }

    @Test
    @DisplayName("discover — empty data array — returns empty list")
    void discover_emptyData_returnsEmptyList() throws Exception {
        stubResponse(200, "{\"data\": []}");

        List<DiscoveredModel> result = client.discover("https://llm.example.com", "key123");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("discover — 401 response — throws with auth message")
    void discover_401_throwsAuthError() throws Exception {
        stubResponse(401, "Unauthorized");

        assertThatThrownBy(() -> client.discover("https://llm.example.com", "bad-key"))
                .isInstanceOf(ModelDiscoveryClient.ModelDiscoveryException.class)
                .hasMessageContaining("Authentication failed");
    }

    @Test
    @DisplayName("discover — 404 response — throws endpoint not found")
    void discover_404_throwsEndpointNotFound() throws Exception {
        stubResponse(404, "Not Found");

        assertThatThrownBy(() -> client.discover("https://llm.example.com", "key"))
                .isInstanceOf(ModelDiscoveryClient.ModelDiscoveryException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("discover — connection error — throws with clear message")
    void discover_connectionError_throws() throws Exception {
        when(mockHttpClient.<HttpResponse<String>>send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        assertThatThrownBy(() -> client.discover("https://llm.example.com", "key"))
                .isInstanceOf(ModelDiscoveryClient.ModelDiscoveryException.class)
                .hasMessageContaining("Connection refused");
    }

    @Test
    @DisplayName("discover — malformed JSON — throws parse error")
    void discover_malformedJson_throwsParseError() throws Exception {
        stubResponse(200, "not json");

        assertThatThrownBy(() -> client.discover("https://llm.example.com", "key"))
                .isInstanceOf(ModelDiscoveryClient.ModelDiscoveryException.class)
                .hasMessageContaining("parse");
    }

    @Test
    @DisplayName("discover — missing data field — throws clear error")
    void discover_missingData_throwsClearError() throws Exception {
        stubResponse(200, "{\"models\": []}");

        assertThatThrownBy(() -> client.discover("https://llm.example.com", "key"))
                .isInstanceOf(ModelDiscoveryClient.ModelDiscoveryException.class)
                .hasMessageContaining("data");
    }

    @Test
    @DisplayName("discover — duplicate model IDs — deduplicated")
    void discover_duplicateIds_deduplicated() throws Exception {
        String body = """
                {"data": [
                    {"id": "haiku", "owned_by": "anthropic"},
                    {"id": "haiku", "owned_by": "anthropic"}
                ]}
                """;
        stubResponse(200, body);

        List<DiscoveredModel> result = client.discover("https://llm.example.com", "key");

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("discover — URL with trailing slash — handled correctly")
    void discover_trailingSlash_handled() throws Exception {
        String body = "{\"data\": [{\"id\": \"test\"}]}";
        stubResponse(200, body);

        List<DiscoveredModel> result = client.discover("https://llm.example.com/", "key");

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("discover — URL with /v1 suffix — no double /v1/v1")
    void discover_v1Suffix_noDouble() throws Exception {
        String body = "{\"data\": [{\"id\": \"test\"}]}";
        stubResponse(200, body);

        List<DiscoveredModel> result = client.discover("https://llm.example.com/v1", "key");

        assertThat(result).hasSize(1);
    }

    /* ============================================================
     * Per-provider config: models-path override + extra headers.
     * Drives the Copilot path where the endpoint is /models (no /v1)
     * and Copilot-Integration-Id is required.
     * ============================================================ */

    @Test
    @DisplayName("discover — config.models-path override — hits the configured URL")
    void discover_modelsPathOverride_usesConfiguredUrl() throws Exception {
        stubResponse(200, "{\"data\": [{\"id\": \"gpt-4o\"}]}");
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);

        Map<String, Object> config = Map.of("models-path", "/models");
        List<DiscoveredModel> result = client.discover(
                "https://api.githubcopilot.com", "tok_x", config);

        assertThat(result).hasSize(1);
        verify(mockHttpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requestCaptor.getValue().uri().toString())
                .isEqualTo("https://api.githubcopilot.com/models");
    }

    @Test
    @DisplayName("discover — default models-path — still hits /v1/models")
    void discover_noConfig_usesDefaultPath() throws Exception {
        stubResponse(200, "{\"data\": [{\"id\": \"sonnet\"}]}");
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);

        client.discover("https://llm.example.com", "key", Map.of());

        verify(mockHttpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requestCaptor.getValue().uri().toString())
                .isEqualTo("https://llm.example.com/v1/models");
    }

    @Test
    @DisplayName("discover — config.headers — applied to outbound request")
    void discover_customHeaders_appliedToRequest() throws Exception {
        stubResponse(200, "{\"data\": []}");
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);

        Map<String, Object> config = Map.of(
                "models-path", "/models",
                "headers", Map.of(
                        "Copilot-Integration-Id", "kukuvaia",
                        "Editor-Version", "kukuvaia/0.1"));
        client.discover("https://api.githubcopilot.com", "tok_x", config);

        verify(mockHttpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest sent = requestCaptor.getValue();
        assertThat(sent.headers().firstValue("Copilot-Integration-Id")).contains("kukuvaia");
        assertThat(sent.headers().firstValue("Editor-Version")).contains("kukuvaia/0.1");
    }

    @Test
    @DisplayName("discover — reserved headers in config — not overridden")
    void discover_reservedHeaders_notOverridden() throws Exception {
        stubResponse(200, "{\"data\": []}");
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);

        Map<String, Object> config = Map.of(
                "headers", Map.of("Authorization", "Bearer hacker-token"));
        client.discover("https://llm.example.com", "real-key", config);

        verify(mockHttpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
                .contains("Bearer real-key");
    }

    @Test
    @DisplayName("testModel — config.completions-path override — hits configured URL")
    void testModel_completionsPathOverride_usesConfiguredUrl() throws Exception {
        stubResponse(200, """
                {"choices": [{"message": {"content": "Hello"}}]}
                """);
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);

        Map<String, Object> providerConfig = Map.of("completions-path", "/chat/completions");
        client.testModel("https://api.githubcopilot.com", "tok_x", "gpt-4o", providerConfig, Map.of());

        verify(mockHttpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requestCaptor.getValue().uri().toString())
                .isEqualTo("https://api.githubcopilot.com/chat/completions");
    }
}
