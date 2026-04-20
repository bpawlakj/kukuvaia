package ai.kukuvaia.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Helper that emits {@link SpanEventBlock} events into the {@link SessionOutputSink}
 * at the same sites where Micrometer timers are captured.
 *
 * This is the decoupled emission path chosen in the P01+P01.1 implementation plan §2
 * (Option 2): the OTel javaagent keeps handling HTTP/JDBC auto-instrumentation while
 * kukuvaia-domain spans flow through this component straight into SSE. No {@code SpanProcessor}
 * registration, no javaagent extension — just plain Java in-process wiring.
 *
 * Usage:
 * <pre>
 *   var handle = spanEventEmitter.start(sessionId, parentHandle, "tool:grep", Map.of("input_preview", "..."));
 *   try { ... } finally {
 *       spanEventEmitter.end(handle, Map.of("kukuvaia.summary", "3 matches", "kukuvaia.status", "success"));
 *   }
 * </pre>
 *
 * <p>Delta throttling: {@link #delta(Handle, long)} drops emissions that arrive within 100 ms
 * of the prior delta on the same span.
 *
 * <p>Parent hierarchy is tracked per-session in a small thread-safe map keyed by span id.
 * No OTel dependency — this is a standalone lineage layer.
 */
@Component
public class SpanEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(SpanEventEmitter.class);
    private static final long DELTA_THROTTLE_MS = 100;

    private final SessionOutputSink outputSink;
    private final ConcurrentHashMap<String, Long> lastDeltaAt = new ConcurrentHashMap<>();

    public SpanEventEmitter(SessionOutputSink outputSink) {
        this.outputSink = outputSink;
    }

    /** Begin a span. Returns a handle to close or annotate it. */
    public Handle start(String sessionId, Handle parent, String name, Map<String, Object> attributes) {
        if (sessionId == null || !outputSink.isActive(sessionId)) {
            return Handle.noop();
        }
        String spanId = newSpanId();
        String parentSpanId = parent != null ? parent.spanId() : "";
        Map<String, Object> attrs = withSession(sessionId, attributes);
        outputSink.emit(sessionId, new SpanEventBlock(spanId, parentSpanId, name, "start", attrs, Instant.now()));
        return new Handle(sessionId, spanId, name);
    }

    /** Mark a span as ended with a status and optional summary. */
    public void end(Handle handle, Map<String, Object> attributes) {
        if (handle == null || handle.isNoop()) return;
        Map<String, Object> attrs = withSession(handle.sessionId(), attributes);
        outputSink.emit(handle.sessionId(), new SpanEventBlock(
                handle.spanId(), "", handle.name(), "end", attrs, Instant.now()));
        lastDeltaAt.remove(handle.spanId());
    }

    /** Emit an incremental token delta for a running span. Throttled to ≤ 1 per 100 ms. */
    public void delta(Handle handle, long tokensDelta) {
        if (handle == null || handle.isNoop() || tokensDelta <= 0) return;
        long now = System.currentTimeMillis();
        Long prev = lastDeltaAt.get(handle.spanId());
        if (prev != null && now - prev < DELTA_THROTTLE_MS) {
            return;
        }
        lastDeltaAt.put(handle.spanId(), now);
        outputSink.emit(handle.sessionId(), new SpanEventBlock(
                handle.spanId(), "", handle.name(), "delta",
                Map.of("kukuvaia.tokens.delta", tokensDelta), Instant.now()));
    }

    private Map<String, Object> withSession(String sessionId, Map<String, Object> attributes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kukuvaia.session.id", sessionId);
        if (attributes != null) result.putAll(attributes);
        return result;
    }

    private static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Opaque handle to a started span. Pass to {@link #end(Handle, Map)} or {@link #delta(Handle, long)}.
     * {@link #noop()} represents a span that was never actually emitted (no active sink for the session).
     */
    public record Handle(String sessionId, String spanId, String name) {
        private static final Handle NOOP = new Handle(null, null, null);

        public static Handle noop() {
            return NOOP;
        }

        public boolean isNoop() {
            return this == NOOP;
        }
    }
}
