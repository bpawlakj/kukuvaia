package ai.kukuvaia.advisors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.kukuvaia.provider.service.ChatModelCache;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

@DisplayName("ConversationSummariser — worker-tier LLM summary writer")
class ConversationSummariserTest {

    private final ChatModelCache cache = mock(ChatModelCache.class);

    private ConversationSummariser summariser() {
        return new ConversationSummariser(cache, "worker", 10, 30_000);
    }

    private static AssistantMessage toolCall(String id, String name, String args) {
        return AssistantMessage.builder()
                .content("calling " + name)
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args)))
                .build();
    }

    private static ToolResponseMessage toolResp(String id, String name, String payload) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(id, name, payload)))
                .build();
    }

    @Test
    @DisplayName("happy path — returns the worker's response text trimmed")
    void summarise_happyPath_returnsResponse() {
        ChatModel model = stubModel("  Operator created rule X with outline Y.  ");
        when(cache.getByRole("worker")).thenReturn(model);

        Optional<String> result = summariser().summarise(
                "previous summary",
                List.of(new UserMessage("hi"), new AssistantMessage("hello")));

        assertThat(result).contains("Operator created rule X with outline Y.");
    }

    @Test
    @DisplayName("worker role unavailable — returns empty, no LLM call attempted")
    void summarise_roleMissing_returnsEmpty() {
        when(cache.getByRole("worker")).thenReturn(null);

        Optional<String> result = summariser().summarise(
                "prev", List.of(new UserMessage("hi"), new AssistantMessage("hello")));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("empty input messages — returns empty (nothing to absorb)")
    void summarise_emptyInput_returnsEmpty() {
        Optional<String> result = summariser().summarise("prev", List.of());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("model returns blank text — returns empty")
    void summarise_blankResponse_returnsEmpty() {
        ChatModel model = stubModel("   ");
        when(cache.getByRole("worker")).thenReturn(model);

        Optional<String> result = summariser().summarise(
                "prev", List.of(new UserMessage("hi"), new AssistantMessage("ok")));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("model throws — returns empty, caller falls through")
    void summarise_modelThrows_returnsEmpty() {
        ChatModel model = mock(ChatModel.class);
        when(cache.getByRole("worker")).thenReturn(model);
        when(model.call(any(Prompt.class))).thenThrow(new RuntimeException("provider down"));

        Optional<String> result = summariser().summarise(
                "prev", List.of(new UserMessage("hi"), new AssistantMessage("ok")));

        assertThat(result).isEmpty();
    }

    /**
     * Build a ChatModel mock chain without RETURNS_DEEP_STUBS so {@code verify(model).call(...)}
     * sees exactly one invocation (the production call). RETURNS_DEEP_STUBS would record an
     * extra {@code call(...)} during stub setup, breaking the verify count.
     */
    private static ChatModel stubModel(String responseText) {
        ChatModel model = mock(ChatModel.class);
        org.springframework.ai.chat.model.ChatResponse response =
                mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation gen =
                mock(org.springframework.ai.chat.model.Generation.class);
        AssistantMessage output = mock(AssistantMessage.class);
        when(model.call(any(Prompt.class))).thenReturn(response);
        when(response.getResult()).thenReturn(gen);
        when(gen.getOutput()).thenReturn(output);
        when(output.getText()).thenReturn(responseText);
        return model;
    }

    @Test
    @DisplayName("prompt includes previous summary verbatim AND new turn renderings")
    void summarise_promptContent_includesPreviousAndNewTurns() {
        ChatModel model = stubModel("new summary");
        when(cache.getByRole("worker")).thenReturn(model);

        List<Message> turns = List.of(
                new UserMessage("create rule X"),
                toolCall("c1", "tool_alpha", "{}"),
                toolResp("c1", "tool_alpha", "REJECT_GENERIC_ERROR"),
                new AssistantMessage("done"),
                new SystemMessage("[compacted: 3 attempts collapsed]"));

        summariser().summarise("operator started rule editing", turns);

        var captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).call(captor.capture());
        String userPrompt = captor.getValue().getInstructions().get(1).getText();
        assertThat(userPrompt).contains("operator started rule editing");
        assertThat(userPrompt).contains("USER: create rule X");
        assertThat(userPrompt).contains("[tool_call: tool_alpha]");
        assertThat(userPrompt).contains("TOOL_RESULT: tool_alpha");
        assertThat(userPrompt).contains("REJECT_GENERIC_ERROR");
        assertThat(userPrompt).contains("SYSTEM_NOTE:");
    }

    @Test
    @DisplayName("empty previous summary — prompt notes 'first pass'")
    void summarise_emptyPrevious_notesFirstPass() {
        ChatModel model = stubModel("ok");
        when(cache.getByRole("worker")).thenReturn(model);

        summariser().summarise("", List.of(new UserMessage("hi")));

        var captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(model).call(captor.capture());
        String userPrompt = captor.getValue().getInstructions().get(1).getText();
        assertThat(userPrompt).contains("(none — first pass)");
    }
}
