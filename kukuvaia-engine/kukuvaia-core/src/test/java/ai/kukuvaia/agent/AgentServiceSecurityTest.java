package ai.kukuvaia.agent;

import ai.kukuvaia.config.ToolRegistryConfig;
import ai.kukuvaia.memory.extraction.MemoryExtractionService;
import ai.kukuvaia.memory.repository.SessionRepository;
import ai.kukuvaia.output.SessionOutputSink;
import ai.kukuvaia.output.SpanEventEmitter;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.security.ErrorSanitizer;
import ai.kukuvaia.security.ToolResultSanitizingAdvisor;
import ai.kukuvaia.tools.DelegationTools;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentServiceSecurityTest {

    private final ChatModel model = mock(ChatModel.class);
    private final PersonaService personas = mock(PersonaService.class);
    private final SessionRepository sessions = mock(SessionRepository.class);
    private final ToolRegistryConfig registry;
    private final ToolCallback read = callback("read_data");
    private final ToolCallback write = callback("write_data");
    private AgentService agent;

    AgentServiceSecurityTest() {
        registry = new ToolRegistryConfig(List.of(() -> new ToolCallback[] {read, write}));
    }

    @BeforeEach
    void setUp() {
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(response("ok"));
        when(personas.getActivePersona(any())).thenReturn(persona(List.of("read_data")));
        var sink = new SessionOutputSink();
        agent = new AgentService(ChatClient.builder(model)
                        .defaultAdvisors(new ToolResultSanitizingAdvisor()).build(),
                personas, mock(MemoryExtractionService.class), sessions,
                Runnable::run, sink, new SpanEventEmitter(sink), mock(PlanningModeService.class),
                registry, new ErrorSanitizer());
        ReflectionTestUtils.setField(agent, "chatTimeoutSeconds", 5);
    }

    @Test
    void chat_restrictedPersonaAdvertisesOnlyAllowedTools() {
        assertThat(agent.chat("session", "hello")).isEqualTo("ok");
        assertScope(Set.of("read_data"));
    }

    @Test
    void streamChat_restrictedPersonaAdvertisesOnlyAllowedTools() {
        var blocks = agent.streamChat("session", "hello").collectList().block(Duration.ofSeconds(10));
        assertThat(blocks).anyMatch(block -> block instanceof TextBlock text && "ok".equals(text.content()));
        assertScope(Set.of("read_data"));
    }

    @Test
    void streamChat_personaChangeAfterDispatchDoesNotExpandTheCurrentTurn() {
        doAnswer(invocation -> {
            when(personas.getActivePersona("session")).thenReturn(persona(List.of("write_data")));
            return null;
        }).when(sessions).ensureExists(anyString(), anyString());

        agent.streamChat("session", "hello").collectList().block(Duration.ofSeconds(10));

        assertScope(Set.of("read_data"));
    }

    @Test
    void chat_unknownWhitelistDoesNotFallBackToAllTools() {
        when(personas.getActivePersona(any())).thenReturn(persona(List.of("unavailable_tool")));
        agent.chat("session", "hello");

        var options = options();
        assertThat(options.getToolCallbacks()).isNullOrEmpty();
        assertThat(options.getToolContext()).containsEntry(
                DelegationTools.PERSONA_TOOLS_CONTEXT_KEY, Set.of("unavailable_tool"));
    }

    @Test
    void chat_modelCannotExecuteForbiddenToolEvenWhenItFabricatesACall() {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "write_data", "{}"))).build()))));

        assertThatThrownBy(() -> agent.chat("session", "write anyway"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No ToolCallback found");
        verify(write, never()).call(anyString(), any());
        verify(write, never()).call(anyString());
    }

    @Test
    void chat_emptyPersonaFilterRetainsUnrestrictedDefault() {
        when(personas.getActivePersona(any())).thenReturn(persona(List.of()));
        agent.chat("session", "hello");
        assertScope(Set.of("read_data", "write_data"));
    }

    @Test
    void chat_consecutivePersonasDoNotShareToolScope() {
        agent.chat("first", "hello");
        when(personas.getActivePersona("second")).thenReturn(persona(List.of("write_data")));
        agent.chat("second", "hello");

        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(((ToolCallingChatOptions) prompts.getAllValues().get(0).getOptions()).getToolCallbacks())
                .extracting(tool -> tool.getToolDefinition().name()).containsExactly("read_data");
        assertThat(((ToolCallingChatOptions) prompts.getAllValues().get(1).getOptions()).getToolCallbacks())
                .extracting(tool -> tool.getToolDefinition().name()).containsExactly("write_data");
    }

    @Test
    void streamChat_providerFailureIsSanitizedBeforeEmission() {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException(
                "Failed jdbc:postgresql://internal:5432/test?password=synthetic "
                        + "Bearer fake+/token== /Users/test/config.yaml"));

        var blocks = agent.streamChat("session", "hello").collectList().block(Duration.ofSeconds(10));
        var errors = blocks.stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                .filter(text -> "error".equals(text.style())).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().content()).contains("[REDACTED]")
                .doesNotContain("jdbc:", "synthetic", "fake", "/Users/");
    }

    @Test
    void personaSpec_snakeCaseWhitelistIsLoaded() {
        var mapper = new ObjectMapper(new YAMLFactory());
        PersonaSpec persona = mapper.readValue("""
                name: readonly
                system_prompt: Read only.
                tool_filter: [read_data]
                """, PersonaSpec.class);
        assertThat(persona.toolFilter()).containsExactly("read_data");
    }

    private void assertScope(Set<String> expected) {
        var options = options();
        assertThat(options.getToolCallbacks()).extracting(tool -> tool.getToolDefinition().name())
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(options.getToolContext()).containsEntry(DelegationTools.PERSONA_TOOLS_CONTEXT_KEY, expected);
    }

    private ToolCallingChatOptions options() {
        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        return (ToolCallingChatOptions) prompt.getValue().getOptions();
    }

    private static PersonaSpec persona(List<String> tools) {
        return new PersonaSpec("readonly", "test", "Read only.", tools);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ToolCallback callback(String name) {
        var callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description(name).inputSchema("{\"type\":\"object\",\"properties\":{}}").build());
        return callback;
    }
}
