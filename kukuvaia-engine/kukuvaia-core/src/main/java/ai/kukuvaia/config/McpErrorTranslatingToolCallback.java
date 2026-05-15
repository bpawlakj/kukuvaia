package ai.kukuvaia.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Wraps a {@link ToolCallback} (typically an MCP-backed one) so well-known transport-level
 * failures become structured, agent-readable error strings instead of raw stack traces.
 *
 * <p>Why this exists: Spring AI 1.1's {@code SyncMcpToolCallback} throws {@link RuntimeException}
 * with the upstream HTTP body embedded in the message. When the validation-engine restarts and
 * the {@code McpSyncClient} bean (built once at startup, no auto-reconnect) hits a stale SSE
 * session, the response is a 404 with body {@code "Session not found: <uuid>"}. Without this
 * decorator, the LLM tool result becomes a 4-line opening followed by a 100-line stack trace —
 * the agent then routinely halucynuje a wrong cause ("MediaType mismatch", "transient streaming
 * error", etc.) instead of telling the operator the actual problem.
 *
 * <p>The translated text is short, deterministic, and named: when the operator sees
 * "MCP transport stale — restart kukuvaia-app" they know exactly what to do.
 */
final class McpErrorTranslatingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(McpErrorTranslatingToolCallback.class);

    private final ToolCallback delegate;

    McpErrorTranslatingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        try {
            return delegate.call(toolInput);
        } catch (RuntimeException e) {
            return translateOrRethrow(e);
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            return delegate.call(toolInput, toolContext);
        } catch (RuntimeException e) {
            return translateOrRethrow(e);
        }
    }

    /**
     * Recognises the SSE "Session not found" pattern produced by Spring AI's
     * {@code WebMvcSseServerTransportProvider} when a peer restart invalidates the session
     * cached in this side's {@code McpSyncClient}. Anything else is a real error — re-throw so
     * Spring AI's ToolCallAdvisor still treats it as a failed call.
     */
    private String translateOrRethrow(RuntimeException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String toolName = delegate.getToolDefinition().name();
        if (msg.contains("Session not found")) {
            log.warn("MCP tool '{}' failed with stale SSE session — kukuvaia-app must be restarted "
                    + "to re-establish the transport. Original message head: {}",
                    toolName, msg.length() > 200 ? msg.substring(0, 200) : msg);
            return ("ERROR: MCP transport stale. The upstream MCP server (validation-engine) "
                    + "restarted since this kukuvaia-app process started, and Spring AI's "
                    + "McpSyncClient does not auto-reconnect. The kukuvaia-app process must be "
                    + "restarted to re-establish the SSE session — surface this verbatim to the "
                    + "operator and STOP. Do NOT fabricate a different cause (e.g. 'MediaType "
                    + "mismatch', 'transient streaming error', 'try a smaller outline') — that "
                    + "wastes the operator's time chasing a wrong fix.");
        }
        // Network-level connection failures — same flavour, similar fix on the operator side.
        if (msg.contains("Connection refused") || msg.contains("Connection reset")) {
            log.warn("MCP tool '{}' failed with transport-level connection error: {}",
                    toolName, msg);
            return ("ERROR: MCP transport connection failed. The MCP server (validation-engine) "
                    + "is unreachable from kukuvaia-app — likely not running, or the network path "
                    + "between the two processes is broken. Surface this verbatim to the operator "
                    + "and STOP.");
        }
        // Anything else — let it bubble through the normal error path.
        throw e;
    }
}
