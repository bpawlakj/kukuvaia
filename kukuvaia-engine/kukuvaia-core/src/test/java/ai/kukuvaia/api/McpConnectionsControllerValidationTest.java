package ai.kukuvaia.api;

import ai.kukuvaia.mcp.McpConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("McpConnectionsController — D5 review: SSRF guard, no literal secrets, masked responses")
class McpConnectionsControllerValidationTest {

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
