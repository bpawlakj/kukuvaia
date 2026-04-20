package ai.kukuvaia.config;

import ai.kukuvaia.config.McpClientConfig.PerConnectionHeaderCustomizer;
import ai.kukuvaia.mcp.McpConnectionsCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("McpClientConfig — DB-backed per-connection header dispatch + env ref resolution")
class McpClientConfigTest {

    private static HttpRequest applied(PerConnectionHeaderCustomizer customizer, String endpoint) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint));
        customizer.customize(b, "GET", URI.create(endpoint), null, null);
        return b.GET().build();
    }

    @Test
    @DisplayName("request URI matched to connection — configured headers applied")
    void matchedConnection_appliesHeaders() {
        McpConnectionsCache cache = mock(McpConnectionsCache.class);
        when(cache.connectionNameForUri("https://alpha.example/mcp/sse")).thenReturn(Optional.of("alpha"));
        when(cache.headersFor("alpha")).thenReturn(Map.of("X-API-Key", "k1", "X-Tenant", "kukuvaia"));

        PerConnectionHeaderCustomizer customizer =
                new PerConnectionHeaderCustomizer(cache, varName -> null);

        HttpRequest req = applied(customizer, "https://alpha.example/mcp/sse");

        assertThat(req.headers().firstValue("X-API-Key")).contains("k1");
        assertThat(req.headers().firstValue("X-Tenant")).contains("kukuvaia");
    }

    @Test
    @DisplayName("env:VAR_NAME reference resolved via EnvResolver")
    void envReference_resolved() {
        McpConnectionsCache cache = mock(McpConnectionsCache.class);
        when(cache.connectionNameForUri("https://alpha.example/mcp/sse")).thenReturn(Optional.of("alpha"));
        when(cache.headersFor("alpha")).thenReturn(Map.of("X-API-Key", "env:SOME_SECRET"));

        PerConnectionHeaderCustomizer customizer = new PerConnectionHeaderCustomizer(
                cache,
                varName -> "SOME_SECRET".equals(varName) ? "resolved-value" : null);

        HttpRequest req = applied(customizer, "https://alpha.example/mcp/sse");

        assertThat(req.headers().firstValue("X-API-Key")).contains("resolved-value");
    }

    @Test
    @DisplayName("env:VAR_NAME pointing at unset env — header silently skipped")
    void envReference_missing_skipsHeader() {
        McpConnectionsCache cache = mock(McpConnectionsCache.class);
        when(cache.connectionNameForUri("https://alpha.example/mcp/sse")).thenReturn(Optional.of("alpha"));
        when(cache.headersFor("alpha")).thenReturn(Map.of("X-API-Key", "env:MISSING", "X-Tenant", "literal"));

        PerConnectionHeaderCustomizer customizer =
                new PerConnectionHeaderCustomizer(cache, varName -> null);

        HttpRequest req = applied(customizer, "https://alpha.example/mcp/sse");

        assertThat(req.headers().firstValue("X-API-Key")).isEmpty();
        assertThat(req.headers().firstValue("X-Tenant")).contains("literal");
    }

    @Test
    @DisplayName("unmatched URI — no headers injected")
    void unmatchedUri_noHeaders() {
        McpConnectionsCache cache = mock(McpConnectionsCache.class);
        when(cache.connectionNameForUri("https://other.example/")).thenReturn(Optional.empty());

        PerConnectionHeaderCustomizer customizer =
                new PerConnectionHeaderCustomizer(cache, varName -> null);

        HttpRequest req = applied(customizer, "https://other.example/");

        assertThat(req.headers().map()).isEmpty();
    }

    @Test
    @DisplayName("blank header value skipped")
    void blankValue_skipped() {
        McpConnectionsCache cache = mock(McpConnectionsCache.class);
        when(cache.connectionNameForUri("https://alpha.example/x")).thenReturn(Optional.of("alpha"));
        when(cache.headersFor("alpha")).thenReturn(Map.of("X-API-Key", "", "X-Tenant", "k"));

        PerConnectionHeaderCustomizer customizer =
                new PerConnectionHeaderCustomizer(cache, varName -> null);

        HttpRequest req = applied(customizer, "https://alpha.example/x");

        assertThat(req.headers().firstValue("X-API-Key")).isEmpty();
        assertThat(req.headers().firstValue("X-Tenant")).contains("k");
    }

    @Test
    @DisplayName("resolve() edge cases: null + prefix-only + literal")
    void resolve_edgeCases() {
        PerConnectionHeaderCustomizer customizer =
                new PerConnectionHeaderCustomizer(mock(McpConnectionsCache.class), varName -> "V");
        assertThat(customizer.resolve(null)).isNull();
        assertThat(customizer.resolve("literal")).isEqualTo("literal");
        assertThat(customizer.resolve("env:X")).isEqualTo("V");
    }
}
