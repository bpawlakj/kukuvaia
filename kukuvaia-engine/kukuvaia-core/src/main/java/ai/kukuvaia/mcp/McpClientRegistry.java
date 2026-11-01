package ai.kukuvaia.mcp;

import io.modelcontextprotocol.client.McpSyncClient;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** The engine's MCP clients by {@code mcp_connections.name} (used by daemon {@code kind: mcp} jobs). */
public class McpClientRegistry {

    private final Map<String, McpSyncClient> clients;

    public McpClientRegistry(Map<String, McpSyncClient> clients) {
        this.clients = new TreeMap<>(clients);
    }

    public Optional<McpSyncClient> find(String connection) {
        return Optional.ofNullable(clients.get(connection));
    }

    public java.util.Set<String> names() {
        return clients.keySet();
    }
}
