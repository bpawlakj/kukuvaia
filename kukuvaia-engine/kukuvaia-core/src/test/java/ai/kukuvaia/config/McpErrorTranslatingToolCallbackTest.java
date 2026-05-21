package ai.kukuvaia.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

@DisplayName("McpErrorTranslatingToolCallback — translates known MCP transport errors so the LLM stops halucynowaniem")
class McpErrorTranslatingToolCallbackTest {

    private static ToolCallback wrap(ToolCallback delegate) {
        return new McpErrorTranslatingToolCallback(delegate);
    }

    private static ToolCallback delegateWithName(String name) {
        // Build the ToolDefinition mock fully BEFORE wiring it into the outer mock — a nested
        // when()/thenReturn would confuse Mockito's stubbing state machine.
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn(name);

        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(def);
        return delegate;
    }

    @Test
    @DisplayName("happy path — successful delegate.call passes through unchanged")
    void happyPath_passThrough() {
        ToolCallback delegate = delegateWithName("tool_alpha");
        when(delegate.call("input")).thenReturn("ok");

        assertThat(wrap(delegate).call("input")).isEqualTo("ok");
    }

    @Test
    @DisplayName("'Session not found' from a stale SSE peer becomes a structured 'restart kukuvaia' instruction")
    void sessionNotFound_translated() {
        ToolCallback delegate = delegateWithName("tool_alpha");
        when(delegate.call("input"))
                .thenThrow(new RuntimeException(
                        "Sending message failed with a non-OK HTTP code: 404 - "
                                + "{\"message\":\"Session not found: 5ec077c1-bfc4\"}"));

        String result = wrap(delegate).call("input");

        assertThat(result)
                .as("translated text MUST tell the agent to surface the cause to the operator and "
                        + "MUST NOT swallow the failure silently")
                .contains("MCP transport stale")
                .contains("kukuvaia-app process must be restarted")
                .contains("Do NOT fabricate")
                .doesNotContain("Session not found"); // raw upstream noise gone
    }

    @Test
    @DisplayName("Connection refused / reset becomes a structured 'peer unreachable' instruction")
    void connectionFailure_translated() {
        ToolCallback delegate = delegateWithName("tool_alpha");
        when(delegate.call("input"))
                .thenThrow(new RuntimeException("Connection refused: localhost/127.0.0.1:8081"));

        String result = wrap(delegate).call("input");

        assertThat(result).contains("MCP transport connection failed");
        assertThat(result).contains("Surface this verbatim");
    }

    @Test
    @DisplayName("any other exception is re-thrown — only known transport patterns are translated")
    void unknownException_rethrown() {
        ToolCallback delegate = delegateWithName("tool_alpha");
        when(delegate.call("input")).thenThrow(new RuntimeException("Internal checker bug"));

        // Real bugs must keep flowing through Spring AI's normal error path so they're surfaced
        // to the operator with their original cause — masking everything would just trade one
        // halucynacja for another.
        assertThatThrownBy(() -> wrap(delegate).call("input"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Internal checker bug");
    }
}
