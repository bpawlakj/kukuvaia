package ai.kukuvaia.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import reactor.core.publisher.Hooks;

/**
 * Installs a global Reactor {@code onErrorDropped} hook so that a configured-but-currently-
 * unreachable MCP peer logs at WARN, not the noisy multi-line ERROR + stack trace reactor
 * fires by default. Spring AI's {@code HttpClientSseClientTransport} already emits a WARN
 * ("SSE stream observed an error") with the cause — the reactor ERROR is duplicate noise
 * because the publisher's error-handling chain has no terminal subscriber.
 *
 * <p><b>Why install from {@code main()}, not {@code @PostConstruct}:</b> Spring AI's MCP
 * autoconfigure opens the SSE transport on a {@code ForkJoinPool} worker as soon as the
 * {@code McpSseClientConnectionDetails} bean is asked for its connections. That can fire
 * BEFORE any {@code @Configuration}-bean lifecycle callback runs, so a {@code @PostConstruct}
 * hook is too late — the dropped error reaches reactor's default ERROR sink first. Calling
 * {@link #install()} from {@code KukuvaiaApplication.main} guarantees the hook is set
 * before any Spring AI publisher exists.
 *
 * <p><b>Why a global hook:</b> the SSE transport publisher is built deep inside Spring AI's
 * autoconfigure; we don't get a handle on the chain to attach {@code onErrorResume}. The
 * standard reactor escape hatch for "errors that leaked the pipeline with no subscriber"
 * is {@link Hooks#onErrorDropped(java.util.function.Consumer)}, which replaces the default
 * sink. We keep the behaviour minimal: known-shape MCP connect errors are recorded once
 * per process at WARN; any other dropped error is still logged at WARN with the trace so
 * a real bug isn't silently swallowed.
 *
 * <p><b>Trade-off:</b> this hook is global to the JVM and affects every reactor pipeline.
 * Errors that the default would have logged at ERROR are now WARN. We accept that —
 * {@code onErrorDropped} fires only for errors that have already escaped their pipeline,
 * which are by definition not recoverable at the call site; ERROR vs WARN is a log-noise
 * call, not a correctness call.
 */
public final class McpReactorErrorHandling {

    private static final Logger log = LoggerFactory.getLogger(McpReactorErrorHandling.class);

    private static final McpReactorErrorHandling INSTANCE = new McpReactorErrorHandling();

    /**
     * Rate-limit MCP-unreachable warnings to one per process. The SSE transport may retry
     * silently and re-emit the same connect failure many times; we only want the operator
     * to see it once, with the actionable hint that a restart is required.
     */
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    private McpReactorErrorHandling() {}

    /**
     * Install the global onErrorDropped hook + the Logback TurboFilter that strips the
     * 30-line stack trace from Spring AI's "SSE stream observed an error" WARN.
     *
     * <p><b>Timing note:</b> the reactor hook survives Spring Boot's logging-system reset,
     * but Logback turbo filters do NOT — Spring Boot resets the {@link LoggerContext} during
     * {@code ApplicationStartingEvent}, clearing any filter we installed in {@code main()}.
     * Callers should additionally invoke {@link #installSseNoiseFilter()} from an
     * {@code ApplicationEnvironmentPreparedEvent} listener (fired AFTER Logback is
     * re-initialised, BEFORE bean creation) so the filter is back in place by the time the
     * MCP SSE transport opens. Idempotent: re-installation just appends another filter,
     * which is harmless because the matched event is already DENY-ed by either copy.
     */
    public static void install() {
        Hooks.onErrorDropped(INSTANCE::handleDroppedError);
        installSseNoiseFilter();
        log.info("Reactor onErrorDropped hook + SSE TurboFilter installed (MCP-aware downgrade)");
    }

    /**
     * Replaces Spring AI's noisy "SSE stream observed an error" WARN + stack trace with
     * our own one-line WARN. The original event is denied at the filter level — Logback
     * never publishes it to appenders. Without this, the SSE transport logs a 30-line
     * trace every connect retry; ours summarises to a single line per first occurrence,
     * with subsequent retries suppressed by the same per-process rate-limit as the
     * reactor onErrorDropped path.
     */
    public static void installSseNoiseFilter() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            log.debug("SLF4J factory is not Logback ({}) — SSE noise filter not installed",
                    factory.getClass().getName());
            return;
        }
        context.addTurboFilter(new SseTransportNoiseFilter(INSTANCE));
    }

    /** Visible for tests. */
    static McpReactorErrorHandling instance() {
        return INSTANCE;
    }

    /** Visible for tests — clears the once-per-process state. */
    void reset() {
        warnedOnce.clear();
    }

    /** Visible for tests. */
    void handleDroppedError(Throwable t) {
        if (isMcpConnectShape(t)) {
            if (warnedOnce.add("mcp_unreachable")) {
                log.warn("MCP peer unreachable at startup — SSE transport will not auto-reconnect. "
                        + "Tools served by the unreachable peer will fail until the peer is up and "
                        + "this process is restarted. (Subsequent connect failures suppressed.)");
            }
            return;
        }
        // Anything else: still a sign of a leaked publisher, but downgrade ERROR → WARN.
        log.warn("Reactor dropped an error with no subscriber", t);
    }

    private static boolean isMcpConnectShape(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof ConnectException) return true;
            if (cur instanceof ClosedChannelException) return true;
        }
        return false;
    }

    /**
     * Logback TurboFilter that denies Spring AI's "SSE stream observed an error" WARN events
     * — we already log a concise WARN for the same condition, and the SSE transport's
     * version drags a 30-line stack trace into the console on every retry.
     *
     * <p>Matched by logger name + message prefix to avoid affecting any other event the
     * MCP transport logs. Returns {@link FilterReply#DENY} which short-circuits the event
     * before any appender runs; returns {@link FilterReply#NEUTRAL} for anything else so
     * the rest of the logging pipeline is untouched.
     */
    static final class SseTransportNoiseFilter extends TurboFilter {

        private static final String SSE_TRANSPORT_LOGGER =
                "io.modelcontextprotocol.client.transport.HttpClientSseClientTransport";
        private static final String NOISY_MESSAGE_PREFIX = "SSE stream observed an error";

        private final McpReactorErrorHandling owner;

        SseTransportNoiseFilter(McpReactorErrorHandling owner) {
            this.owner = owner;
            // Logback turbo filters must be started before they're consulted.
            start();
        }

        @Override
        public FilterReply decide(Marker marker, ch.qos.logback.classic.Logger logger,
                                  Level level, String format, Object[] params, Throwable t) {
            if (logger == null || !SSE_TRANSPORT_LOGGER.equals(logger.getName())) {
                return FilterReply.NEUTRAL;
            }
            if (format == null || !format.startsWith(NOISY_MESSAGE_PREFIX)) {
                return FilterReply.NEUTRAL;
            }
            // Substitute one-line WARN if we haven't already warned this process; the reactor
            // onErrorDropped path uses the same key so the two sources don't double-log.
            owner.handleDroppedError(new SseTransportObservedError(t));
            return FilterReply.DENY;
        }
    }

    /**
     * Synthetic throwable wrapper so the Logback-side notification reuses the same
     * MCP-connect-shape detection as the reactor onErrorDropped path. We carry the
     * original cause for shape detection but never log this object directly — the WARN
     * we emit is plain text.
     */
    static final class SseTransportObservedError extends RuntimeException {
        SseTransportObservedError(Throwable cause) {
            super("SSE transport observed an error", cause);
        }
    }
}
