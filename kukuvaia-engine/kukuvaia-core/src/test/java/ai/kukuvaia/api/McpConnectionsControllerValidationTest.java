package ai.kukuvaia.api;

import ai.kukuvaia.mcp.McpConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ai.kukuvaia.mcp.McpConnectionsRepository;
import ai.kukuvaia.mcp.McpConnectionsCache;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("McpConnectionsController — D5 review: SSRF guard, no literal secrets, masked responses")
class McpConnectionsControllerValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"//169.254.169.254/latest/meta-data", "//other.example/mcp",
            "///other.example/mcp", "/mcp\\other", "/mcp#fragment"})
    void endpointsCannotOverrideValidatedOrigin(String endpoint) {
        var repository = mock(McpConnectionsRepository.class);
        var cache = mock(McpConnectionsCache.class);
        var controller = new McpConnectionsController(repository, cache);

        assertThat(controller.create(new McpConnectionsController.CreateRequest("mcp", "http://127.0.0.1:8765",
                "/sse", Map.of(), true, null, McpConnection.STREAMABLE_HTTP, endpoint)).getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.update(UUID.randomUUID(), new McpConnectionsController.UpdateRequest(
                null, endpoint, null, null, null, McpConnection.SSE, "/mcp")).getStatusCode().value())
                .isEqualTo(400);
        verifyNoInteractions(repository, cache);

        assertThatThrownBy(() -> new McpConnection(UUID.randomUUID(), "mcp", "http://127.0.0.1:8765",
                "/sse", Map.of(), true, null, Instant.now(), Instant.now(), McpConnection.STREAMABLE_HTTP, endpoint))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new McpConnection(UUID.randomUUID(), "mcp", "http://127.0.0.1:8765",
                endpoint, Map.of(), true, null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("url: http(s) only, link-local / any-local addresses (cloud metadata) refused")
    void urls() {
        assertThat(McpConnectionsController.validUrl("https://10.20.30.40/base")).isTrue(); // literal IP: no DNS in tests
        assertThat(McpConnectionsController.validUrl("http://localhost:8765")).isTrue(); // local toolkit server
        assertThat(McpConnectionsController.validUrl("http://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(McpConnectionsController.validUrl("http://0.0.0.0:80")).isFalse();
        assertThat(McpConnectionsController.validUrl("file:///etc/passwd")).isFalse();
        assertThat(McpConnectionsController.validUrl("ftp://x.example.com")).isFalse();
    }

    @Test
    @DisplayName("credential-like headers must be env: references")
    void headers() {
        assertThat(McpConnectionsController.validHeaders(Map.of("Authorization", "env:TOOLKIT_TOKEN"))).isTrue();
        assertThat(McpConnectionsController.validHeaders(Map.of("X-Tenant", "future-education"))).isTrue();
        assertThat(McpConnectionsController.validHeaders(Map.of("Authorization", "Bearer abc123"))).isFalse();
        assertThat(McpConnectionsController.validHeaders(Map.of("X-Api-Key", "abc123"))).isFalse();
    }

    @Test
    @DisplayName("responses never echo literal header values")
    void masked() {
        var c = new McpConnection(UUID.randomUUID(), "n", "https://h", "/sse",
                Map.of("Authorization", "env:TOKEN", "X-Tenant", "literal-value"), true, null, Instant.now(), Instant.now());

        assertThat(McpConnectionsController.masked(c).headers())
                .containsEntry("Authorization", "env:TOKEN").containsEntry("X-Tenant", "***");
    }
}
