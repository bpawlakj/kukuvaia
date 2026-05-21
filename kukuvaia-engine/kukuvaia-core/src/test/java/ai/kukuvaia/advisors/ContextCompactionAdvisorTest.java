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
    @Mock private ConversationSummariser summariser;
    @Mock private ai.kukuvaia.memory.repository.ConversationSummaryRepository summaryRepository;

    private final TokenEstimator tokenEstimator = new TokenEstimator();
    private final ToolCompactionRegistry registry = new ToolCompactionRegistry();

    /** Build the advisor with a small default window so we don't need 200K-token fixtures. */
    private ContextCompactionAdvisor advisor(int defaultWindow, int safetyMargin, int keepLastTurns) {
        return new ContextCompactionAdvisor(
                tokenEstimator, modelRepository, outputSink, summariser, summaryRepository, registry,
                /* enabled */ true, defaultWindow, safetyMargin, keepLastTurns,
                /* summarisationEnabled */ false, /* summarisationKeepLastTurns */ 4,
                /* toolSummariesEnabled */ false, /* toolSummariesDefaultElision */ false);
    }

    /** Variant that enables Phase D — used only by tests that target the summarisation path. */
    private ContextCompactionAdvisor advisorWithPhaseD(int defaultWindow, int safetyMargin,
            int keepLastTurns, int summarisationKeepLastTurns) {
        return new ContextCompactionAdvisor(
                tokenEstimator, modelRepository, outputSink, summariser, summaryRepository, registry,
                /* enabled */ true, defaultWindow, safetyMargin, keepLastTurns,
                /* summarisationEnabled */ true, summarisationKeepLastTurns,
                /* toolSummariesEnabled */ false, /* toolSummariesDefaultElision */ false);
    }

    /** Variant that enables Phase C — used by tests targeting tool-response summarisation. */
    private ContextCompactionAdvisor advisorWithPhaseC(int defaultWindow, int safetyMargin,
            int keepLastTurns, boolean defaultElision) {
        return new ContextCompactionAdvisor(
                tokenEstimator, modelRepository, outputSink, summariser, summaryRepository, registry,
                /* enabled */ true, defaultWindow, safetyMargin, keepLastTurns,
                /* summarisationEnabled */ false, /* summarisationKeepLastTurns */ 4,
                /* toolSummariesEnabled */ true, defaultElision);
    }

    private ChatClientRequest request(List<Message> messages, String sessionId) {
        return ChatClientRequest.builder()
                .prompt(new Prompt(messages))
                .context("chat_memory_conversation_id", sessionId)
                .build();
    }

    private AssistantMessage toolCall(String callId, String toolName) {
        // Default to args containing the callId, so distinct calls aren't seen as duplicates
        // by Phase B's fold-duplicates strategy.
        return toolCall(callId, toolName, "{\"id\":\"" + callId + "\"}");
    }

    private AssistantMessage toolCall(String callId, String toolName, String argsJson) {
        return AssistantMessage.builder()
                .content("calling tool")
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", toolName, argsJson)))
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
    @DisplayName("Phase B — over cap with identical-arg duplicates folds them before Phase A drops")
    void phaseB_dupFoldRunsBeforeHardDrop() {
        // Window of 300 forces compaction; ample room for the fold to fit so no Phase-A drop.
        var a = advisor(300, 5, 1);

        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        msgs.add(new UserMessage("create rule"));
        // Three identical-arg calls — older two should fold.
        for (int i = 1; i <= 3; i++) {
            msgs.add(toolCall("c" + i, "introspect_section_schema", "{\"template\":\"X\"}"));
            msgs.add(toolResponse("c" + i, "introspect_section_schema", bigPayload(400)));
        }
        msgs.add(new UserMessage("status?"));

        int before = tokenEstimator.estimate(msgs);
        assertThat(before).isGreaterThan(295);   // sanity: fixture overflows

        ChatClientRequest out = a.before(request(msgs, "s"), chain);
        List<Message> after = out.prompt().getInstructions();

        // Most recent identical call (c3) kept verbatim.
        assertThat(hasPair(after, "c3")).isTrue();
        // c1 and c2 folded out.
        assertThat(hasPair(after, "c1")).isFalse();
        assertThat(hasPair(after, "c2")).isFalse();

        // Warning OutputBlock mentions the duplicate fold count.
        ArgumentCaptor<OutputBlock> emitted = ArgumentCaptor.forClass(OutputBlock.class);
        verify(outputSink).emit(eq("s"), emitted.capture());
        TextBlock block = (TextBlock) emitted.getValue();
        assertThat(block.content()).contains("2 duplicate(s) folded");
    }

    @Test
    @DisplayName("Phase B — replays 2026-05-13 retry loop, emits one collapsed-attempts note")
    void phaseB_retryLoopCollapse() {
        var a = advisor(400, 5, 1);

        String rejectPayload = "InvariantViolationException: REJECT_GENERIC_ERROR";
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        msgs.add(new UserMessage("create rule"));
        // Four consecutive failed attempts with similar (but not identical — varying value) args.
        for (int i = 1; i <= 4; i++) {
            msgs.add(toolCall("c" + i, "tool_alpha",
                    "{\"outlineId\":\"u" + i + "\",\"ruleJson\":\"v" + i + "\"}"));
            // Bulk the response so the fixture clears the 395-token sanity floor; mimics
            // a real REJECT payload with diagnostic histogram + reasoning trail.
            msgs.add(toolResponse("c" + i, "tool_alpha",
                    rejectPayload + " attempt-" + i + " " + bigPayload(300)));
        }
        msgs.add(new UserMessage("status?"));

        int before = tokenEstimator.estimate(msgs);
        assertThat(before).isGreaterThan(395);

        ChatClientRequest out = a.before(request(msgs, "s"), chain);
        List<Message> after = out.prompt().getInstructions();

        // Most recent attempt (c4) kept verbatim.
        assertThat(hasPair(after, "c4")).isTrue();
        // Older 3 attempts folded.
        for (String old : List.of("c1", "c2", "c3")) {
            assertThat(hasPair(after, old)).as("attempt %s should be folded", old).isFalse();
        }
        // One synthetic note naming the verdict pattern.
        boolean hasNote = after.stream().anyMatch(m -> m instanceof SystemMessage
                && m.getText().contains("agent attempted `tool_alpha` 4 time(s)")
                && m.getText().contains("REJECT_GENERIC_ERROR"));
        assertThat(hasNote).isTrue();

        ArgumentCaptor<OutputBlock> emitted = ArgumentCaptor.forClass(OutputBlock.class);
        verify(outputSink).emit(eq("s"), emitted.capture());
        assertThat(((TextBlock) emitted.getValue()).content()).contains("1 retry-loop(s) collapsed");
    }

    @Test
    @DisplayName("Phase B + Phase A — duplicates folded first, then remaining over-cap triggers hard drop")
    void phaseB_thenPhaseA_combined() {
        // Tiny window so even after folding duplicates we still overflow → Phase A must fire.
        var a = advisor(200, 5, 1);

        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        msgs.add(new UserMessage("go"));
        // Older duplicate pair (will be folded by Phase B).
        msgs.add(toolCall("dup1", "introspect", "{\"k\":\"v\"}"));
        msgs.add(toolResponse("dup1", "introspect", bigPayload(300)));
        msgs.add(toolCall("dup2", "introspect", "{\"k\":\"v\"}"));
        msgs.add(toolResponse("dup2", "introspect", bigPayload(300)));
        // Older distinct pair (Phase A candidate to drop).
        msgs.add(toolCall("old", "other", "{\"q\":1}"));
        msgs.add(toolResponse("old", "other", bigPayload(300)));
        // Recent pinned pair (must survive both phases).
        msgs.add(toolCall("keep", "other", "{\"q\":2}"));
        msgs.add(toolResponse("keep", "other", bigPayload(300)));
        msgs.add(new UserMessage("status?"));

        ChatClientRequest out = a.before(request(msgs, "s"), chain);
        List<Message> after = out.prompt().getInstructions();

        // dup2 (latest duplicate) might or might not still be there depending on whether
        // Phase A also dropped it — we only assert that dup1 (oldest duplicate) is gone
        // and the pinned recent pair survives.
        assertThat(hasPair(after, "dup1")).isFalse();
        assertThat(hasPair(after, "keep")).isTrue();

        // Warning combines both reasons.
        ArgumentCaptor<OutputBlock> emitted = ArgumentCaptor.forClass(OutputBlock.class);
        verify(outputSink).emit(eq("s"), emitted.capture());
        String content = ((TextBlock) emitted.getValue()).content();
        assertThat(content).contains("duplicate(s) folded");
    }

    @Test
    @DisplayName("Phase E — pinned tool's pair survives Phase A drop even when oldest")
    void phaseE_pinSurvivesPhaseA() {
        registry.pin("pinned_tool");
        var a = advisor(300, 5, 1);   // keepLastTurns=1, Phase A active, Phase C off

        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        msgs.add(new UserMessage("go"));
        // Oldest pair targets a pinned tool — must NOT be dropped by Phase A.
        msgs.add(toolCall("pin1", "pinned_tool", "{\"k\":1}"));
        msgs.add(toolResponse("pin1", "pinned_tool", bigPayload(400)));
        msgs.add(toolCall("drop1", "other_tool", "{\"k\":1}"));
        msgs.add(toolResponse("drop1", "other_tool", bigPayload(400)));
        msgs.add(toolCall("keep", "other_tool", "{\"k\":2}"));
        msgs.add(toolResponse("keep", "other_tool", bigPayload(400)));

        ChatClientRequest out = a.before(request(msgs, "s"), chain);
        List<Message> after = out.prompt().getInstructions();

        // Pinned pair survives even though it's the oldest.
        assertThat(hasPair(after, "pin1")).isTrue();
        // Phase A dropped the unpinned middle pair instead.
        assertThat(hasPair(after, "drop1")).isFalse();
        // Recent pair always survives.
        assertThat(hasPair(after, "keep")).isTrue();
    }

    @Test
    @DisplayName("Phase E — pinned tool's response survives Phase C even when older than keep window")
    void phaseE_pinSurvivesPhaseC() {
        registry.register("pinned_tool", (args, data) -> "should never be applied");
        registry.pin("pinned_tool");
        // Window chosen so initial fixture overflows (compaction fires) but Phase C's
        // single elision is enough to bring us back under (Phase A stays quiet).
        var a = advisorWithPhaseC(380, 5, 1, true);

        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        msgs.add(new UserMessage("go"));
        // Old pair on the pinned tool with a long body — Phase C must leave it alone.
        msgs.add(toolCall("p1", "pinned_tool", "{}"));
        msgs.add(toolResponse("p1", "pinned_tool", bigPayload(500)));
        // Old non-pinned pair — Phase C elides this body. Distinct args from "keep" so
        // Phase B's duplicate-fold doesn't catch them first.
        msgs.add(toolCall("e1", "other_tool", "{\"q\":\"a\"}"));
        msgs.add(toolResponse("e1", "other_tool", bigPayload(500)));
        // Newest pair (smaller body so Phase A has no reason to fire) — inside the keep window.
        msgs.add(toolCall("keep", "other_tool", "{\"q\":\"b\"}"));
        msgs.add(toolResponse("keep", "other_tool", bigPayload(100)));

        ChatClientRequest out = a.before(request(msgs, "s"), chain);
        List<Message> after = out.prompt().getInstructions();

        // All three pairs still present — Phase C only shrinks, doesn't drop.
        assertThat(hasPair(after, "p1")).isTrue();
        assertThat(hasPair(after, "e1")).isTrue();
        assertThat(hasPair(after, "keep")).isTrue();

        // Pinned pair's response body is unchanged verbatim.
        String p1Data = ((ToolResponseMessage) after.get(3)).getResponses().get(0).responseData();
        assertThat(p1Data).hasSize(500);
        // Non-pinned older pair was elided (default elision on).
        String e1Data = ((ToolResponseMessage) after.get(5)).getResponses().get(0).responseData();
        assertThat(e1Data).contains("elided by P24-C");
    }

    @Test
    @DisplayName("Phase C — registered tool summary applied to older response, warning notes count")
    void phaseC_registeredSummary_appliedBeforePhaseA() {
        registry.register("introspect", (args, data) -> "schema: 47 entries, 23 of kind A");
        var a = advisorWithPhaseC(300, 5, /* keepLast */ 1, /* defaultElision */ false);

        // Three identical-tool but distinct-args calls — Phase B doesn't fold them (distinct args)
        // but Phase C should summarise the two oldest responses (older than keep-last=1).
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        msgs.add(new UserMessage("show me schema"));
        msgs.add(toolCall("c1", "introspect", "{\"t\":\"X\"}"));
        msgs.add(toolResponse("c1", "introspect", bigPayload(400)));
        msgs.add(toolCall("c2", "introspect", "{\"t\":\"Y\"}"));
        msgs.add(toolResponse("c2", "introspect", bigPayload(400)));
        msgs.add(toolCall("c3", "introspect", "{\"t\":\"Z\"}"));
        msgs.add(toolResponse("c3", "introspect", bigPayload(400)));

        ChatClientRequest out = a.before(request(msgs, "s"), chain);
        List<Message> after = out.prompt().getInstructions();

        // All three pairs still present (Phase C doesn't drop, just shrinks).
        assertThat(hasPair(after, "c1")).isTrue();
        assertThat(hasPair(after, "c2")).isTrue();
        assertThat(hasPair(after, "c3")).isTrue();

        // c3 (newest, in keep window) still has the verbatim 400-char payload.
        String c3Body = ((ToolResponseMessage) after.get(7)).getResponses().get(0).responseData();
        assertThat(c3Body).hasSize(400);
        // c1 + c2 bodies replaced with the registered summary.
        String c1Body = ((ToolResponseMessage) after.get(3)).getResponses().get(0).responseData();
        assertThat(c1Body).startsWith("schema:");

        ArgumentCaptor<OutputBlock> emitted = ArgumentCaptor.forClass(OutputBlock.class);
        verify(outputSink).emit(eq("s"), emitted.capture());
        assertThat(((TextBlock) emitted.getValue()).content()).contains("2 tool response(s) summarised");
    }

    @Test
    @DisplayName("Phase D — summariser called when over cap after Phase A, summarised range replaced with SystemMessage")
    void phaseD_summarisesOldestTurns() {
        // Tight cap so even after no Phase A/B reduction is possible (no tool pairs)
        // we still overflow → Phase D must fire.
        var a = advisorWithPhaseD(200, 5, 1, /* summarisationKeepLastTurns */ 2);

        // 6 user/assistant turns, no tool pairs (so Phase A has nothing to drop).
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        for (int i = 1; i <= 6; i++) {
            msgs.add(new UserMessage("user-" + i + " " + bigPayload(100)));
            msgs.add(new AssistantMessage("assistant-" + i + " " + bigPayload(100)));
        }

        when(summaryRepository.findBySessionId("s")).thenReturn(java.util.Optional.empty());
        when(summariser.summarise(eq(""), any())).thenReturn(java.util.Optional.of("rolling summary of turns 1-4"));

        ChatClientRequest out = a.before(request(msgs, "s"), chain);
        List<Message> after = out.prompt().getInstructions();

        // Persona at head, then injected summary, then last 2 user messages + their assistants.
        assertThat(after.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(after.get(0).getText()).isEqualTo("persona");
        assertThat(after.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(after.get(1).getText()).contains("Conversation summary so far:");
        assertThat(after.get(1).getText()).contains("rolling summary of turns 1-4");

        // The last 2 user turns are preserved verbatim (user-5 + asst-5 + user-6 + asst-6 = 4 messages).
        long userCount = after.stream().filter(m -> m instanceof UserMessage).count();
        assertThat(userCount).isEqualTo(2);
        boolean hasUser5 = after.stream().anyMatch(m -> m instanceof UserMessage
                && m.getText().startsWith("user-5"));
        boolean hasUser6 = after.stream().anyMatch(m -> m instanceof UserMessage
                && m.getText().startsWith("user-6"));
        assertThat(hasUser5).isTrue();
        assertThat(hasUser6).isTrue();

        // Summary persisted under the right session key.
        verify(summaryRepository).upsertSummary(eq("s"), eq("rolling summary of turns 1-4"));

        // Warning OutputBlock mentions the messages-summarised count.
        ArgumentCaptor<OutputBlock> emitted = ArgumentCaptor.forClass(OutputBlock.class);
        verify(outputSink).emit(eq("s"), emitted.capture());
        TextBlock block = (TextBlock) emitted.getValue();
        assertThat(block.content()).contains("older message(s) summarised");
    }

    @Test
    @DisplayName("Phase D — monotonicity: previous summary fed back into next pass")
    void phaseD_monotonicityPreservedAcrossPasses() {
        var a = advisorWithPhaseD(200, 5, 1, 2);

        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        for (int i = 1; i <= 5; i++) {
            msgs.add(new UserMessage("u" + i + " " + bigPayload(100)));
            msgs.add(new AssistantMessage("a" + i + " " + bigPayload(100)));
        }

        when(summaryRepository.findBySessionId("s")).thenReturn(java.util.Optional.of("PRIOR_SUMMARY_TOKEN"));
        when(summariser.summarise(eq("PRIOR_SUMMARY_TOKEN"), any()))
                .thenReturn(java.util.Optional.of("PRIOR_SUMMARY_TOKEN + new facts"));

        a.before(request(msgs, "s"), chain);

        // Verify the prior summary was passed to the summariser (monotonicity).
        verify(summariser).summarise(eq("PRIOR_SUMMARY_TOKEN"), any());
        verify(summaryRepository).upsertSummary(eq("s"), eq("PRIOR_SUMMARY_TOKEN + new facts"));
    }

    @Test
    @DisplayName("Phase D — summariser returns empty (LLM failure) → no DB write, fall-through")
    void phaseD_summariserFailure_doesNotPersist() {
        var a = advisorWithPhaseD(200, 5, 1, 2);

        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        for (int i = 1; i <= 5; i++) {
            msgs.add(new UserMessage("u" + i + " " + bigPayload(100)));
            msgs.add(new AssistantMessage("a" + i + " " + bigPayload(100)));
        }

        when(summaryRepository.findBySessionId("s")).thenReturn(java.util.Optional.empty());
        when(summariser.summarise(any(), any())).thenReturn(java.util.Optional.empty());

        a.before(request(msgs, "s"), chain);

        verify(summaryRepository, org.mockito.Mockito.never()).upsertSummary(any(), any());
    }

    @Test
    @DisplayName("Phase D — too few user messages to leave a keep-window → no-op")
    void phaseD_tooFewTurns_skips() {
        var a = advisorWithPhaseD(50, 5, 1, /* keepLast */ 4);

        // Only 3 user messages — less than keepLast=4, so nothing to summarise.
        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        for (int i = 1; i <= 3; i++) {
            msgs.add(new UserMessage("u" + i + " " + bigPayload(100)));
            msgs.add(new AssistantMessage("a" + i + " " + bigPayload(100)));
        }

        a.before(request(msgs, "s"), chain);

        verify(summariser, org.mockito.Mockito.never()).summarise(any(), any());
        verify(summaryRepository, org.mockito.Mockito.never()).upsertSummary(any(), any());
    }

    @Test
    @DisplayName("Phase D — under cap → not triggered, summariser untouched")
    void phaseD_underCap_notTriggered() {
        var a = advisorWithPhaseD(10_000, 5, 1, 2);

        List<Message> msgs = new ArrayList<>();
        msgs.add(new SystemMessage("persona"));
        for (int i = 1; i <= 5; i++) {
            msgs.add(new UserMessage("u" + i));
            msgs.add(new AssistantMessage("a" + i));
        }

        a.before(request(msgs, "s"), chain);

        verify(summariser, org.mockito.Mockito.never()).summarise(any(), any());
    }

    @Test
    @DisplayName("disabled — pass-through even when over cap")
    void disabled_passesThrough() {
        var a = new ContextCompactionAdvisor(
                tokenEstimator, modelRepository, outputSink, summariser, summaryRepository, registry,
                /* enabled */ false, 100, 5, 0,
                /* summarisationEnabled */ false, 4,
                /* toolSummariesEnabled */ false, false);

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
