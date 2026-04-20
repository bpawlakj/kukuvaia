package ai.kukuvaia.mcp;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row of {@code kukuvaia.mcp_connections} — a single remote MCP server
 * the engine consumes.
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
        Instant updatedAt
) {

    public McpConnection {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (url == null || url.isBlank()) throw new IllegalArgumentException("url required");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        sseEndpoint = sseEndpoint == null || sseEndpoint.isBlank() ? "/sse" : sseEndpoint;
    }
}
