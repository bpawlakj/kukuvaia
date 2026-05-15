package ai.kukuvaia.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("McpConnectionsCache — snapshot + URL prefix lookup")
class McpConnectionsCacheTest {

    private static McpConnection conn(String name, String url, Map<String, String> headers) {
        return new McpConnection(
                UUID.randomUUID(), name, url, "/sse", headers, true, null,
                Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("refresh loads enabled connections from repo; headersFor returns them")
    void refresh_and_lookup() {
        McpConnectionsRepository repo = mock(McpConnectionsRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(
                conn("alpha", "https://alpha.example/mcp", Map.of("X-API-Key", "env:A")),
                conn("beta",  "https://beta.example/mcp",  Map.of("Authorization", "literal"))
        ));

        McpConnectionsCache cache = new McpConnectionsCache(repo);

        assertThat(cache.all()).hasSize(2);
        assertThat(cache.headersFor("alpha")).containsEntry("X-API-Key", "env:A");
        assertThat(cache.headersFor("beta")).containsEntry("Authorization", "literal");
        assertThat(cache.headersFor("unknown")).isEmpty();
    }

    @Test
    @DisplayName("connectionNameForUri picks longest-prefix match")
    void longestPrefixWins() {
        McpConnectionsRepository repo = mock(McpConnectionsRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(
                conn("root", "https://host.example",       Map.of()),
                conn("mcp",  "https://host.example/mcp",   Map.of())
        ));
        McpConnectionsCache cache = new McpConnectionsCache(repo);

        assertThat(cache.connectionNameForUri("https://host.example/mcp/sse"))
                .contains("mcp");
        assertThat(cache.connectionNameForUri("https://host.example/other/path"))
                .contains("root");
    }

    @Test
    @DisplayName("no match returns empty Optional")
    void unmatched() {
        McpConnectionsRepository repo = mock(McpConnectionsRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(
                conn("alpha", "https://alpha.example/mcp", Map.of())
        ));
        McpConnectionsCache cache = new McpConnectionsCache(repo);

        assertThat(cache.connectionNameForUri("https://different.example/")).isEmpty();
        assertThat(cache.connectionNameForUri(null)).isEmpty();
    }

    @Test
    @DisplayName("trailing slash on url does not break matching")
    void trailingSlashNormalised() {
        McpConnectionsRepository repo = mock(McpConnectionsRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(
                conn("alpha", "https://alpha.example/mcp/", Map.of("X-API-Key", "k"))
        ));
        McpConnectionsCache cache = new McpConnectionsCache(repo);

        assertThat(cache.connectionNameForUri("https://alpha.example/mcp/sse")).contains("alpha");
    }

    @Test
    @DisplayName("repository failure at startup leaves cache empty — engine still boots")
    void startupFailure_doesNotThrow() {
        McpConnectionsRepository repo = mock(McpConnectionsRepository.class);
        when(repo.findAllEnabled()).thenThrow(new RuntimeException("db down"));

        McpConnectionsCache cache = new McpConnectionsCache(repo);

        assertThat(cache.all()).isEmpty();
        assertThat(cache.connectionNameForUri("https://any/")).isEmpty();
    }

    @Test
    @DisplayName("DbMcpSseClientConnectionDetails maps cached rows to Spring AI SseParameters")
    void connectionDetails_mapping() {
        McpConnectionsRepository repo = mock(McpConnectionsRepository.class);
        when(repo.findAllEnabled()).thenReturn(List.of(
                conn("alpha", "https://alpha.example/mcp", Map.of())
        ));
        McpConnectionsCache cache = new McpConnectionsCache(repo);
        var details = new DbMcpSseClientConnectionDetails(cache);

        var connections = details.getConnections();
        assertThat(connections).containsOnlyKeys("alpha");
        assertThat(connections.get("alpha").url()).isEqualTo("https://alpha.example/mcp");
        assertThat(connections.get("alpha").sseEndpoint()).isEqualTo("/sse");
    }

    /**
     * TG2.C — validation-engine MCP connection contract. The persona implementations in
     * TG2.A / TG2.B address an MCP connection registered under the name "validation-engine".
     * These tests pin the contract: when an operator inserts the row from
     * scripts/register-validation-engine-mcp.sql, the cache exposes the connection under
     * that name, the URL prefix lookup routes to it, and the Authorization header carries
     * the env-var reference (so the token never sits in a DB row).
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("TG2.C — validation-engine MCP connection contract")
    class ValidationEngineConnection {

        private static final String CONNECTION_NAME = "validation-engine";
        private static final String DEFAULT_URL = "http://localhost:8081/mcp";

        @Test
        @DisplayName("validation-engine row is exposed under name 'validation-engine' with env-ref Authorization header")
        void validationEngineRow_isResolvedByName() {
            McpConnectionsRepository repo = mock(McpConnectionsRepository.class);
            when(repo.findAllEnabled()).thenReturn(List.of(
                    conn(CONNECTION_NAME, DEFAULT_URL,
                            Map.of("Authorization", "env:VALIDATION_ENGINE_TOKEN"))
            ));

            McpConnectionsCache cache = new McpConnectionsCache(repo);

            assertThat(cache.all())
                    .as("the validation-engine row must round-trip through the cache exactly once")
                    .extracting(McpConnection::name)
                    .containsExactly(CONNECTION_NAME);
            assertThat(cache.headersFor(CONNECTION_NAME))
                    .as("token must be an env reference, never a literal — see scripts/register-validation-engine-mcp.sql")
                    .containsEntry("Authorization", "env:VALIDATION_ENGINE_TOKEN");
        }

        @Test
        @DisplayName("outbound URLs at the validation-engine MCP root resolve to the validation-engine connection")
        void validationEngineUrl_routesToConnection() {
            McpConnectionsRepository repo = mock(McpConnectionsRepository.class);
            when(repo.findAllEnabled()).thenReturn(List.of(
                    conn(CONNECTION_NAME, DEFAULT_URL, Map.of())
            ));

            McpConnectionsCache cache = new McpConnectionsCache(repo);

            // Spring AI appends /sse, message paths, etc. — longest-prefix match must still
            // resolve them all back to "validation-engine" so the header customizer attaches
            // the token on every outbound MCP request.
            assertThat(cache.connectionNameForUri(DEFAULT_URL + "/sse")).contains(CONNECTION_NAME);
            assertThat(cache.connectionNameForUri(DEFAULT_URL + "/message/abc")).contains(CONNECTION_NAME);
            assertThat(cache.connectionNameForUri("http://elsewhere/sse"))
                    .as("requests outside the validation-engine prefix must NOT pick up its token")
                    .isEmpty();
        }

        @Test
        @DisplayName("validation-engine row with enabled=false is omitted — graceful degradation when ops disables it")
        void validationEngineDisabled_doesNotAppear() {
            // findAllEnabled() filters disabled rows at the repository level (see
            // McpConnectionsRepository SQL), so the cache never sees them. Simulate that
            // by returning an empty list — the engine still boots, the validator persona
            // simply has no MCP backend available until the connection is re-enabled.
            McpConnectionsRepository repo = mock(McpConnectionsRepository.class);
            when(repo.findAllEnabled()).thenReturn(List.of());

            McpConnectionsCache cache = new McpConnectionsCache(repo);

            assertThat(cache.all()).isEmpty();
            assertThat(cache.connectionNameForUri(DEFAULT_URL + "/sse")).isEmpty();
            // Engine still boots: cache construction did not throw, headersFor returns empty map.
            assertThat(cache.headersFor(CONNECTION_NAME)).isEmpty();
        }
    }
}
