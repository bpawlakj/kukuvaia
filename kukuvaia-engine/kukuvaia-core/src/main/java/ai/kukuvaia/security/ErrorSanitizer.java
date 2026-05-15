package ai.kukuvaia.security;

import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Global error handler that sanitizes all error responses.
 * Covers: Finding #11 (credentials in error messages).
 *
 * Strips: connection strings, stack traces, internal paths, credentials.
 * Logs full error server-side, returns safe message to client.
 */
@RestControllerAdvice
public class ErrorSanitizer {

    private static final Logger log = LoggerFactory.getLogger(ErrorSanitizer.class);

    // Patterns that indicate sensitive data in error messages
    private static final Pattern[] SENSITIVE_PATTERNS = {
            // JDBC / database connection strings
            Pattern.compile("jdbc:[a-z]+://[^\\s]+", Pattern.CASE_INSENSITIVE),
            // MongoDB connection strings
            Pattern.compile("mongodb(\\+srv)?://[^\\s]+", Pattern.CASE_INSENSITIVE),
            // Generic URLs with credentials
            Pattern.compile("://[^:]+:[^@]+@", Pattern.CASE_INSENSITIVE),
            // IP addresses with ports (internal services)
            Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}:\\d+"),
            // JWT tokens
            Pattern.compile("eyJ[A-Za-z0-9_-]+\\.eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
            // GitHub OAuth tokens
            Pattern.compile("gho_[A-Za-z0-9]+"),
            // Bearer tokens in headers
            Pattern.compile("Bearer\\s+[A-Za-z0-9._-]+", Pattern.CASE_INSENSITIVE),
            // File paths that reveal server structure
            Pattern.compile("/home/[a-z]+/[^\\s]+"),
            Pattern.compile("/opt/[a-z]+/[^\\s]+"),
            // Environment variable references
            Pattern.compile("\\$\\{[A-Z_]+}"),
            // Stack trace frames
            Pattern.compile("at\\s+[a-z]+(\\.[a-z]+)+\\(\\w+\\.java:\\d+\\)",
                    Pattern.CASE_INSENSITIVE),
    };

    /**
     * 404 for unmapped paths (Spring's {@code NoResourceFoundException}). These are routine —
     * SockJS heartbeat probes ({@code /ws/info}), favicon requests, scanner traffic — and must
     * not be logged at ERROR with full stack traces. Returns a clean 404 with no body so we
     * don't leak the requested path back to the caller.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResource(NoResourceFoundException ex) {
        log.debug("404 No static resource: {}", ex.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    /**
     * Client-disconnect during an in-flight SSE stream — Tomcat's {@code ClientAbortException}
     * wrapped by Spring's {@code AsyncRequestNotUsableException}. The peer (CLI / browser) closed
     * the socket; we cannot write a response back. Logging this at ERROR with a stack trace is
     * pure noise — the request is dead, there's nothing to fix. Without this handler the
     * generic {@link #handleAll} below tries to render a JSON error body, fails because the SSE
     * response Content-Type has no Map converter, and the resulting secondary exception
     * (HttpMessageNotWritableException) buries the original cause.
     *
     * <p>Returning {@code null} from a {@code @RestControllerAdvice} handler tells Spring to
     * treat the exception as resolved without writing a body — exactly right for a dead socket.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public ResponseEntity<Void> handleClientDisconnect(Exception ex) {
        log.debug("Client disconnected during async stream: {}", ex.getMessage());
        return null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleAll(Exception ex) {
        // Log full error server-side
        log.error("Unhandled exception", ex);
        // Return sanitized message to client
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", "internal_error",
                        "message", sanitize(ex.getMessage())
                ));
    }

    /**
     * Sanitize an error message by removing sensitive patterns.
     * Used both in HTTP responses and in tool result error messages sent to LLM.
     */
    public String sanitize(String message) {
        if (message == null) {
            return "An internal error occurred";
        }
        String result = message;
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            result = pattern.matcher(result).replaceAll("[REDACTED]");
        }
        // Truncate overly long messages (stack traces)
        if (result.length() > 500) {
            result = result.substring(0, 500) + "...[truncated]";
        }
        return result;
    }
}
