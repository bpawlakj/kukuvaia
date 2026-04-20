package ai.kukuvaia.output;

import java.time.Instant;
import java.util.Map;

/**
 * Streams OTel-style span lifecycle events over SSE to interactive clients.
 * Carries enough data for a live UI to render a span tree without inspecting traces in Jaeger.
 *
 * <ul>
 *   <li>{@code spanId} / {@code parentSpanId} — hierarchy. Empty {@code parentSpanId} marks a top-level span.</li>
 *   <li>{@code name} — {@code "<category>:<subject>"} convention. Categories: {@code role}, {@code tool}, {@code llm}, {@code memory}.</li>
 *   <li>{@code phase} — {@code "start"}, {@code "delta"}, or {@code "end"}.</li>
 *   <li>{@code attributes} — kukuvaia-prefixed keys (session.id, tokens.*, summary, status, error.*).</li>
 * </ul>
 *
 * Throttling for {@code delta} phase: at most one emission per span per 100 ms.
 * Cancellation: when the client disconnects or user aborts, in-flight spans close with {@code status="cancelled"}.
 */
public record SpanEventBlock(
        String spanId,
        String parentSpanId,
        String name,
        String phase,
        Map<String, Object> attributes,
        Instant timestamp
) implements OutputBlock {

    public SpanEventBlock {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
