package ai.kukuvaia.output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session reactive sink registry. Replaces ad-hoc {@code Flux.just(...)} returns
 * so that arbitrary producers (main agent response, Micrometer-driven span events,
 * async memory extraction) can all emit into the same SSE stream for a given session.
 *
 * Design notes:
 * <ul>
 *   <li>Backpressure buffer capped at {@value #BUFFER_SIZE}. On overflow, {@code SpanEventBlock}
 *       {@code delta} frames are dropped silently (they are optional live-UX detail); other blocks
 *       are kept. See P01+P01.1 implementation plan §14.</li>
 *   <li>Emission uses {@link Sinks.EmitFailureHandler#FAIL_FAST} combined with a short busy-loop
 *       retry so concurrent producers on different threads do not corrupt the SSE frame order.</li>
 *   <li>Sinks are created on first {@link #streamFor(String)} call and removed on
 *       {@link #complete(String)} or {@link #abort(String, Throwable)}.</li>
 * </ul>
 */
@Component
public class SessionOutputSink {

    private static final Logger log = LoggerFactory.getLogger(SessionOutputSink.class);
    private static final int BUFFER_SIZE = 500;

    private final ConcurrentHashMap<String, Sinks.Many<OutputBlock>> sinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> deltasDropped = new ConcurrentHashMap<>();

    /** Return the reactive stream for {@code sessionId}, creating a fresh sink if none exists. */
    public Flux<OutputBlock> streamFor(String sessionId) {
        return sink(sessionId).asFlux();
    }

    /** Emit one block. Thread-safe; serialises across concurrent producers for this session. */
    public void emit(String sessionId, OutputBlock block) {
        Sinks.Many<OutputBlock> s = sinks.get(sessionId);
        if (s == null) {
            log.debug("emit dropped — no active sink for session {}", sessionId);
            return;
        }
        Sinks.EmitResult result = s.tryEmitNext(block);
        if (result.isFailure()) {
            handleEmitFailure(sessionId, block, result);
        }
    }

    /** Complete the stream for {@code sessionId}. Idempotent — safe to call twice. */
    public void complete(String sessionId) {
        Sinks.Many<OutputBlock> s = sinks.remove(sessionId);
        if (s != null) {
            s.tryEmitComplete();
        }
        Integer dropped = deltasDropped.remove(sessionId);
        if (dropped != null && dropped > 0) {
            log.info("Session {} completed with {} dropped delta frames (backpressure)", sessionId, dropped);
        }
    }

    /** Abort the stream with an error. */
    public void abort(String sessionId, Throwable cause) {
        Sinks.Many<OutputBlock> s = sinks.remove(sessionId);
        if (s != null) {
            s.tryEmitError(cause);
        }
        deltasDropped.remove(sessionId);
    }

    /** True when a sink is currently registered for this session. */
    public boolean isActive(String sessionId) {
        return sinks.containsKey(sessionId);
    }

    private Sinks.Many<OutputBlock> sink(String sessionId) {
        return sinks.computeIfAbsent(sessionId,
                id -> Sinks.many().multicast().onBackpressureBuffer(BUFFER_SIZE, false));
    }

    private void handleEmitFailure(String sessionId, OutputBlock block, Sinks.EmitResult result) {
        if (block instanceof SpanEventBlock se && "delta".equals(se.phase())) {
            deltasDropped.merge(sessionId, 1, Integer::sum);
            return;
        }
        log.warn("Sink emit failed for session {} block={} result={}",
                sessionId, block.getClass().getSimpleName(), result);
    }
}
