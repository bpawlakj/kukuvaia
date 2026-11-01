package ai.kukuvaia.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.McpSseClientConnectionDetails;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feeds Spring AI's MCP autoconfigure from the database instead of YAML.
 *
 * <p>Spring Boot's ConnectionDetails pattern: when a bean of type
 * {@link McpSseClientConnectionDetails} exists, the autoconfigure skips
 * the properties-backed default and uses this one. {@code getConnections}
 * is called once during startup to build the {@code McpSyncClient} beans,
 * so this implementation simply reads the enabled-connections snapshot
 * off {@link McpConnectionsCache}.
 *
 * <p>Adding / disabling a connection after startup does <em>not</em>
 * retro-wire the Spring context — Spring AI's autoconfig does the wiring
 * once. Hot reload is a future concern; MVP requires a restart (or a
 * context refresh) to pick up new connections. Header changes on
 * existing connections are picked up immediately via the customizer path.
 *
 * <p>Spring AI 2.0 deprecates the HTTP+SSE MCP transport (and with it
 * {@code McpSseClientProperties.SseParameters}) because the MCP specification replaced it
 * with Streamable HTTP; there is no non-deprecated SSE equivalent. Remote MCP servers that
 * only speak SSE still need this, so the warning is suppressed deliberately. Migration path:
 * expose {@code McpStreamableHttpClientProperties} connections once every remote server
 * supports Streamable HTTP.
 */
@SuppressWarnings("removal")
public class DbMcpSseClientConnectionDetails implements McpSseClientConnectionDetails {

    private static final Logger log = LoggerFactory.getLogger(DbMcpSseClientConnectionDetails.class);

    private final McpConnectionsCache cache;

    public DbMcpSseClientConnectionDetails(McpConnectionsCache cache) {
        this.cache = cache;
    }

    @Override
    public Map<String, org.springframework.ai.mcp.client.common.autoconfigure.properties.McpSseClientProperties.SseParameters> getConnections() {
        Map<String, org.springframework.ai.mcp.client.common.autoconfigure.properties.McpSseClientProperties.SseParameters> out = new LinkedHashMap<>();
        for (McpConnection c : cache.all()) {
            if (c.isStreamableHttp()) continue; // built by McpClientConfig#dbStreamableHttpTransports
            out.put(c.name(), new org.springframework.ai.mcp.client.common.autoconfigure.properties.McpSseClientProperties.SseParameters(c.url(), c.sseEndpoint()));
        }
        if (out.isEmpty()) {
            log.info("MCP connection details: no enabled connections — Spring AI will register no remote tools");
        } else {
            log.info("MCP connection details: {} connections resolved from DB — {}", out.size(), out.keySet());
        }
        return out;
    }
}
