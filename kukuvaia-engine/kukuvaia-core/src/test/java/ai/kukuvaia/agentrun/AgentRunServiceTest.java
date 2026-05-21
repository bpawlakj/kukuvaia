package ai.kukuvaia.agentrun;

import ai.kukuvaia.agent.PersonaService;
import ai.kukuvaia.agent.PersonaSpec;
import ai.kukuvaia.memory.embedding.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lifecycle behaviour of {@link AgentRunService} — persona resolution, status transitions,
 * skip-if-empty, escalation, embedding gating, failure path.
 */
@DisplayName("AgentRunService — lifecycle")
@ExtendWith(MockitoExtension.class)
class AgentRunServiceTest {

    @Mock private AgentRunRepository repository;
    @Mock private ChatClient chatClient;
    @Mock private EmbeddingService embeddingService;
    @Mock private PersonaService personaService;

    @Mock private ChatClient.ChatClientRequestSpec promptSpec;
    @Mock private ChatClient.CallResponseSpec callSpec;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Executor inline = Runnable::run;
    private final AgentRunConfig.AgentRunProperties properties = new AgentRunConfig.AgentRunProperties();

    private AgentRunService service;

    @BeforeEach
    void setUp() {
        service = new AgentRunService(repository, chatClient, embeddingService,
                personaService, objectMapper, inline, properties);

        // ChatClient fluent stubbing — needs lenient because not every test path runs the LLM.
        lenient().when(chatClient.prompt()).thenReturn(promptSpec);
        lenient().when(promptSpec.system(anyString())).thenReturn(promptSpec);
        lenient().when(promptSpec.user(anyString())).thenReturn(promptSpec);
        lenient().when(promptSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(promptSpec);
        lenient().when(promptSpec.call()).thenReturn(callSpec);

        // Repository: default insert returns a stable UUID; findById echoes a minimal AgentRun.
        lenient().when(repository.insertQueued(any())).thenAnswer(inv -> UUID.randomUUID());
        lenient().when(repository.findById(any())).thenAnswer(inv -> Optional.of(emptyRun(inv.getArgument(0))));
    }

    @Test
    @DisplayName("runSync with no LLM: persists input as output verbatim, status=completed")
    void runSync_noLlm_persistsInput() {
        ObjectNode input = JsonNodeFactory.instance.objectNode().put("k", "v");
        AgentRunSpec spec = baseSpec(input, /*persona*/ null, /*followup*/ null);

        service.runSync(spec);

        verify(repository).markInProgress(any());
        verify(repository).markCompleted(any(), any(), anyString(), any(), any(), any(), any());
        verify(repository, never()).markFailed(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("runSync with personaName resolves PersonaService systemPrompt")
    void runSync_personaResolvedFromService() {
        when(personaService.allPersonas()).thenReturn(Map.of(
                "test-persona", new PersonaSpec("test-persona", "desc", "PERSONA SYSTEM PROMPT", List.of())));
        stubLlmResponse("{\"ok\":true}", 10, 5, "claude-haiku-4-5");
        // storeEmbedding=true triggers embeddingService.isAvailable() inside maybeEmbed
        when(embeddingService.isAvailable()).thenReturn(false);

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                "test-persona",
                new LlmFollowupSpec(null, null, true, true, null, false,
                        SimilarityContextSpec.disabled(), null));

        service.runSync(spec);

        ArgumentCaptor<String> sysCap = ArgumentCaptor.forClass(String.class);
        verify(promptSpec).system(sysCap.capture());
        assertThat(sysCap.getValue()).isEqualTo("PERSONA SYSTEM PROMPT");
    }

    @Test
    @DisplayName("runSync with personaName + override appends override after persona prompt")
    void runSync_personaPlusOverride_appended() {
        when(personaService.allPersonas()).thenReturn(Map.of(
                "test-persona", new PersonaSpec("test-persona", "desc", "BASE", List.of())));
        stubLlmResponse("ok", 1, 1, "model-x");

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                "test-persona",
                new LlmFollowupSpec("EXTRA INSTRUCTIONS", null, true, false, null, false,
                        SimilarityContextSpec.disabled(), null));

        service.runSync(spec);

        ArgumentCaptor<String> sysCap = ArgumentCaptor.forClass(String.class);
        verify(promptSpec).system(sysCap.capture());
        assertThat(sysCap.getValue()).contains("BASE").contains("EXTRA INSTRUCTIONS");
    }

    @Test
    @DisplayName("runSync with unknown persona throws IllegalArgumentException -> markFailed")
    void runSync_unknownPersona_markedFailed() {
        when(personaService.allPersonas()).thenReturn(Map.of());

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode(),
                "ghost-persona",
                new LlmFollowupSpec(null, null, true, false, null, false,
                        SimilarityContextSpec.disabled(), null));

        service.runSync(spec);

        verify(repository).markFailed(any(), anyString(), anyString());
        verify(repository, never()).markCompleted(any(), any(), anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("runSync with skipIfEmpty + no system prompt -> markSkipped")
    void runSync_emptySystemPrompt_skipped() {
        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                /*persona*/ null,
                new LlmFollowupSpec(/*systemPrompt*/ null, null, /*skipIfEmpty*/ true,
                        false, null, false, SimilarityContextSpec.disabled(), null));

        service.runSync(spec);

        verify(repository).markSkipped(any(), anyString());
        verify(repository, never()).markCompleted(any(), any(), anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("runSync embeds output when storeEmbedding=true and embedding service available")
    void runSync_storesEmbedding() {
        stubLlmResponse("{\"x\":1}", 7, 3, "model-x");
        when(embeddingService.isAvailable()).thenReturn(true);
        when(embeddingService.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f, 0.3f});

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                /*persona*/ null,
                new LlmFollowupSpec("SYS", null, true, /*storeEmbedding*/ true, null, false,
                        SimilarityContextSpec.disabled(), null));

        service.runSync(spec);

        ArgumentCaptor<float[]> embedCap = ArgumentCaptor.forClass(float[].class);
        verify(repository).markCompleted(any(), any(), anyString(), embedCap.capture(),
                anyString(), any(), any());
        assertThat(embedCap.getValue()).isNotNull();
        assertThat(embedCap.getValue()).hasSize(3);
    }

    @Test
    @DisplayName("runSync with storeEmbedding=false skips the embed call")
    void runSync_noEmbedding_whenDisabled() {
        stubLlmResponse("ok", 1, 1, "model-x");

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                /*persona*/ null,
                new LlmFollowupSpec("SYS", null, true, /*storeEmbedding*/ false, null, false,
                        SimilarityContextSpec.disabled(), null));

        service.runSync(spec);

        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    @DisplayName("escalation: trigger evaluates true -> second LLM call with escalateTo, tokens summed")
    void runSync_escalation_happyPath() {
        // first call returns JSON {needs_escalation: true}
        ChatResponse first = makeResponse("{\"needs_escalation\": true}", 10, 5, "claude-haiku-4-5");
        ChatResponse second = makeResponse("{\"final\": \"answer\"}", 30, 15, "claude-sonnet-4-6");
        when(callSpec.chatResponse()).thenReturn(first, second);

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                /*persona*/ null,
                new LlmFollowupSpec("SYS", null, true, false, null, false,
                        SimilarityContextSpec.disabled(),
                        new ModelPreferenceSpec("claude-haiku-4-5", "claude-sonnet-4-6",
                                "output.needs_escalation == true")));

        service.runSync(spec);

        // Two LLM calls
        verify(callSpec, times(2)).chatResponse();

        // markCompleted invoked with escalateTo model and summed tokens (10+30=40, 5+15=20)
        ArgumentCaptor<String> modelCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> promptCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> completionCap = ArgumentCaptor.forClass(Integer.class);
        verify(repository).markCompleted(any(), any(), anyString(), any(),
                modelCap.capture(), promptCap.capture(), completionCap.capture());
        assertThat(modelCap.getValue()).isEqualTo("claude-sonnet-4-6");
        assertThat(promptCap.getValue()).isEqualTo(40);
        assertThat(completionCap.getValue()).isEqualTo(20);
    }

    @Test
    @DisplayName("escalation: trigger evaluates false -> single LLM call only")
    void runSync_escalation_triggerFalse() {
        ChatResponse first = makeResponse("{\"needs_escalation\": false}", 10, 5, "model-a");
        when(callSpec.chatResponse()).thenReturn(first);

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                /*persona*/ null,
                new LlmFollowupSpec("SYS", null, true, false, null, false,
                        SimilarityContextSpec.disabled(),
                        new ModelPreferenceSpec("model-a", "model-b",
                                "output.needs_escalation == true")));

        service.runSync(spec);

        verify(callSpec, times(1)).chatResponse();
    }

    @Test
    @DisplayName("escalation: malformed SpEL fails safely (no escalation)")
    void runSync_escalation_malformedExpression_noEscalation() {
        ChatResponse first = makeResponse("{\"x\":1}", 5, 3, "model-a");
        when(callSpec.chatResponse()).thenReturn(first);

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                /*persona*/ null,
                new LlmFollowupSpec("SYS", null, true, false, null, false,
                        SimilarityContextSpec.disabled(),
                        new ModelPreferenceSpec("model-a", "model-b",
                                "this is not @@ a valid expression")));

        service.runSync(spec);

        verify(callSpec, times(1)).chatResponse();
    }

    @Test
    @DisplayName("max-escalation-depth=0 disables escalation even if trigger fires")
    void runSync_maxEscalationDepthZero_disablesEscalation() {
        properties.setMaxEscalationDepth(0);
        ChatResponse first = makeResponse("{\"needs_escalation\": true}", 5, 3, "model-a");
        when(callSpec.chatResponse()).thenReturn(first);

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                /*persona*/ null,
                new LlmFollowupSpec("SYS", null, true, false, null, false,
                        SimilarityContextSpec.disabled(),
                        new ModelPreferenceSpec("model-a", "model-b",
                                "output.needs_escalation == true")));

        service.runSync(spec);

        verify(callSpec, times(1)).chatResponse();
    }

    @Test
    @DisplayName("ChatClient throws -> markFailed with exception class as error_code")
    void runSync_chatClientThrows_markedFailed() {
        when(callSpec.chatResponse()).thenThrow(new RuntimeException("upstream timeout"));

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                /*persona*/ null,
                new LlmFollowupSpec("SYS", null, true, false, null, false,
                        SimilarityContextSpec.disabled(), null));

        service.runSync(spec);

        ArgumentCaptor<String> codeCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(repository).markFailed(any(), codeCap.capture(), msgCap.capture());
        assertThat(codeCap.getValue()).isEqualTo("RuntimeException");
        assertThat(msgCap.getValue()).contains("upstream timeout");
    }

    @Test
    @DisplayName("enqueue inserts queued row, executes via executor, returns runId")
    void enqueue_returnsId_andRuns() {
        UUID expected = UUID.randomUUID();
        when(repository.insertQueued(any())).thenReturn(expected);
        stubLlmResponse("ok", 1, 1, "model");

        AgentRunSpec spec = baseSpec(JsonNodeFactory.instance.objectNode().put("a", 1),
                /*persona*/ null,
                new LlmFollowupSpec("SYS", null, true, false, null, false,
                        SimilarityContextSpec.disabled(), null));

        UUID returned = service.enqueue(spec);

        assertThat(returned).isEqualTo(expected);
        verify(repository).markInProgress(expected);
        verify(repository).markCompleted(any(), any(), anyString(), any(), any(), any(), any());
    }

    // --- helpers ---------------------------------------------------------

    private AgentRunSpec baseSpec(com.fasterxml.jackson.databind.JsonNode input,
                                  String personaName,
                                  LlmFollowupSpec followup) {
        return new AgentRunSpec(
                "test-invoker", "agent",
                "test_input", input,
                personaName, followup,
                /*parentRunId*/ null, /*threadId*/ null,
                List.of(), null, null);
    }

    private void stubLlmResponse(String text, int prompt, int completion, String model) {
        ChatResponse r = makeResponse(text, prompt, completion, model);
        when(callSpec.chatResponse()).thenReturn(r);
    }

    private ChatResponse makeResponse(String text, int prompt, int completion, String model) {
        AssistantMessage msg = new AssistantMessage(text);
        Generation gen = new Generation(msg);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(new DefaultUsage(prompt, completion))
                .build();
        return new ChatResponse(List.of(gen), metadata);
    }

    private AgentRun emptyRun(UUID id) {
        return new AgentRun(id, null, null, "test-invoker", "agent", "test_input",
                JsonNodeFactory.instance.objectNode(), null, null, null,
                null, AgentRunStatus.COMPLETED, null, null, null, null, null, null, null,
                null, null, null, java.time.Instant.now(), List.of(), null);
    }
}
