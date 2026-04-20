package ai.kukuvaia.config;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-shot startup log that reports which tools each MCP server exposed.
 *
 * <p>When {@code spring-ai-starter-mcp-client} auto-wires connections from
 * {@code spring.ai.mcp.client.sse.connections.*}, Spring creates one
 * {@link McpSyncClient} per connection name. This listener iterates the
 * beans after {@code ApplicationReadyEvent} and prints the name + tool
 * count for each so ops can confirm at a glance that the remote server
 * is reachable and its surface matches expectations.
 *
 * <p>If no MCP clients are registered (no connections configured, MCP
 * disabled, or sl-content unreachable), this listener logs a single
 * INFO line and returns — never a warning, because absence of MCP
 * clients is a valid configuration (e.g. running engine standalone for
 * development).
 *
 * <p>Defensive: per-client {@code listTools()} errors are caught so one
 * broken server does not hide the others.
 */
@Component
public class McpToolDiscoveryLogger {

    private static final Logger log = LoggerFactory.getLogger(McpToolDiscoveryLogger.class);

    private final List<McpSyncClient> mcpClients;

    public McpToolDiscoveryLogger(List<McpSyncClient> mcpClients) {
        this.mcpClients = mcpClients;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logDiscoveredTools() {
        if (mcpClients.isEmpty()) {
            log.info("MCP: no remote MCP clients configured");
            return;
        }
        for (McpSyncClient client : mcpClients) {
            String clientName;
            try {
                clientName = client.getClientInfo() != null ? client.getClientInfo().name() : "unnamed";
            } catch (Exception e) {
                clientName = "unknown";
            }
            try {
                var tools = client.listTools();
                int count = tools.tools() != null ? tools.tools().size() : 0;
                log.info("MCP: client '{}' connected — {} tools discovered", clientName, count);
                if (log.isDebugEnabled() && tools.tools() != null) {
                    tools.tools().forEach(t ->
                            log.debug("MCP:   - {} — {}", t.name(), t.description()));
                }
            } catch (Exception e) {
                log.warn("MCP: client '{}' listTools failed ({}); "
                        + "tool calls will error at runtime. Verify X-API-Key and server reachability.",
                        clientName, e.getMessage());
            }
        }
    }
}
