package ai.kukuvaia.config;

import ai.kukuvaia.mcp.DbMcpSseClientConnectionDetails;
import ai.kukuvaia.mcp.McpConnectionsCache;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.McpSseClientConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

/**
 * Wires kukuvaia's DB-backed MCP connection registry into Spring AI's
 * autoconfigure and applies per-connection outbound headers.
 *
 * <p>Two beans:
 *
 * <ol>
 *   <li>{@link McpSseClientConnectionDetails} — overrides the
 *       properties-backed default so {@code spring.ai.mcp.client.sse.connections.*}
 *       YAML is irrelevant. Connections come from {@code kukuvaia.mcp_connections}
 *       via {@link McpConnectionsCache}.</li>
 *   <li>{@link McpSyncHttpClientRequestCustomizer} — injects the headers
 *       configured per connection. The cache's URL-prefix lookup picks
 *       the right connection for each outbound request URI.</li>
 * </ol>
 *
 * <p>Header values in the DB can use the {@code env:VAR_NAME} convention
 * — the customizer resolves them from {@link System#getenv(String)} at
 * request time. Any other value is used verbatim. Missing env vars are
 * skipped with a warning so a rotation slip doesn't spam errors per turn.
 */
@Configuration
public class McpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(McpClientConfig.class);
    private static final String ENV_PREFIX = "env:";

    @Bean
    McpSseClientConnectionDetails mcpSseClientConnectionDetails(McpConnectionsCache cache) {
        return new DbMcpSseClientConnectionDetails(cache);
    }

    @Bean
    McpSyncHttpClientRequestCustomizer mcpRequestHeaderCustomizer(McpConnectionsCache cache) {
        return new PerConnectionHeaderCustomizer(cache, System::getenv);
    }

    /**
     * Matches the outbound URI to a configured connection, reads its
     * header map from the cache, resolves env refs, and applies headers.
     * Shared across every MCP client this engine opens.
     */
    public static final class PerConnectionHeaderCustomizer implements McpSyncHttpClientRequestCustomizer {

        private final McpConnectionsCache cache;
        private final EnvResolver env;

        /** Functional interface so tests can inject a fake env. */
        public interface EnvResolver {
            String get(String name);
        }

        public PerConnectionHeaderCustomizer(McpConnectionsCache cache, EnvResolver env) {
            this.cache = cache;
            this.env = env;
        }

        @Override
        public void customize(HttpRequest.Builder builder,
                              String method,
                              URI endpoint,
                              @Nullable String body,
                              McpTransportContext context) {
            if (endpoint == null) return;
            var connectionName = cache.connectionNameForUri(endpoint.toString());
            if (connectionName.isEmpty()) return;
            Map<String, String> headers = cache.headersFor(connectionName.get());
            for (var h : headers.entrySet()) {
                String value = resolve(h.getValue());
                if (value == null || value.isBlank()) continue;
                builder.header(h.getKey(), value);
            }
        }

        String resolve(String value) {
            if (value == null) return null;
            if (!value.startsWith(ENV_PREFIX)) return value;
            String varName = value.substring(ENV_PREFIX.length());
            String resolved = env.get(varName);
            if (resolved == null || resolved.isBlank()) {
                log.warn("MCP header references env var '{}' which is unset — header will be skipped", varName);
                return null;
            }
            return resolved;
        }
    }
}
