package ai.kukuvaia.agent.subagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enforces a specialist's {@code max_tool_rounds} for one sub-agent call.
 *
 * <p>All tool callbacks of a single call are wrapped with the same budget. Once the budget is
 * spent, further invocations do not reach the real tool: they return an "exhausted" tool result,
 * so the model can still produce a final answer with what it has (instead of the whole call
 * failing). This is one of the three mandatory sub-agent DoS controls (AGENTS.md), next to
 * {@code max_tokens} and the call timeout.
 */
public final class ToolRoundLimiter {

    static final String EXHAUSTED_RESULT =
            "Tool budget exhausted for this task. Do not call more tools — answer with what you have.";

    private static final Logger log = LoggerFactory.getLogger(ToolRoundLimiter.class);

    private final String specialist;
    private final int maxCalls;
    private final AtomicInteger calls = new AtomicInteger();

    public ToolRoundLimiter(String specialist, int maxCalls) {
        this.specialist = specialist;
        this.maxCalls = Math.max(0, maxCalls);
    }

    /** Wraps every callback so they share this call's budget. */
    public ToolCallback[] wrap(ToolCallback[] callbacks) {
        if (callbacks == null) return new ToolCallback[0];
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = new Limited(callbacks[i]);
        }
        return wrapped;
    }

    /** Number of tool invocations attempted so far (including refused ones). */
    public int attempted() {
        return calls.get();
    }

    private boolean tryConsume(String toolName) {
        int n = calls.incrementAndGet();
        if (n <= maxCalls) return true;
        log.warn("Sub-agent '{}' exceeded max_tool_rounds={} — refused call #{} to tool '{}'",
                specialist, maxCalls, n, toolName);
        return false;
    }

    private final class Limited implements ToolCallback {

        private final ToolCallback delegate;

        Limited(ToolCallback delegate) {
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
            return tryConsume(getToolDefinition().name()) ? delegate.call(toolInput) : EXHAUSTED_RESULT;
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return tryConsume(getToolDefinition().name()) ? delegate.call(toolInput, toolContext) : EXHAUSTED_RESULT;
        }
    }
}
