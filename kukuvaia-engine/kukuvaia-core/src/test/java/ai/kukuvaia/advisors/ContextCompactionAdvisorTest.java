package ai.kukuvaia.advisors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.SessionOutputSink;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.provider.model.ModelRecord;
import ai.kukuvaia.provider.repository.ModelRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

@DisplayName("ContextCompactionAdvisor — hard-cap trim drops oldest tool pairs, pins system + recent")
@ExtendWith(MockitoExtension.class)
class ContextCompactionAdvisorTest {

    @Mock private ModelRepository modelRepository;
    @Mock private SessionOutputSink outputSink;
    @Mock private AdvisorChain chain;

    private final TokenEstimator tokenEstimator = new TokenEstimator();

    /** Build the advisor with a small default window so we don't need 200K-token fixtures. */
    private ContextCompactionAdvisor advisor(int defaultWindow, int safetyMargin, int keepLastTurns) {
        return new ContextCompactionAdvisor(
                tokenEstimator, modelRepository, outputSink,
                /* enabled */ true, defaultWindow, safetyMargin, keepLastTurns);
    }

    private ChatClientRequest request(List<Message> messages, String sessionId) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .context("chat_memory_conversation_id", sessionId)
                .build();
    }

    private AssistantMessage toolCall(String callId, String toolName) {
        return AssistantMessage.builder()
                .content("calling tool")
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", toolName, "{}")))
                .build();
    }

    private ToolResponseMessage toolResponse(String callId, String toolName, String payload) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, toolName, payload)))
                .build();
    }

    private String bigPayload(int chars) {
        return "x".repeat(chars);
    }

    @Test
    @DisplayName("under hard cap — request passes through unchanged, no warning emitted")
    void underHardCap_passesThrough() {
        // 200-token window, 5-token margin → hard cap = 195.
        var a = advisor(200, 5, 2);

        List<Message> msgs = List.of(
                new SystemMessage("Persona prompt"),
                new UserMessage("Short question"));

        ChatClientRequest req = request(msgs, "session-1");
        ChatClientRequest out = a.before(req, chain);

        assertThat(out.prompt().getInstructions()).hasSameSizeAs(msgs);
        verify(outputSink, never()).emit(any(), any());
    }

    @Test
    @DisplayName("over hard cap — drops oldest non-pinned tool-call pair as a unit")
    void overHardCap_dropsOldestToolPair() {
        // Tiny window so the fixture overflows: 250 tokens cap, 5 margin = 245.
        var a = advisor(250, 5, 1);

        // 2 tool-call pairs, each pair carries a ~600-char payload (~190 tokens with JSON
        // weighting), then a recent user message. Together they exceed 245.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        msgs.add(new UserMessage("create a rule"));
        msgs.add(toolCall("c1", "introspect_section_schema"));
        msgs.add(toolResponse("c1", "introspect_section_schema", bigPayload(600)));
        msgs.add(toolCall("c2", "introspect_section_schema"));
        msgs.add(toolResponse("c2", "introspect_section_schema", bigPayload(600)));
        msgs.add(new UserMessage("what's next?"));

        int before = tokenEstimator.estimate(msgs);
        assertThat(before).isGreaterThan(245);   // sanity: fixture really overflows

        ChatClientRequest req = request(msgs, "session-1");
        ChatClientRequest out = a.before(req, chain);

        List<Message> after = out.prompt().getInstructions();
        // Oldest pair (c1 assistant + c1 tool response) should be dropped — 5 messages remain.
        assertThat(after).hasSize(5);
        // System message preserved at head.
        assertThat(after.get(0)).isInstanceOf(SystemMessage.class);
        // Most recent pair (c2) preserved.
        boolean hasC2Pair = after.stream().anyMatch(m -> m instanceof AssistantMessage am
                && am.hasToolCalls()
                && am.getToolCalls().stream().anyMatch(tc -> "c2".equals(tc.id())));
        assertThat(hasC2Pair).isTrue();
        // Old c1 pair gone.
        boolean hasC1Pair = after.stream().anyMatch(m -> m instanceof AssistantMessage am
                && am.hasToolCalls()
                && am.getToolCalls().stream().anyMatch(tc -> "c1".equals(tc.id())));
        assertThat(hasC1Pair).isFalse();
        // Trailing user message preserved.
        assertThat(after.get(after.size() - 1)).isInstanceOf(UserMessage.class);

        // Warning block emitted onto the session sink.
        ArgumentCaptor<OutputBlock> emitted = ArgumentCaptor.forClass(OutputBlock.class);
        verify(outputSink).emit(eq("session-1"), emitted.capture());
        assertThat(emitted.getValue()).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) emitted.getValue()).style()).isEqualTo("warning");
    }

    @Test
    @DisplayName("system message is never dropped even when alone would push over the cap")
    void systemMessage_neverDropped() {
        var a = advisor(150, 5, 0);

        // System message itself big-ish + a giant tool pair we'd love to drop.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage(bigPayload(200)));      // ~50 tokens (NL weighting)
        msgs.add(new UserMessage("go"));
        msgs.add(toolCall("c1", "tool"));
        msgs.add(toolResponse("c1", "tool", bigPayload(600)));
        msgs.add(new UserMessage("again"));

        ChatClientRequest out = a.before(request(msgs, "s"), chain);
        List<Message> after = out.prompt().getInstructions();
        // System message survives at head.
        assertThat(after.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(after.get(0).getText()).hasSize(200);
    }

    @Test
    @DisplayName("keepLastTurns=2 — keeps the two most recent tool-call pairs intact")
    void keepLastTurns_protectsRecentPairs() {
        var a = advisor(400, 5, 2);

        // 4 tool pairs with payloads big enough to overflow ANY one being kept; we want to
        // see oldest two dropped and newest two kept.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        msgs.add(new UserMessage("go"));
        for (int i = 1; i <= 4; i++) {
            msgs.add(toolCall("c" + i, "tool"));
            msgs.add(toolResponse("c" + i, "tool", bigPayload(400)));
        }
        msgs.add(new UserMessage("status?"));

        ChatClientRequest out = a.before(request(msgs, "s"), chain);
        List<Message> after = out.prompt().getInstructions();

        // c3 and c4 (the most recent two pairs) must survive.
        assertThat(hasPair(after, "c3")).isTrue();
        assertThat(hasPair(after, "c4")).isTrue();
        // c1 should have been dropped first since the compactor walks oldest-first.
        assertThat(hasPair(after, "c1")).isFalse();
    }

    @Test
    @DisplayName("active model's contextWindow from ModelRepository overrides default")
    void activeModelWindow_overridesDefault() {
        // Default in advisor: 200k. We pass 1M-tier window via the model record so the
        // exact same fixture that previously overflowed should now pass through.
        var a = advisor(200, 5, 1);
        when(modelRepository.findByModelId("opus-1m"))
                .thenReturn(Optional.of(modelRecord("opus-1m", 1_000_000)));

        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        msgs.add(new UserMessage("go"));
        msgs.add(toolCall("c1", "tool"));
        msgs.add(toolResponse("c1", "tool", bigPayload(2_000)));
        msgs.add(new UserMessage("done?"));

        ChatClientRequest req = ChatClientRequest.builder()
                .prompt(new Prompt(msgs))
                .context("chat_memory_conversation_id", "s")
                .context(ModelRoutingAdvisor.CTX_ROUTED_MODEL, "opus-1m")
                .build();

        ChatClientRequest out = a.before(req, chain);

        // Same fixture, but with a 1M window the prompt is way under cap — no trim.
        assertThat(out.prompt().getInstructions()).hasSameSizeAs(msgs);
        verify(outputSink, never()).emit(any(), any());
    }

    @Test
    @DisplayName("disabled — pass-through even when over cap")
    void disabled_passesThrough() {
        var a = new ContextCompactionAdvisor(
                tokenEstimator, modelRepository, outputSink,
                /* enabled */ false, 100, 5, 0);

        List<Message> msgs = List.of(
                new SystemMessage("persona"),
                new UserMessage(bigPayload(10_000)));   // would overflow if enabled

        ChatClientRequest out = a.before(request(msgs, "s"), chain);
        assertThat(out.prompt().getInstructions()).hasSameSizeAs(msgs);
        verify(outputSink, never()).emit(any(), any());
    }

    private static boolean hasPair(List<Message> msgs, String callId) {
        return msgs.stream().anyMatch(m -> m instanceof AssistantMessage am
                && am.hasToolCalls()
                && am.getToolCalls().stream().anyMatch(tc -> callId.equals(tc.id())));
    }

    private static ModelRecord modelRecord(String modelId, int contextWindow) {
        return new ModelRecord(
                UUID.randomUUID(), UUID.randomUUID(), modelId, modelId,
                List.of(), "standard", 8192, contextWindow, true, Map.of(),
                Instant.now(), Instant.now(), Instant.now());
    }
}
