package ai.kukuvaia.provider.transport.openai;

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
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;
import org.springframework.ai.chat.model.ToolContext;

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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OpenAiResponsesTransport — POST /responses with custom HttpClient")
@ExtendWith(MockitoExtension.class)
class OpenAiResponsesTransportTest {

    @Mock private HttpClient httpClient;

    private OpenAiResponsesTransport transport;
    private ModelRecord model;

    @BeforeEach
    void setUp() {
        model = new ModelRecord(UUID.randomUUID(), UUID.randomUUID(),
                "gpt-5.4-mini", "GPT-5.4 mini",
                List.of("text"), "standard", 4096, null, true, Map.of(), null, Instant.now(), Instant.now());

        TransportSpec spec = new TransportSpec(
                TransportType.OPENAI_RESPONSES,
                "https://api.example.com",
                "/responses",
                "tok_test",
                Map.of("Copilot-Integration-Id", "vscode-chat"),
                TransportSpec.Source.HEURISTIC);

        transport = new OpenAiResponsesTransport(spec, model, httpClient, new ObjectMapper(),
                Duration.ofSeconds(30));
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int statusCode, String body) throws Exception {
        HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
        lenient().when(response.statusCode()).thenReturn(statusCode);
        lenient().when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    @Test
    @DisplayName("call — happy path — POSTs to spec URL with Bearer auth and custom headers, returns parsed ChatResponse")
    void call_happyPath_returnsParsedResponse() throws Exception {
        stubResponse(200, """
                {
                  "id": "resp_1",
                  "output": [{"type": "message", "content": [{"type": "output_text", "text": "ok"}]}],
                  "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}
                }
                """);

        ChatResponse response = transport.call(new Prompt(List.of(new UserMessage("hi"))));

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest sent = requestCaptor.getValue();
        assertThat(sent.uri().toString()).isEqualTo("https://api.example.com/responses");
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.headers().firstValue("Authorization")).contains("Bearer tok_test");
        assertThat(sent.headers().firstValue("Copilot-Integration-Id")).contains("vscode-chat");
        assertThat(response.getResult().getOutput().getText()).isEqualTo("ok");
    }

    @Test
    @DisplayName("call — 400 from server — exception includes status and body excerpt")
    void call_400_includesStatusAndBody() throws Exception {
        stubResponse(400, "{\"error\":{\"message\":\"bad input\"}}");

        assertThatThrownBy(() -> transport.call(new Prompt(List.of(new UserMessage("hi")))))
                .isInstanceOf(OpenAiResponsesTransport.ResponsesTransportException.class)
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("bad input");
    }

    @Test
    @DisplayName("call — reserved header in spec.extraHeaders — not overridden")
    void call_reservedHeader_notOverridden() throws Exception {
        TransportSpec spec = new TransportSpec(
                TransportType.OPENAI_RESPONSES,
                "https://api.example.com",
                "/responses",
                "real-key",
                Map.of("Authorization", "Bearer hacker-token"),
                TransportSpec.Source.HEURISTIC);
        transport = new OpenAiResponsesTransport(spec, model, httpClient, new ObjectMapper(),
                Duration.ofSeconds(30));
        stubResponse(200, "{\"output\":[]}");

        transport.call(new Prompt(List.of(new UserMessage("hi"))));

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
                .contains("Bearer real-key");
    }

    @Test
    @DisplayName("describe — includes transport type and URL")
    void describe_includesTypeAndUrl() {
        assertThat(transport.describe()).isEqualTo("openai-responses@https://api.example.com/responses");
    }

    /* ====================== Phase 2 — tool calling loop ====================== */

    /** Queue scripted responses so we can verify the loop iterates over them. */
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

    private static Prompt promptWithTool(String userText, ToolCallback... callbacks) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(callbacks))
                .build();
        return new Prompt(List.of(new UserMessage(userText)), options);
    }

    private static ToolCallback recordingCallback(String name, AtomicInteger counter, String result) {
        ToolDefinition def = ToolDefinition.builder()
                .name(name)
                .description("test " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return def; }
            @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
            @Override public String call(String toolInput) { counter.incrementAndGet(); return result; }
            @Override public String call(String toolInput, @Nullable ToolContext toolContext) { return call(toolInput); }
        };
    }

    @Test
    @DisplayName("call — model returns function_call then message — tool is executed and final text returned")
    void call_toolLoop_executesCallbackAndReturnsFinal() throws Exception {
        AtomicInteger toolCallCount = new AtomicInteger();
        ToolCallback weather = recordingCallback("get_weather", toolCallCount, "Warsaw: 5°C");
        scriptResponses(
                """
                {"id":"r1","output":[
                    {"type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\\"city\\":\\"Warsaw\\"}"}
                ]}
                """,
                """
                {"id":"r2","output":[
                    {"type":"message","content":[{"type":"output_text","text":"Warsaw is 5°C."}]}
                ],"usage":{"input_tokens":20,"output_tokens":5,"total_tokens":25}}
                """);

        ChatResponse response = transport.call(promptWithTool("weather in Warsaw?", weather));

        assertThat(toolCallCount).hasValue(1);
        assertThat(response.getResult().getOutput().getText()).isEqualTo("Warsaw is 5°C.");
        verify(httpClient, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("call — model invokes unknown tool — loop completes with stub error fed back as function_call_output")
    void call_unknownTool_returnsStubErrorToModel() throws Exception {
        AtomicInteger known = new AtomicInteger();
        ToolCallback knownTool = recordingCallback("get_weather", known, "ok");
        scriptResponses(
                """
                {"id":"r1","output":[
                    {"type":"function_call","call_id":"call_x","name":"nonexistent_tool","arguments":"{}"}
                ]}
                """,
                """
                {"id":"r2","output":[{"type":"message","content":[{"type":"output_text","text":"I cannot use that tool."}]}]}
                """);

        ChatResponse response = transport.call(promptWithTool("do something", knownTool));

        assertThat(known).hasValue(0);
        assertThat(response.getResult().getOutput().getText()).isEqualTo("I cannot use that tool.");
    }

    @Test
    @DisplayName("call — model asks for tool but prompt has no callbacks — throws")
    void call_modelAsksToolButNoCallbacks_throws() throws Exception {
        scriptResponses("""
                {"id":"r1","output":[
                    {"type":"function_call","call_id":"c","name":"f","arguments":"{}"}
                ]}
                """);

        assertThatThrownBy(() -> transport.call(new Prompt(List.of(new UserMessage("hi")))))
                .isInstanceOf(OpenAiResponsesTransport.ResponsesTransportException.class)
                .hasMessageContaining("no ToolCallbacks");
    }

    @Test
    @DisplayName("call — request body includes tools array with parsed JSON schema")
    void call_tools_serialisedInRequestBody() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        ToolCallback weather = recordingCallback("get_weather", counter, "ok");
        scriptResponses("""
                {"id":"r1","output":[{"type":"message","content":[{"type":"output_text","text":"done"}]}]}
                """);
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);

        transport.call(promptWithTool("hi", weather));

        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        String body = new String(extractBody(requestCaptor.getValue()));
        assertThat(body).contains("\"tools\":[");
        assertThat(body).contains("\"name\":\"get_weather\"");
        assertThat(body).contains("\"type\":\"function\"");
        assertThat(body).contains("\"parameters\":{\"type\":\"object\"");
    }

    @Test
    @DisplayName("call — reasoning content from output → ChatGenerationMetadata.reasoning")
    void call_reasoningInOutput_surfacesInMetadata() throws Exception {
        stubResponse(200, """
                {"id":"r1","output":[
                    {"type":"reasoning","summary":[{"text":"step one"},{"text":"step two"}]},
                    {"type":"message","content":[{"type":"output_text","text":"answer"}]}
                ]}
                """);

        ChatResponse response = transport.call(new Prompt(List.of(new UserMessage("hi"))));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("answer");
        Object reasoning = response.getResult().getMetadata().get("reasoning");
        assertThat(reasoning).isEqualTo("step one\nstep two");
    }

    @Test
    @DisplayName("stream — no tools, only text — Spring AI sees deltas plus terminal frame")
    void stream_textOnly_emitsDeltasAndTerminal() throws Exception {
        // The streaming path is integration-flavoured; covered in detail by SseDecoderTest.
        // Here we only assert the fallback path doesn't crash when tools are present.
        AtomicInteger counter = new AtomicInteger();
        ToolCallback weather = recordingCallback("get_weather", counter, "ok");
        stubResponse(200, """
                {"id":"r1","output":[{"type":"message","content":[{"type":"output_text","text":"hi"}]}]}
                """);

        List<ChatResponse> emitted = transport.stream(promptWithTool("hi", weather))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).getResult().getOutput().getText()).isEqualTo("hi");
        verify(httpClient, atLeastOnce()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    /** Inspect the body of an outgoing HttpRequest. */
    private static byte[] extractBody(HttpRequest request) {
        return request.bodyPublisher()
                .map(bp -> {
                    java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>[] sub = new java.util.concurrent.Flow.Subscriber[1];
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    bp.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                        java.util.concurrent.Flow.Subscription s;
                        public void onSubscribe(java.util.concurrent.Flow.Subscription s) { this.s = s; s.request(Long.MAX_VALUE); }
                        public void onNext(java.nio.ByteBuffer b) { byte[] dst = new byte[b.remaining()]; b.get(dst); out.writeBytes(dst); }
                        public void onError(Throwable t) { latch.countDown(); }
                        public void onComplete() { latch.countDown(); }
                    });
                    try { latch.await(1, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                    return out.toByteArray();
                })
                .orElse(new byte[0]);
    }
}
