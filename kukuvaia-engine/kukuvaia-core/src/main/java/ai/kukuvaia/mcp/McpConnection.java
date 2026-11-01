package ai.kukuvaia.mcp;

import java.time.Instant;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * One row of {@code kukuvaia.mcp_connections} — a single remote MCP server
 * the engine consumes.
 *
 * <p>{@code transport} is {@code sse} (legacy HTTP+SSE, path {@code sseEndpoint}) or
 * {@code streamable-http} (path {@code endpoint}, default {@code /mcp}).
 *
 * <p>{@code headers} is a plain string-keyed map. Values may use the
 * {@code env:<VAR_NAME>} convention to indicate env-var resolution at
 * request time; any other value is treated as literal. This keeps
 * rotating secrets a pure env-var operation while leaving the DB row
 * safe to expose through admin APIs.
 */
public record McpConnection(
        UUID id,
        String name,
        String url,
        String sseEndpoint,
        Map<String, String> headers,
        boolean enabled,
        String description,
        Instant createdAt,
        Instant updatedAt,
        String transport,
        String endpoint
) {

    public static final String SSE = "sse";
    public static final String STREAMABLE_HTTP = "streamable-http";

    public McpConnection {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        sseEndpoint = sseEndpoint == null || sseEndpoint.isBlank() ? "/sse" : sseEndpoint;
        transport = transport == null || transport.isBlank() ? SSE : transport;
        if (!SSE.equals(transport) && !STREAMABLE_HTTP.equals(transport)) {
            throw new IllegalArgumentException("transport must be 'sse' or 'streamable-http'");
        }
        endpoint = endpoint == null || endpoint.isBlank() ? "/mcp" : endpoint;
        if (!isOriginRelativePath(endpoint) || !isOriginRelativePath(sseEndpoint)) {
            throw new IllegalArgumentException("MCP endpoints must be origin-relative paths without an authority or fragment");
        }
    }

    /** SSE connection (pre-V25 shape). */
    public McpConnection(UUID id, String name, String url, String sseEndpoint, Map<String, String> headers,
                         boolean enabled, String description, Instant createdAt, Instant updatedAt) {
        this(id, name, url, sseEndpoint, headers, enabled, description, createdAt, updatedAt, SSE, null);
    }

    public boolean isStreamableHttp() {
        return STREAMABLE_HTTP.equals(transport);
    }

    /** A leading slash alone is insufficient: URI resolution treats //host/path as a new authority. */
    public static boolean isOriginRelativePath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.length() > 100) return false;
        try {
            URI uri = URI.create(path);
            return !uri.isAbsolute() && uri.getRawAuthority() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
