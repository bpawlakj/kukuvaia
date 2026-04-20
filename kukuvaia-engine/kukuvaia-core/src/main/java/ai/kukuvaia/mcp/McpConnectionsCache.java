package ai.kukuvaia.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory snapshot of enabled {@link McpConnection}s — loaded at startup
 * and on demand via {@link #refresh()}.
 *
 * <p>Hot path (per outbound MCP request) reads {@link #headersFor(String)}
 * off this cache without touching the DB. Admin CRUD paths call
 * {@link #refresh()} after writes so subsequent requests see the new state.
 *
 * <p>Spring AI's {@code McpSyncClient} beans are still constructed once at
 * startup from the connection list — adding a new connection to the DB
 * therefore requires either an engine restart or the future hot-reload
 * worker. Header changes on existing connections take effect immediately
 * after {@code refresh()}.
 */
@Service
public class McpConnectionsCache {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionsCache.class);

    private final McpConnectionsRepository repository;
    private final CopyOnWriteArrayList<McpConnection> connections = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Map<String, String>> headersByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> nameByUrlPrefix = new ConcurrentHashMap<>();

    public McpConnectionsCache(McpConnectionsRepository repository) {
        this.repository = repository;
        // Startup refresh — if DB is unreachable, log + leave empty; engine
        // still boots so admin can fix the situation.
        try {
            refresh();
        } catch (Exception e) {
            log.warn("Initial MCP connections load failed: {} — cache is empty", e.getMessage());
        }
    }

    public synchronized void refresh() {
        List<McpConnection> fresh = repository.findAllEnabled();
        connections.clear();
        connections.addAll(fresh);
        headersByName.clear();
        nameByUrlPrefix.clear();
        for (McpConnection c : fresh) {
            headersByName.put(c.name(), c.headers());
            nameByUrlPrefix.put(normalise(c.url()), c.name());
        }
        log.info("MCP connections cache refreshed — {} enabled connections", fresh.size());
    }

    public List<McpConnection> all() {
        return List.copyOf(connections);
    }

    public Map<String, String> headersFor(String connectionName) {
        return headersByName.getOrDefault(connectionName, Map.of());
    }

    /**
     * Resolve which configured connection a given outbound URI belongs to,
     * by longest-matching URL prefix. Returns {@code null} when no
     * connection matches — callers may then skip header injection.
     */
    public Optional<String> connectionNameForUri(String uri) {
        if (uri == null) return Optional.empty();
        String best = null;
        int bestLen = -1;
        for (var entry : nameByUrlPrefix.entrySet()) {
            if (uri.startsWith(entry.getKey()) && entry.getKey().length() > bestLen) {
                best = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        return Optional.ofNullable(best);
    }

    private static String normalise(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
