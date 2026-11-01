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
        java.util.Set<String> ambiguous = new java.util.HashSet<>();
        for (McpConnection c : fresh) {
            headersByName.put(c.name(), c.headers());
            String key = normalise(c.url());
            String previous = nameByUrlPrefix.putIfAbsent(key, c.name());
            if (previous != null) ambiguous.add(key);
        }
        for (String key : ambiguous) {
            // Two connections on the same base URL cannot be told apart by URI — never guess whose
            // credentials to send. Their transports still get headers via per-connection customizers.
            log.error("MCP connections share the base URL {} — URI-based header lookup disabled for it", key);
            nameByUrlPrefix.put(key, AMBIGUOUS);
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
            if (matches(entry.getKey(), uri) && entry.getKey().length() > bestLen) {
                best = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        return AMBIGUOUS.equals(best) ? Optional.empty() : Optional.ofNullable(best);
    }

    public Optional<McpConnection> find(String connectionName) {
        return connections.stream().filter(c -> c.name().equals(connectionName)).findFirst();
    }

    /**
     * Same scheme, host and port, and the request path equals the base path or continues it at a
     * {@code /} boundary — so {@code https://api.x.com} never matches {@code https://api.x.com.evil.net}.
     */
    public static boolean matches(String baseUrl, String requestUri) {
        try {
            java.net.URI base = java.net.URI.create(normalise(baseUrl));
            java.net.URI req = java.net.URI.create(requestUri);
            if (base.getScheme() == null || req.getScheme() == null || base.getHost() == null || req.getHost() == null) {
                return false;
            }
            if (!base.getScheme().equalsIgnoreCase(req.getScheme()) || !base.getHost().equalsIgnoreCase(req.getHost())
                    || port(base) != port(req)) {
                return false;
            }
            String basePath = base.getRawPath() == null ? "" : base.getRawPath();
            String reqPath = req.getRawPath() == null ? "" : req.getRawPath();
            return basePath.isEmpty() || reqPath.equals(basePath) || reqPath.startsWith(basePath + "/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int port(java.net.URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static final String AMBIGUOUS = "\u0000ambiguous";

    private static String normalise(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
