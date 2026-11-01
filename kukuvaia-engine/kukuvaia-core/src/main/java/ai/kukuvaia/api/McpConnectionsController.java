package ai.kukuvaia.api;

import ai.kukuvaia.mcp.McpConnection;
import ai.kukuvaia.mcp.McpConnectionsCache;
import ai.kukuvaia.mcp.McpConnectionsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin CRUD for remote MCP connections — backs the admin dashboard UI.
 *
 * <p>Write operations refresh {@link McpConnectionsCache} so header
 * changes take effect on subsequent outbound requests without a restart.
 * Adding a brand new connection still requires a restart because Spring
 * AI wires {@code McpSyncClient} beans once per application context;
 * that limitation is documented and noted in the P22 plan.
 *
 * <p>Secrets in header values should use the {@code env:VAR_NAME}
 * convention — the row stays safe to expose through this API and the
 * actual secret lives in the deployment environment.
 */
@RestController
@RequestMapping("/api/admin/mcp/connections")
public class McpConnectionsController {

    private static final Logger log = LoggerFactory.getLogger(McpConnectionsController.class);

    private final McpConnectionsRepository repository;
    private final McpConnectionsCache cache;

    public McpConnectionsController(McpConnectionsRepository repository, McpConnectionsCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    private static final java.util.regex.Pattern CREDENTIAL_HEADER =
            java.util.regex.Pattern.compile("(?i).*(authorization|token|secret|key|cookie|password|credential).*");

    @GetMapping
    public List<McpConnection> list() {
        return repository.findAll().stream().map(McpConnectionsController::masked).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<McpConnection> get(@PathVariable UUID id) {
        return repository.findById(id)
                .map(McpConnectionsController::masked)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<McpConnection> create(@RequestBody CreateRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (req.url() == null || req.url().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (!validTransport(req.transport(), req.endpoint()) || !validUrl(req.url())
                || !validHeaders(req.headers()) || !validPath(req.sseEndpoint())) {
            return ResponseEntity.badRequest().build();
        }
        if (repository.findByName(req.name()).isPresent()) {
            return ResponseEntity.status(409).build();
        }
        UUID id = repository.create(
                req.name(),
                req.url(),
                req.sseEndpoint() != null ? req.sseEndpoint() : "/sse",
                req.headers() != null ? req.headers() : Map.of(),
                req.enabled() == null || req.enabled(),
                req.description(),
                req.transport(),
                req.endpoint());
        cache.refresh();
        return repository.findById(id)
                .map(c -> ResponseEntity.status(201).body(masked(c)))
                .orElse(ResponseEntity.internalServerError().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<McpConnection> update(@PathVariable UUID id, @RequestBody UpdateRequest req) {
        if (!validTransport(req.transport(), req.endpoint()) || (req.url() != null && !validUrl(req.url()))
                || !validHeaders(req.headers()) || !validPath(req.sseEndpoint())) {
            return ResponseEntity.badRequest().build();
        }
        int affected = repository.update(id, req.url(), req.sseEndpoint(), req.headers(), req.enabled(),
                req.description(), req.transport(), req.endpoint());
        if (affected == 0) {
            return ResponseEntity.notFound().build();
        }
        cache.refresh();
        return repository.findById(id)
                .map(McpConnectionsController::masked)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.internalServerError().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        int affected = repository.delete(id);
        if (affected == 0) {
            return ResponseEntity.notFound().build();
        }
        cache.refresh();
        log.info("MCP connection {} deleted via admin API", id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        cache.refresh();
        return ResponseEntity.ok(Map.of("refreshed", true, "enabledCount", cache.all().size()));
    }

    /** Literal header values are never echoed back — only {@code env:} references are shown. */
    static McpConnection masked(McpConnection c) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        c.headers().forEach((k, v) -> headers.put(k, v != null && v.startsWith("env:") ? v : "***"));
        return new McpConnection(c.id(), c.name(), c.url(), c.sseEndpoint(), headers, c.enabled(), c.description(),
                c.createdAt(), c.updatedAt(), c.transport(), c.endpoint());
    }

    /** http(s) only, and never a link-local address (cloud metadata endpoints such as 169.254.169.254). */
    static boolean validUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
                return false;
            }
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(uri.getHost())) {
                if (address.isLinkLocalAddress() || address.isAnyLocalAddress()) return false;
            }
            return true;
        } catch (IllegalArgumentException | java.net.UnknownHostException e) {
            return false;
        }
    }

    /** Credential-like headers must reference the environment ({@code env:VAR}) — no secrets in the DB. */
    static boolean validHeaders(Map<String, String> headers) {
        if (headers == null) return true;
        return headers.entrySet().stream().allMatch(h -> h.getKey() != null
                && (!CREDENTIAL_HEADER.matcher(h.getKey()).matches() || (h.getValue() != null && h.getValue().startsWith("env:"))));
    }

    private static boolean validPath(String path) {
        return path == null || McpConnection.isOriginRelativePath(path);
    }

    /** {@code null} = keep / default; otherwise {@code sse | streamable-http}, endpoint a path. */
    private static boolean validTransport(String transport, String endpoint) {
        boolean transportOk = transport == null
                || McpConnection.SSE.equals(transport) || McpConnection.STREAMABLE_HTTP.equals(transport);
        boolean endpointOk = validPath(endpoint);
        return transportOk && endpointOk;
    }

    public record CreateRequest(
            String name,
            String url,
            String sseEndpoint,
            Map<String, String> headers,
            Boolean enabled,
            String description,
            String transport,
            String endpoint
    ) {}

    public record UpdateRequest(
            String url,
            String sseEndpoint,
            Map<String, String> headers,
            Boolean enabled,
            String description,
            String transport,
            String endpoint
    ) {}
}
