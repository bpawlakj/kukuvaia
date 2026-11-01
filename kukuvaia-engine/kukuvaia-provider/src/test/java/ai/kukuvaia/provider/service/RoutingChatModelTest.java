package ai.kukuvaia.provider.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RoutingChatModel — ChatClient integration")
class RoutingChatModelTest {

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("test tool")
                        .inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

    @Test
    @DisplayName("ChatClient on RoutingChatModel sends registered tools to the model (Spring AI 2.x getOptions())")
    void chatClient_passesToolCallbacksThrough() {
        AtomicReference<Prompt> seen = new AtomicReference<>();
        ChatModel target = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seen.set(prompt);
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }
        };
        ChatModelCache cache = mock(ChatModelCache.class);
        when(cache.getByRole("supervisor")).thenReturn(target);

        String content = ChatClient.builder(new RoutingChatModel(cache))
                .defaultTools(tool("echo"))
                .build()
                .prompt().user("hi")
                .call().content();

        assertThat(content).isEqualTo("done");
        // Spring AI 2.x ChatClient silently drops tools unless the model's getOptions()
        // is a ToolCallingChatOptions — this is the regression guard for that.
        assertThat(seen.get().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        var toolOptions = (ToolCallingChatOptions) seen.get().getOptions();
        assertThat(toolOptions.getToolCallbacks())
                .extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("echo");
    }
}
