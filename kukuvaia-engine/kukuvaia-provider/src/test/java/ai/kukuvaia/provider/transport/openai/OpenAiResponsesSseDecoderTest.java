package ai.kukuvaia.provider.transport.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OpenAiResponsesSseDecoder — line-by-line SSE parsing")
class OpenAiResponsesSseDecoderTest {

    private OpenAiResponsesSseDecoder decoder;

    @BeforeEach
    void setUp() {
        decoder = new OpenAiResponsesSseDecoder(new OpenAiResponsesMapper(new ObjectMapper()), "gpt-5.4-mini");
    }

    /** Feed every line including blank separators; return only the chunks the decoder emitted. */
    private List<ChatResponse> feedAll(String... lines) {
        List<ChatResponse> out = new ArrayList<>();
        for (String line : lines) {
            decoder.feed(line).ifPresent(out::add);
        }
        decoder.complete().ifPresent(out::add);
        return out;
    }

    @Test
    @DisplayName("output_text.delta events emit ChatResponse with just the delta as content")
    void textDeltas_emitChunkPerDelta() {
        List<ChatResponse> emitted = feedAll(
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}",
                "",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\", world!\"}",
                "");

        assertThat(emitted).hasSize(2);
        assertThat(emitted.get(0).getResult().getOutput().getText()).isEqualTo("Hello");
        assertThat(emitted.get(1).getResult().getOutput().getText()).isEqualTo(", world!");
    }

    @Test
    @DisplayName("response.completed emits terminal frame with usage + reasoning + finish reason")
    void completed_emitsTerminalFrameWithMetadata() {
        List<ChatResponse> emitted = feedAll(
                "event: response.completed",
                "data: {\"type\":\"response.completed\",\"response\":{"
                        + "\"id\":\"resp_xyz\","
                        + "\"output\":[{\"type\":\"reasoning\",\"summary\":[{\"text\":\"thinking\"}]},"
                        + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Final\"}]}],"
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"total_tokens\":12}"
                        + "}}",
                "");

        assertThat(emitted).hasSize(1);
        ChatResponse terminal = emitted.get(0);
        assertThat(terminal.getMetadata().getId()).isEqualTo("resp_xyz");
        Object reasoning = terminal.getResult().getMetadata().get("reasoning");
        assertThat(reasoning).isEqualTo("thinking");
        // Terminal frame intentionally carries empty text — text already streamed via deltas.
        assertThat(terminal.getResult().getOutput().getText()).isEmpty();
    }

    @Test
    @DisplayName("response.failed → ResponsesTransportException with API message")
    void failure_throws() {
        decoder.feed("event: response.failed");
        decoder.feed("data: {\"type\":\"response.failed\",\"error\":{\"message\":\"server boom\"}}");

        assertThatThrownBy(() -> decoder.feed(""))
                .isInstanceOf(OpenAiResponsesTransport.ResponsesTransportException.class)
                .hasMessageContaining("server boom");
    }

    @Test
    @DisplayName("type inferred from data.type when event: line is missing (some proxies strip it)")
    void typeFromDataPayload_whenEventLineMissing() {
        List<ChatResponse> emitted = feedAll(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}",
                "");

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getResult().getOutput().getText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("malformed data is silently dropped (does not break the stream)")
    void malformedData_isDropped() {
        List<ChatResponse> emitted = feedAll(
                "event: response.output_text.delta",
                "data: not-json",
                "",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}",
                "");

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getResult().getOutput().getText()).isEqualTo("ok");
    }

    @Test
    @DisplayName("comments and keep-alive (':...' lines) are ignored")
    void commentLines_ignored() {
        List<ChatResponse> emitted = feedAll(
                ": ping",
                "event: response.output_text.delta",
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"x\"}",
                "");

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getResult().getOutput().getText()).isEqualTo("x");
    }

    @Test
    @DisplayName("response.completed emitted only once — even if stream re-emits the event")
    void completed_emittedAtMostOnce() {
        String completedPayload = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"x\"}]}]}}";

        Optional<ChatResponse> first = decoder.feed("event: response.completed");
        decoder.feed(completedPayload);
        Optional<ChatResponse> firstEmission = decoder.feed("");
        decoder.feed("event: response.completed");
        decoder.feed(completedPayload);
        Optional<ChatResponse> secondEmission = decoder.feed("");

        assertThat(first).isEmpty();
        assertThat(firstEmission).isPresent();
        assertThat(secondEmission).isEmpty();
    }
}
