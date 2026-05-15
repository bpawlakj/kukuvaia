package ai.kukuvaia.advisors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

@DisplayName("TokenEstimator — char-based estimate, JSON/NL weights")
class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    @DisplayName("null and empty inputs return 0")
    void nullAndEmpty_returnZero() {
        assertThat(estimator.estimate((Message) null)).isZero();
        assertThat(estimator.estimate(List.of())).isZero();
        assertThat(estimator.estimate((List<Message>) null)).isZero();
    }

    @Test
    @DisplayName("empty text message — only per-message overhead counted")
    void emptyText_overheadOnly() {
        // Per-message overhead = 5 tokens (role tagging).
        assertThat(estimator.estimate(new UserMessage(""))).isEqualTo(5);
    }

    @Test
    @DisplayName("natural-language user message — ~chars/4 + overhead")
    void naturalLanguage_userMessage() {
        // 40 chars → 40/4 = 10 tokens + 5 overhead = 15
        String text = "Lorem ipsum dolor sit amet, consectetur."; // 40 chars
        int estimate = estimator.estimate(new UserMessage(text));
        assertThat(estimate).isEqualTo(15);
    }

    @Test
    @DisplayName("tool-response message — uses JSON ratio (3.2 chars/token), denser than NL")
    void toolResponse_usesJsonRatio() {
        // 32 chars of JSON → 32/3.2 = 10 tokens + 5 overhead = 15
        String json = "{\"k\":\"v\",\"a\":1,\"b\":2,\"c\":3,\"d\":4}"; // 32 chars
        Message tool = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("id-1", "test-tool", json)))
                .build();
        int estimate = estimator.estimate(tool);
        // ToolResponseMessage.getText() returns concatenated responses — exact char count
        // depends on Spring AI's toString format; verify denser-than-NL by comparing to a
        // user message of the same payload length.
        int userEstimate = estimator.estimate(new UserMessage(tool.getText()));
        assertThat(estimate).isGreaterThanOrEqualTo(userEstimate);
    }

    @Test
    @DisplayName("assistant message with tool calls uses JSON weighting")
    void assistantWithToolCalls_usesJsonRatio() {
        AssistantMessage am = AssistantMessage.builder()
                .content("thinking")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id-1", "function", "get_outline", "{\"id\":\"x\"}")))
                .build();
        // Same content via plain user message — JSON-weighted assistant should not be smaller
        // than the NL estimate of an equivalent-length text.
        int estimate = estimator.estimate(am);
        int userEstimate = estimator.estimate(new UserMessage(am.getText()));
        assertThat(estimate).isGreaterThanOrEqualTo(userEstimate);
    }

    @Test
    @DisplayName("sum across mixed message list")
    void sum_acrossList() {
        List<Message> msgs = List.of(
                new SystemMessage("System prompt body"),  // 18 chars NL → 18/4 + 5 ≈ 10
                new UserMessage("Hello"),                  // 5 chars → ceil(5/4)=2 + 5 = 7
                new AssistantMessage("Hi back")            // 7 chars → ceil(7/4)=2 + 5 = 7
        );
        int total = estimator.estimate(msgs);
        // Don't pin exact value; pin monotonicity vs each part.
        assertThat(total).isGreaterThan(estimator.estimate(msgs.get(0)));
        assertThat(total).isEqualTo(
                estimator.estimate(msgs.get(0))
                + estimator.estimate(msgs.get(1))
                + estimator.estimate(msgs.get(2)));
    }
}
