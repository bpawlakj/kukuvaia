package ai.kukuvaia.provider.transport.anthropic;

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

@DisplayName("AnthropicMessagesMapper — Prompt ↔ Messages JSON")
class AnthropicMessagesMapperTest {

    private AnthropicMessagesMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AnthropicMessagesMapper(new ObjectMapper());
    }

    private ModelRecord model(String id, Map<String, Object> config) {
        return new ModelRecord(UUID.randomUUID(), UUID.randomUUID(), id, id,
                List.of("text"), "standard", 4096, null, true, config, null, Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("buildRequestBody — SystemMessage folded into top-level 'system' field, not messages")
    void systemMessage_extractedToTopLevel() {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("be terse"),
                new UserMessage("hi")));

        Map<String, Object> body = mapper.buildRequestBody(prompt, model("claude-sonnet-4.6", Map.of()));

        assertThat(body).containsEntry("system", "be terse");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0)).containsEntry("role", "user");
    }

    @Test
    @DisplayName("buildRequestBody — text content is always wrapped in [{type:text, text:...}]")
    void content_isAlwaysBlockArray() {
        Prompt prompt = new Prompt(List.of(new UserMessage("hi")));

        Map<String, Object> body = mapper.buildRequestBody(prompt, model("claude-sonnet-4.6", Map.of()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        Object content = messages.get(0).get("content");
        assertThat(content).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).containsEntry("type", "text").containsEntry("text", "hi");
    }

    @Test
    @DisplayName("buildRequestBody — max_tokens always emitted (Anthropic requires it)")
    void maxTokens_alwaysEmitted() {
        Map<String, Object> body = mapper.buildRequestBody(
                new Prompt(List.of(new UserMessage("hi"))),
                model("claude-haiku-4.5", Map.of()));

        assertThat(body.get("max_tokens")).isEqualTo(4096);
    }

    @Test
    @DisplayName("buildRequestBody — thinking config triggers extended-thinking block with budget")
    void thinking_emitted() {
        Map<String, Object> body = mapper.buildRequestBody(
                new Prompt(List.of(new UserMessage("hi"))),
                model("claude-opus-4.7", Map.of("thinking", true, "thinking_budget", 8000)));

        @SuppressWarnings("unchecked")
        Map<String, Object> thinking = (Map<String, Object>) body.get("thinking");
        assertThat(thinking).containsEntry("type", "enabled").containsEntry("budget_tokens", 8000);
    }

    @Test
    @DisplayName("toChatResponse — concatenates text blocks, extracts thinking as reasoning metadata")
    void toChatResponse_concatTextAndThinking() {
        String body = """
                {
                  "id": "msg_xyz",
                  "model": "claude-sonnet-4.6-20260301",
                  "content": [
                    {"type": "thinking", "thinking": "step one"},
                    {"type": "text", "text": "Hello, "},
                    {"type": "text", "text": "world!"}
                  ],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 8, "output_tokens": 3}
                }
                """;

        ChatResponse response = mapper.parseResponse(body, "claude-sonnet-4.6");

        assertThat(response.getResult().getOutput().getText()).isEqualTo("Hello, world!");
        Object reasoning = response.getResult().getMetadata().get("reasoning");
        assertThat(reasoning).isEqualTo("step one");
        DefaultUsage usage = (DefaultUsage) response.getMetadata().getUsage();
        assertThat(usage.getPromptTokens()).isEqualTo(8);
        assertThat(usage.getCompletionTokens()).isEqualTo(3);
    }

    @Test
    @DisplayName("extractToolUses — picks tool_use blocks with id/name/input")
    void extractToolUses_findsBlocks() {
        Map<String, Object> root = mapper.parseResponseJson("""
                {
                  "id": "msg_1",
                  "content": [
                    {"type": "text", "text": "I'll look up the weather."},
                    {"type": "tool_use", "id": "toolu_1", "name": "get_weather",
                     "input": {"city": "Warsaw"}},
                    {"type": "tool_use", "id": "toolu_2", "name": "get_weather",
                     "input": {"city": "Berlin"}}
                  ],
                  "stop_reason": "tool_use"
                }
                """);

        List<AnthropicMessagesMapper.ToolUse> uses = AnthropicMessagesMapper.extractToolUses(root);

        assertThat(uses).hasSize(2);
        assertThat(uses.get(0).id()).isEqualTo("toolu_1");
        assertThat(uses.get(0).name()).isEqualTo("get_weather");
        assertThat(uses.get(0).input()).containsEntry("city", "Warsaw");
        assertThat(uses.get(1).input()).containsEntry("city", "Berlin");
    }

    @Test
    @DisplayName("toChatResponse — type=error → AnthropicProtocolException")
    void errorPayload_throws() {
        assertThatThrownBy(() -> mapper.parseResponse("""
                {"type":"error","error":{"type":"rate_limit","message":"too many"}}
                """, "claude-sonnet-4.6"))
                .isInstanceOf(AnthropicMessagesMapper.AnthropicProtocolException.class)
                .hasMessageContaining("too many");
    }

    @Test
    @DisplayName("appendToolResults — emits one user message with N tool_result blocks")
    void appendToolResults_batchesBlocksIntoOneUserTurn() {
        java.util.List<Map<String, Object>> messages = new java.util.ArrayList<>();
        List<AnthropicMessagesMapper.ToolUse> uses = List.of(
                new AnthropicMessagesMapper.ToolUse("toolu_1", "get_weather", Map.of("city", "Warsaw")),
                new AnthropicMessagesMapper.ToolUse("toolu_2", "get_weather", Map.of("city", "Berlin")));
        AnthropicMessagesMapper.appendToolResults(messages, uses, List.of("5°C", "3°C"));

        assertThat(messages).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) messages.get(0).get("content");
        assertThat(messages.get(0)).containsEntry("role", "user");
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).containsEntry("tool_use_id", "toolu_1").containsEntry("content", "5°C");
        assertThat(blocks.get(1)).containsEntry("tool_use_id", "toolu_2").containsEntry("content", "3°C");
    }

    @Test
    @DisplayName("addTools — schema lands in input_schema (not parameters), name+description preserved")
    void addTools_usesInputSchemaKey() {
        AnthropicMessagesMapper m = new AnthropicMessagesMapper(new ObjectMapper());
        // We only need the mapper's serialiser; tool callbacks are built up in the
        // dedicated transport test where Spring AI ToolCallback infrastructure lives.
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("messages", List.of());
        // Direct API check: addTools serialises input_schema
        m.addTools(body, List.of(new TestToolCallback()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0))
                .containsEntry("name", "weather")
                .containsEntry("description", "Gets the weather");
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) tools.get(0).get("input_schema");
        assertThat(schema).containsEntry("type", "object");
    }

    private static class TestToolCallback implements org.springframework.ai.tool.ToolCallback {
        @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name("weather").description("Gets the weather")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
        }
        @Override public String call(String toolInput) { return ""; }
    }
}
