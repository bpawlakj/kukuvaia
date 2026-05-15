package ai.kukuvaia.provider.transport.openai;

import ai.kukuvaia.provider.model.ModelRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OpenAiResponsesMapper — Prompt ↔ Responses JSON")
class OpenAiResponsesMapperTest {

    private ObjectMapper json;
    private OpenAiResponsesMapper mapper;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper();
        mapper = new OpenAiResponsesMapper(json);
    }

    private ModelRecord model(String id, Map<String, Object> config) {
        return new ModelRecord(UUID.randomUUID(), UUID.randomUUID(), id, id,
                List.of("text"), "standard", 4096, null, true, config, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("buildRequestBody — emits model, input array, max_output_tokens, temperature")
    void buildRequestBody_emitsExpectedFields() {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("be terse"),
                new UserMessage("hi")));
        ModelRecord m = model("gpt-5.4-mini", Map.of("temperature", 0.5));

        Map<String, Object> body = mapper.buildRequestBody(prompt, m);

        assertThat(body).containsEntry("model", "gpt-5.4-mini");
        assertThat(body).containsEntry("max_output_tokens", 4096);
        assertThat(body).containsEntry("temperature", 0.5);
        assertThat(body.get("input")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) body.get("input");
        assertThat(input).hasSize(2);
        assertThat(input.get(0)).containsEntry("role", "system").containsEntry("content", "be terse");
        assertThat(input.get(1)).containsEntry("role", "user").containsEntry("content", "hi");
    }

    @Test
    @DisplayName("buildRequestBody — assistant message round-trips with role 'assistant'")
    void buildRequestBody_assistantMessage_mapsCorrectly() {
        Prompt prompt = new Prompt(List.of(
                new UserMessage("hi"),
                new AssistantMessage("hello"),
                new UserMessage("how are you?")));

        Map<String, Object> body = mapper.buildRequestBody(prompt, model("gpt-5.4", Map.of()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> input = (List<Map<String, Object>>) body.get("input");
        assertThat(input).hasSize(3);
        assertThat(input.get(1)).containsEntry("role", "assistant").containsEntry("content", "hello");
    }

    @Test
    @DisplayName("buildRequestBody — previous_response_id from model config is propagated")
    void buildRequestBody_previousResponseId_propagated() {
        ModelRecord m = model("gpt-5.4-mini", Map.of("previous_response_id", "resp_prev"));

        Map<String, Object> body = mapper.buildRequestBody(
                new Prompt(List.of(new UserMessage("hi"))), m);

        assertThat(body).containsEntry("previous_response_id", "resp_prev");
    }

    @Test
    @DisplayName("parseResponse — message + output_text → ChatResponse with assistant content")
    void parseResponse_messageOutputText_buildsChatResponse() {
        String body = """
                {
                  "id": "resp_abc",
                  "model": "gpt-5.4-mini-2026-01-01",
                  "output": [
                    {"type": "message", "role": "assistant",
                     "content": [{"type": "output_text", "text": "Hello!"}]}
                  ],
                  "usage": {"input_tokens": 12, "output_tokens": 3, "total_tokens": 15}
                }
                """;

        ChatResponse response = mapper.parseResponse(body, "gpt-5.4-mini");

        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResult().getOutput().getText()).isEqualTo("Hello!");
        assertThat(response.getMetadata().getId()).isEqualTo("resp_abc");
        assertThat(response.getMetadata().getModel()).isEqualTo("gpt-5.4-mini-2026-01-01");
        DefaultUsage usage = (DefaultUsage) response.getMetadata().getUsage();
        assertThat(usage.getPromptTokens()).isEqualTo(12);
        assertThat(usage.getCompletionTokens()).isEqualTo(3);
        assertThat(usage.getTotalTokens()).isEqualTo(15);
    }

    @Test
    @DisplayName("parseResponse — multiple output_text chunks are concatenated")
    void parseResponse_multipleTextChunks_concatenated() {
        String body = """
                {
                  "id": "r1",
                  "output": [
                    {"type": "message",
                     "content": [
                       {"type": "output_text", "text": "Hello, "},
                       {"type": "output_text", "text": "world!"}
                     ]}
                  ]
                }
                """;

        ChatResponse response = mapper.parseResponse(body, "gpt-5.4");

        assertThat(response.getResult().getOutput().getText()).isEqualTo("Hello, world!");
    }

    @Test
    @DisplayName("parseResponse — non-message output items (reasoning, function_call) are skipped in Phase 1")
    void parseResponse_nonMessageItems_skipped() {
        String body = """
                {
                  "id": "r1",
                  "output": [
                    {"type": "reasoning", "summary": [{"text": "thinking..."}]},
                    {"type": "message", "content": [{"type": "output_text", "text": "Final"}]}
                  ]
                }
                """;

        ChatResponse response = mapper.parseResponse(body, "gpt-5.4");

        assertThat(response.getResult().getOutput().getText()).isEqualTo("Final");
    }

    @Test
    @DisplayName("parseResponse — error object → ResponsesProtocolException with API message")
    void parseResponse_errorObject_throws() {
        String body = """
                {"error": {"message": "rate limit", "code": "rate_limit_exceeded"}}
                """;

        assertThatThrownBy(() -> mapper.parseResponse(body, "gpt-5.4"))
                .isInstanceOf(OpenAiResponsesMapper.ResponsesProtocolException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    @DisplayName("parseResponse — missing output array → empty assistant text, no crash")
    void parseResponse_missingOutput_emptyText() {
        String body = "{\"id\": \"r1\"}";

        ChatResponse response = mapper.parseResponse(body, "gpt-5.4");

        assertThat(response.getResult().getOutput().getText()).isEmpty();
        assertThat(response.getMetadata().getId()).isEqualTo("r1");
    }
}
