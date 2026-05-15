package ai.kukuvaia.provider.transport;

import java.util.Map;
import ai.kukuvaia.provider.secret.SecretResolver;

/**
 * Resolved description of how a single (provider, model) pair should be reached on the
 * wire. Produced by {@link ChatTransportFactory}, consumed by the matching
 * {@link ChatTransport} implementation.
 *
 * @param type        wire dialect
 * @param baseUrl     provider base URL, normalised (no trailing slash, no implicit /v1)
 * @param path        endpoint path for this transport (already non-blank, leading slash)
 * @param apiKey      resolved bearer secret — already passed through {@code SecretResolver}
 * @param extraHeaders provider-defined headers to attach to every request
 * @param source      origin of the routing decision — for log/observability only
 */
public record TransportSpec(
        TransportType type,
        String baseUrl,
        String path,
        String apiKey,
        Map<String, String> extraHeaders,
        Source source
) {

    public TransportSpec {
        if (type == null) throw new IllegalArgumentException("type required");
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl required");
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path required");
        if (apiKey == null) throw new IllegalArgumentException("apiKey required (may be empty for local providers)");
        extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
        if (source == null) source = Source.HEURISTIC;
    }

    /** Full URL = baseUrl + path. */
    public String url() {
        return baseUrl + path;
    }

    /** How was the {@link TransportType} chosen? Surfaced in logs/metrics. */
    public enum Source {
        /** Model-level override via {@code model.config.transport}. */
        MODEL_CONFIG,
        /** Provider-level rule via {@code provider.config.transport-rules}. */
        PROVIDER_RULE,
        /** Built-in heuristic on model id (e.g. "gpt-5*" → Responses). */
        HEURISTIC
    }
}
