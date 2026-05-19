package ai.kukuvaia.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("McpReactorErrorHandling — onErrorDropped downgrade for MCP-unreachable shapes")
class McpReactorErrorHandlingTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger classLogger;
    private McpReactorErrorHandling handler;

    @BeforeEach
    void setUp() {
        handler = McpReactorErrorHandling.instance();
        handler.reset();    // clear once-per-process state between tests
        classLogger = (Logger) LoggerFactory.getLogger(McpReactorErrorHandling.class);
        appender = new ListAppender<>();
        appender.start();
        classLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        classLogger.detachAppender(appender);
        handler.reset();
    }

    @Test
    @DisplayName("MCP connect failure shape — logged once at WARN, not ERROR")
    void mcpConnectShape_warnsOnceAtWarn() {
        Throwable err = new CompletionException(new ConnectException("connection refused"));

        handler.handleDroppedError(err);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("MCP peer unreachable")
                .contains("restart");
    }

    @Test
    @DisplayName("Same shape fires twice — second log suppressed (rate-limited per process)")
    void mcpConnectShape_secondCallSuppressed() {
        handler.handleDroppedError(new CompletionException(new ConnectException("x")));
        handler.handleDroppedError(new CompletionException(new ConnectException("y")));

        // Only the first WARN survives — rate limiter eats the second.
        assertThat(appender.list).hasSize(1);
    }

    @Test
    @DisplayName("ClosedChannelException nested in cause chain — same downgrade")
    void closedChannelException_alsoDowngraded() {
        Throwable err = new RuntimeException("outer",
                new CompletionException(new ClosedChannelException()));

        handler.handleDroppedError(err);

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("MCP peer unreachable");
    }

    @Test
    @DisplayName("Unknown leaked error — WARN with trace, NOT silently swallowed")
    void unknownError_loggedAsWarnWithTrace() {
        Throwable err = new IllegalStateException("something unexpected");

        handler.handleDroppedError(err);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("Reactor dropped an error with no subscriber");
        assertThat(appender.list.get(0).getThrowableProxy()).isNotNull();
    }

    @Test
    @DisplayName("TurboFilter denies the noisy 'SSE stream observed an error' from the transport logger")
    void sseFilter_deniesNoisyTransportWarning() {
        var filter = new McpReactorErrorHandling.SseTransportNoiseFilter(handler);
        ch.qos.logback.classic.Logger transportLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        "io.modelcontextprotocol.client.transport.HttpClientSseClientTransport");

        ch.qos.logback.core.spi.FilterReply reply = filter.decide(
                null, transportLogger, Level.WARN,
                "SSE stream observed an error",
                new Object[0],
                new java.util.concurrent.CompletionException(new ConnectException("refused")));

        assertThat(reply).isEqualTo(ch.qos.logback.core.spi.FilterReply.DENY);
        // And our own concise WARN was emitted in place.
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("MCP peer unreachable");
    }

    @Test
    @DisplayName("TurboFilter is NEUTRAL for unrelated loggers and messages")
    void sseFilter_neutralForUnrelatedEvents() {
        var filter = new McpReactorErrorHandling.SseTransportNoiseFilter(handler);
        ch.qos.logback.classic.Logger otherLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("some.unrelated.Logger");

        var replyA = filter.decide(null, otherLogger, Level.WARN, "anything", new Object[0], null);
        assertThat(replyA).isEqualTo(ch.qos.logback.core.spi.FilterReply.NEUTRAL);

        ch.qos.logback.classic.Logger transportLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                        "io.modelcontextprotocol.client.transport.HttpClientSseClientTransport");
        var replyB = filter.decide(null, transportLogger, Level.WARN,
                "Some other message from the same logger", new Object[0], null);
        assertThat(replyB).isEqualTo(ch.qos.logback.core.spi.FilterReply.NEUTRAL);
    }
}
