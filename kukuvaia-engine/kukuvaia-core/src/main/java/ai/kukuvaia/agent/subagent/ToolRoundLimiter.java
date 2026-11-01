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
 *
 * <p>Two hard stops end the call instead of answering: a model that keeps asking for tools after
 * {@link #GRACE_REFUSALS} refusals (it ignores the budget), and any tool call after the sub-agent
 * call was cancelled ({@link #close()} on timeout, or the thread was interrupted) — so an abandoned
 * call cannot keep reaching tools in the background.
 */
public final class ToolRoundLimiter {

    static final String EXHAUSTED_RESULT =
            "Tool budget exhausted for this task. Do not call more tools — answer with what you have.";

    /** Refused calls tolerated after the budget is spent before the call is ended. */
    static final int GRACE_REFUSALS = 3;

    private static final Logger log = LoggerFactory.getLogger(ToolRoundLimiter.class);

    private final String specialist;
    private final int maxCalls;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean closed;

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

    /** Refuse every further tool call of this sub-agent call (it timed out or was cancelled). */
    public void close() {
        closed = true;
    }

    /** Number of tool invocations attempted so far (including refused ones). */
    public int attempted() {
        return calls.get();
    }

    private boolean tryConsume(String toolName) {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new ToolBudgetExceededException("Sub-agent '%s' was cancelled — tool '%s' not executed"
                    .formatted(specialist, toolName));
        }
        int n = calls.incrementAndGet();
        if (n <= maxCalls) return true;
        int refused = n - maxCalls;
        if (refused > GRACE_REFUSALS) {
            throw new ToolBudgetExceededException(("Sub-agent '%s' kept calling tools after max_tool_rounds=%d "
                    + "was exhausted — call ended").formatted(specialist, maxCalls));
        }
        log.warn("Sub-agent '{}' exceeded max_tool_rounds={} — refused call #{} to tool '{}'",
                specialist, maxCalls, n, toolName);
        return false;
    }

    /** Ends the sub-agent call: thrown through Spring AI's tool loop (not a ToolExecutionException). */
    public static final class ToolBudgetExceededException extends IllegalStateException {
        public ToolBudgetExceededException(String message) {
            super(message);
        }
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
