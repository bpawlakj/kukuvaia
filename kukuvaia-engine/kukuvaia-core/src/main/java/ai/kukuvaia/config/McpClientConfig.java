package ai.kukuvaia.config;

import ai.kukuvaia.mcp.DbMcpSseClientConnectionDetails;
import ai.kukuvaia.mcp.McpConnectionsCache;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.common.autoconfigure.McpSseClientConnectionDetails;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Spring AI's {@code SseHttpClientTransportAutoConfiguration} unconditionally registers a
     * default {@code mcpSseClientConnectionDetails} bean (no {@code @ConditionalOnMissingBean}),
     * so we cannot just shadow it by name or type — both beans co-exist. {@code @Primary} makes
     * autowiring deterministic: every consumer that asks for {@link McpSseClientConnectionDetails}
     * gets ours (DB-backed), and the autoconfig's default sits unused. Our bean has a distinct
     * name so the bean factory accepts both registrations.
     */
    @Bean
    @Primary
    McpSseClientConnectionDetails dbMcpSseClientConnectionDetails(McpConnectionsCache cache) {
        return new DbMcpSseClientConnectionDetails(cache);
    }

    @Bean
    McpSyncHttpClientRequestCustomizer mcpRequestHeaderCustomizer(McpConnectionsCache cache) {
        return new PerConnectionHeaderCustomizer(cache, System::getenv);
    }

    /**
     * Replacement for the {@code mcpSyncClients} bean that {@link EmbabelBeanOverride} removes
     * to break the parent/subclass factory-method ambiguity in
     * {@code com.embabel.agent.autoconfigure.platform.QuiteMcpClientAutoConfiguration}
     * (Embabel 0.3.x adds a sibling @Bean with a divergent 4th parameter signature, and Spring
     * cannot pick between the inherited and the override).
     *
     * <p>Minimal viable wiring: take every {@link NamedClientMcpTransport} the SSE autoconfig
     * built from our DB-backed connection details, wrap it in an {@link McpSyncClient} with the
     * configured client info + request timeout, initialise eagerly when configured to. We
     * intentionally skip Spring AI's optional sampling / elicitation / logging handler hooks
     * — kukuvaia does not register any of those, so the parent autoconfig was wiring no-ops anyway.
     */
    @Bean
    @Primary
    public List<McpSyncClient> kukuvaiaMcpSyncClients(McpClientCommonProperties commonProperties,
                                                     ObjectProvider<List<NamedClientMcpTransport>> namedTransports) {
        List<McpSyncClient> clients = new ArrayList<>();
        List<NamedClientMcpTransport> transports = namedTransports.stream().flatMap(List::stream).toList();
        for (NamedClientMcpTransport t : transports) {
            String connectedName = "%s - %s".formatted(commonProperties.getName(), t.name());
            McpSyncClient client = McpClient.sync(t.transport())
                    .clientInfo(new McpSchema.Implementation(connectedName, commonProperties.getVersion()))
                    .requestTimeout(commonProperties.getRequestTimeout())
                    .build();
            if (commonProperties.isInitialized()) {
                try {
                    client.initialize();
                    log.info("MCP sync client initialized for connection '{}' (timeout={})",
                            t.name(), commonProperties.getRequestTimeout());
                } catch (Exception e) {
                    log.warn("MCP sync client '{}' initialize() failed: {}", t.name(), e.getMessage());
                }
            }
            clients.add(client);
        }
        if (clients.isEmpty()) {
            log.info("MCP sync clients: no transports — discovered 0 remote MCP servers");
        } else {
            log.info("MCP sync clients: {} client(s) built — {}",
                    clients.size(),
                    transports.stream().map(NamedClientMcpTransport::name).toList());
        }
        return clients;
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
