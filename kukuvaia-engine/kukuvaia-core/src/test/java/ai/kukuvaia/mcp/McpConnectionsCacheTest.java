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
}
