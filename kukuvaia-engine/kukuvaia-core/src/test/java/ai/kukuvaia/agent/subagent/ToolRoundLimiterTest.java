package ai.kukuvaia.agent.subagent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ToolRoundLimiter — budget, grace refusals, cancellation")
class ToolRoundLimiterTest {

    private final AtomicInteger invocations = new AtomicInteger();
    private final ToolCallback tool = new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("t").description("t").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            invocations.incrementAndGet();
            return "ok";
        }
    };

    @Test
    @DisplayName("budget N: N real calls, then GRACE refusals, then the call is ended")
    void budgetThenGraceThenStop() {
        ToolCallback limited = new ToolRoundLimiter("s", 2).wrap(new ToolCallback[]{tool})[0];

        assertThat(limited.call("{}")).isEqualTo("ok");
        assertThat(limited.call("{}")).isEqualTo("ok");
        for (int i = 0; i < ToolRoundLimiter.GRACE_REFUSALS; i++) {
            assertThat(limited.call("{}")).isEqualTo(ToolRoundLimiter.EXHAUSTED_RESULT);
        }
        assertThatThrownBy(() -> limited.call("{}")).isInstanceOf(ToolRoundLimiter.ToolBudgetExceededException.class);
        assertThat(invocations).hasValue(2);
    }

    @Test
    @DisplayName("review #6: after close() (timeout) no tool call reaches the real tool")
    void closed_refusesEverything() {
        var limiter = new ToolRoundLimiter("s", 10);
        ToolCallback limited = limiter.wrap(new ToolCallback[]{tool})[0];
        limiter.close();

        assertThatThrownBy(() -> limited.call("{}")).isInstanceOf(ToolRoundLimiter.ToolBudgetExceededException.class);
        assertThat(invocations).hasValue(0);
    }

    @Test
    @DisplayName("an interrupted thread cannot start a tool call")
    void interrupted_refuses() {
        ToolCallback limited = new ToolRoundLimiter("s", 10).wrap(new ToolCallback[]{tool})[0];
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> limited.call("{}")).isInstanceOf(ToolRoundLimiter.ToolBudgetExceededException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(invocations).hasValue(0);
    }
}
