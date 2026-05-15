package ai.kukuvaia.provider.transport.anthropic;

import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.transport.TransportSpec;
import ai.kukuvaia.provider.transport.TransportType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AnthropicMessagesTransport — POST /v1/messages with tool loop")
@ExtendWith(MockitoExtension.class)
class AnthropicMessagesTransportTest {

    @Mock private HttpClient httpClient;

    private AnthropicMessagesTransport transport;
    private ModelRecord model;

    @BeforeEach
    void setUp() {
        model = new ModelRecord(UUID.randomUUID(), UUID.randomUUID(),
                "claude-sonnet-4.6", "Claude Sonnet 4.6",
                List.of("text"), "standard", 4096, null, true, Map.of(), null, Instant.now(), Instant.now());

        TransportSpec spec = new TransportSpec(
                TransportType.ANTHROPIC_MESSAGES,
                "https://api.example.com",
                "/v1/messages",
                "tok_test",
                Map.of("anthropic-version", "2023-06-01"),
                TransportSpec.Source.HEURISTIC);

        transport = new AnthropicMessagesTransport(spec, model, httpClient, new ObjectMapper(),
                Duration.ofSeconds(30), "Authorization");
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int statusCode, String body) throws Exception {
        HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
        lenient().when(response.statusCode()).thenReturn(statusCode);
        lenient().when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private void scriptResponses(String... bodies) throws Exception {
        Deque<HttpResponse<String>> queue = new ArrayDeque<>();
        for (String body : bodies) {
            HttpResponse<String> r = org.mockito.Mockito.mock(HttpResponse.class);
            lenient().when(r.statusCode()).thenReturn(200);
            lenient().when(r.body()).thenReturn(body);
            queue.add(r);
        }
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> {
                    HttpResponse<String> next = queue.poll();
                    if (next == null) throw new IllegalStateException("No scripted response left");
                    return next;
                });
    }

    private static ToolCallback recordingCallback(String name, AtomicInteger counter, String result) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name).description("test " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String toolInput) { counter.incrementAndGet(); return result; }
        };
    }

    private static Prompt promptWithTool(String text, ToolCallback... callbacks) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(callbacks)).build();
        return new Prompt(List.of(new UserMessage(text)), options);
    }

    @Test
    @DisplayName("call — happy path — Bearer auth + custom headers, parsed ChatResponse")
    void call_happyPath() throws Exception {
        stubResponse(200, """
                {"id":"msg_1","content":[{"type":"text","text":"hi back"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":5,"output_tokens":2}}
                """);

        ChatResponse response = transport.call(new Prompt(List.of(new UserMessage("hi"))));

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(req.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest sent = req.getValue();
        assertThat(sent.uri().toString()).isEqualTo("https://api.example.com/v1/messages");
        assertThat(sent.headers().firstValue("Authorization")).contains("Bearer tok_test");
        assertThat(sent.headers().firstValue("anthropic-version")).contains("2023-06-01");
        assertThat(response.getResult().getOutput().getText()).isEqualTo("hi back");
    }

    @Test
    @DisplayName("call — x-api-key auth header switches to raw key (no Bearer prefix)")
    void call_xApiKeyHeader() throws Exception {
        TransportSpec spec = new TransportSpec(
                TransportType.ANTHROPIC_MESSAGES,
                "https://api.anthropic.com", "/v1/messages",
                "sk-ant-test", Map.of("anthropic-version", "2023-06-01"),
                TransportSpec.Source.HEURISTIC);
        transport = new AnthropicMessagesTransport(spec, model, httpClient, new ObjectMapper(),
                Duration.ofSeconds(30), "x-api-key");
        stubResponse(200, """
                {"id":"msg_1","content":[{"type":"text","text":"ok"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}
                """);

        transport.call(new Prompt(List.of(new UserMessage("hi"))));

        ArgumentCaptor<HttpRequest> req = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(req.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(req.getValue().headers().firstValue("x-api-key")).contains("sk-ant-test");
        assertThat(req.getValue().headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    @DisplayName("call — tool_use stop_reason → execute callbacks, send tool_result, get final text")
    void call_toolLoop() throws Exception {
        AtomicInteger toolCount = new AtomicInteger();
        ToolCallback weather = recordingCallback("get_weather", toolCount, "Warsaw: 5°C");
        scriptResponses(
                """
                {"id":"msg_1","content":[
                  {"type":"tool_use","id":"toolu_a","name":"get_weather","input":{"city":"Warsaw"}}
                ],"stop_reason":"tool_use","usage":{"input_tokens":10,"output_tokens":5}}
                """,
                """
                {"id":"msg_2","content":[{"type":"text","text":"Warsaw is 5°C."}],
                 "stop_reason":"end_turn","usage":{"input_tokens":20,"output_tokens":5}}
                """);

        ChatResponse response = transport.call(promptWithTool("weather in Warsaw?", weather));

        assertThat(toolCount).hasValue(1);
        assertThat(response.getResult().getOutput().getText()).isEqualTo("Warsaw is 5°C.");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("call — parallel tool_use blocks → all executed, batched into single user message")
    void call_parallelToolUses() throws Exception {
        AtomicInteger count = new AtomicInteger();
        ToolCallback weather = recordingCallback("get_weather", count, "ok");
        scriptResponses(
                """
                {"id":"msg_1","content":[
                  {"type":"tool_use","id":"toolu_a","name":"get_weather","input":{"city":"Warsaw"}},
                  {"type":"tool_use","id":"toolu_b","name":"get_weather","input":{"city":"Berlin"}}
                ],"stop_reason":"tool_use"}
                """,
                """
                {"id":"msg_2","content":[{"type":"text","text":"Done."}],
                 "stop_reason":"end_turn"}
                """);

        transport.call(promptWithTool("weather?", weather));

        assertThat(count).hasValue(2);
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("call — model asks for tool but no callbacks → throws")
    void call_modelAsksToolWithoutCallbacks_throws() throws Exception {
        stubResponse(200, """
                {"id":"msg_1","content":[
                  {"type":"tool_use","id":"toolu_x","name":"f","input":{}}
                ],"stop_reason":"tool_use"}
                """);

        assertThatThrownBy(() -> transport.call(new Prompt(List.of(new UserMessage("hi")))))
                .isInstanceOf(AnthropicMessagesTransport.AnthropicTransportException.class)
                .hasMessageContaining("no ToolCallbacks");
    }

    @Test
    @DisplayName("call — 400 from server — message includes status and body excerpt")
    void call_400_includesContext() throws Exception {
        stubResponse(400, "{\"type\":\"error\",\"error\":{\"message\":\"bad input\"}}");

        assertThatThrownBy(() -> transport.call(new Prompt(List.of(new UserMessage("hi")))))
                .isInstanceOf(AnthropicMessagesTransport.AnthropicTransportException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("bad input");
    }

    @Test
    @DisplayName("describe — includes transport type and URL")
    void describe() {
        assertThat(transport.describe())
                .isEqualTo("anthropic-messages@https://api.example.com/v1/messages");
    }
}
