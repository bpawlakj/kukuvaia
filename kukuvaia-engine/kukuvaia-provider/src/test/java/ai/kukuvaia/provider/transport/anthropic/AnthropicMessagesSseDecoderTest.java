package ai.kukuvaia.provider.transport.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AnthropicMessagesSseDecoder — line-by-line Anthropic streaming")
class AnthropicMessagesSseDecoderTest {

    private AnthropicMessagesSseDecoder decoder;

    @BeforeEach
    void setUp() {
        decoder = new AnthropicMessagesSseDecoder(new AnthropicMessagesMapper(new ObjectMapper()),
                "claude-sonnet-4.6");
    }

    private List<ChatResponse> feedAll(String... lines) {
        List<ChatResponse> out = new ArrayList<>();
        for (String line : lines) decoder.feed(line).ifPresent(out::add);
        decoder.complete().ifPresent(out::add);
        return out;
    }

    @Test
    @DisplayName("content_block_delta with text_delta → ChatResponse with just the delta")
    void textDeltas_emitChunkPerDelta() {
        // Includes message_stop so complete() doesn't fire the fallback terminal frame.
        List<ChatResponse> emitted = feedAll(
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_x\",\"model\":\"claude-sonnet-4.6-20260301\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\", world!\"}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "");

        // Two text deltas + one terminal frame.
        assertThat(emitted).hasSize(3);
        assertThat(emitted.get(0).getResult().getOutput().getText()).isEqualTo("Hello");
        assertThat(emitted.get(1).getResult().getOutput().getText()).isEqualTo(", world!");
        assertThat(emitted.get(2).getResult().getOutput().getText()).isEmpty();
        assertThat(emitted.get(2).getMetadata().getId()).isEqualTo("msg_x");
    }

    @Test
    @DisplayName("message_stop emits terminal frame with id, model, usage, stop_reason")
    void messageStop_emitsTerminalWithUsage() {
        List<ChatResponse> emitted = feedAll(
                "event: message_start",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_z\",\"model\":\"claude-sonnet-4.6-20260301\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}",
                "",
                "event: message_delta",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":15}}",
                "",
                "event: message_stop",
                "data: {\"type\":\"message_stop\"}",
                "");

        assertThat(emitted).hasSize(1);
        ChatResponse terminal = emitted.get(0);
        assertThat(terminal.getMetadata().getId()).isEqualTo("msg_z");
        assertThat(terminal.getMetadata().getModel()).isEqualTo("claude-sonnet-4.6-20260301");
        DefaultUsage usage = (DefaultUsage) terminal.getMetadata().getUsage();
        assertThat(usage.getPromptTokens()).isEqualTo(10);
        assertThat(usage.getCompletionTokens()).isEqualTo(15);
        assertThat(terminal.getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");
        // Terminal frame carries empty text — content already streamed via deltas.
        assertThat(terminal.getResult().getOutput().getText()).isEmpty();
    }

    @Test
    @DisplayName("input_json_delta events are ignored (Phase 3 doesn't stream tool input)")
    void inputJsonDelta_isIgnored() {
        List<ChatResponse> emitted = feedAll(
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\"}}",
                "");

        assertThat(emitted).isEmpty();
    }

    @Test
    @DisplayName("error event → AnthropicTransportException with API message")
    void error_throws() {
        decoder.feed("event: error");
        decoder.feed("data: {\"type\":\"error\",\"error\":{\"message\":\"overloaded\"}}");

        assertThatThrownBy(() -> decoder.feed(""))
                .isInstanceOf(AnthropicMessagesTransport.AnthropicTransportException.class)
                .hasMessageContaining("overloaded");
    }

    @Test
    @DisplayName("complete() emits terminal even when message_stop event was missed")
    void complete_fallbackEmitsTerminal() {
        decoder.feed("event: message_start");
        decoder.feed("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_q\",\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}");
        decoder.feed("");

        var terminal = decoder.complete();

        assertThat(terminal).isPresent();
        assertThat(terminal.get().getMetadata().getId()).isEqualTo("msg_q");
    }

    @Test
    @DisplayName("malformed data is silently dropped")
    void malformed_dropped() {
        List<ChatResponse> emitted = feedAll(
                "event: content_block_delta",
                "data: not-json",
                "",
                "event: content_block_delta",
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}",
                "");

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getResult().getOutput().getText()).isEqualTo("ok");
    }
}
