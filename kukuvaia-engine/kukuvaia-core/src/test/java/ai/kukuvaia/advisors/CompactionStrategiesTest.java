package ai.kukuvaia.advisors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

@DisplayName("CompactionStrategies — Phase B + Phase C strategies over the message list")
class CompactionStrategiesTest {

    private static AssistantMessage toolCall(String callId, String toolName, String argsJson) {
        return AssistantMessage.builder()
                .content("calling " + toolName)
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", toolName, argsJson)))
                .build();
    }

    private static AssistantMessage twoToolCalls(String id1, String name1, String args1, String id2, String name2, String args2) {
        return AssistantMessage.builder()
                .content("calling tools")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall(id1, "function", name1, args1),
                        new AssistantMessage.ToolCall(id2, "function", name2, args2)))
                .build();
    }

    private static ToolResponseMessage toolResponse(String callId, String toolName, String payload) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, toolName, payload)))
                .build();
    }

    @Nested
    @DisplayName("foldDuplicateToolCalls")
    class FoldDuplicates {

        @Test
        @DisplayName("four identical calls — keeps latest verbatim, others become pointer SystemMessages")
        void fourIdentical_keepsLatest() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("persona"));
            msgs.add(new UserMessage("create a rule"));
            for (int i = 1; i <= 4; i++) {
                msgs.add(toolCall("c" + i, "introspect_section_schema", "{\"template\":\"X\"}"));
                msgs.add(toolResponse("c" + i, "introspect_section_schema", "big-payload"));
            }

            var res = CompactionStrategies.foldDuplicateToolCalls(msgs);

            assertThat(res.count()).isEqualTo(3);
            List<Message> after = res.messages();
            // Latest call (c4) and its response must still be present verbatim as a pair.
            boolean hasC4 = after.stream().anyMatch(m -> m instanceof AssistantMessage am
                    && am.hasToolCalls()
                    && am.getToolCalls().stream().anyMatch(tc -> "c4".equals(tc.id())));
            assertThat(hasC4).isTrue();
            // c1, c2, c3 pairs replaced by pointer SystemMessages with the canonical note text.
            for (String old : List.of("c1", "c2", "c3")) {
                boolean stillThere = after.stream().anyMatch(m -> m instanceof AssistantMessage am
                        && am.hasToolCalls()
                        && am.getToolCalls().stream().anyMatch(tc -> old.equals(tc.id())));
                assertThat(stillThere).as("older pair %s should be folded", old).isFalse();
            }
            long pointerCount = after.stream()
                    .filter(m -> m instanceof SystemMessage)
                    .filter(m -> m.getText().contains("identical arguments"))
                    .count();
            assertThat(pointerCount).isEqualTo(3);
        }

        @Test
        @DisplayName("different args — no folding even with same tool name")
        void differentArgs_notFolded() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("persona"));
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "introspect", "{\"template\":\"X\"}"));
            msgs.add(toolResponse("c1", "introspect", "ok"));
            msgs.add(toolCall("c2", "introspect", "{\"template\":\"Y\"}"));
            msgs.add(toolResponse("c2", "introspect", "ok"));

            var res = CompactionStrategies.foldDuplicateToolCalls(msgs);

            assertThat(res.count()).isZero();
            assertThat(res.messages()).hasSameSizeAs(msgs);
        }

        @Test
        @DisplayName("args differ only in key order — canonicalised, treated as duplicate")
        void argsKeyOrderInsensitive() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("persona"));
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "introspect", "{\"a\":1,\"b\":2}"));
            msgs.add(toolResponse("c1", "introspect", "ok"));
            msgs.add(toolCall("c2", "introspect", "{\"b\":2,\"a\":1}"));
            msgs.add(toolResponse("c2", "introspect", "ok"));

            var res = CompactionStrategies.foldDuplicateToolCalls(msgs);

            assertThat(res.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("multi-tool-call assistant message — skipped, never folded")
        void multiToolCallPair_skipped() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("persona"));
            msgs.add(new UserMessage("go"));
            // Two pairs that look duplicate but each carries TWO tool calls — strategy must skip.
            msgs.add(twoToolCalls("a1", "x", "{}", "a2", "y", "{}"));
            msgs.add(toolResponse("a1", "x", "ok"));
            msgs.add(toolResponse("a2", "y", "ok"));
            msgs.add(twoToolCalls("b1", "x", "{}", "b2", "y", "{}"));
            msgs.add(toolResponse("b1", "x", "ok"));
            msgs.add(toolResponse("b2", "y", "ok"));

            var res = CompactionStrategies.foldDuplicateToolCalls(msgs);

            assertThat(res.count()).isZero();
            assertThat(res.messages()).hasSameSizeAs(msgs);
        }

        @Test
        @DisplayName("empty input — passes through")
        void emptyInput_passesThrough() {
            var res = CompactionStrategies.foldDuplicateToolCalls(List.of());
            assertThat(res.count()).isZero();
            assertThat(res.messages()).isEmpty();
        }
    }

    @Nested
    @DisplayName("collapseRetryLoops")
    class CollapseRetries {

        @Test
        @DisplayName("2026-05-13 replay — 4× create_rule with L2 reject collapses to one synthetic note")
        void retryReplay_collapsesToNote() {
            String rejectPayload = "InvariantViolationException: REJECT_PREREQUISITE_NEVER_MATCHED — "
                    + "histogram [Lesson=12, Page=8]";
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("persona"));
            msgs.add(new UserMessage("create a rule"));
            for (int i = 1; i <= 4; i++) {
                msgs.add(toolCall("c" + i, "create_rule", "{\"outlineId\":\"wrong-uuid\"}"));
                msgs.add(toolResponse("c" + i, "create_rule", rejectPayload));
            }

            var res = CompactionStrategies.collapseRetryLoops(msgs);

            assertThat(res.count()).isEqualTo(1);   // one run collapsed
            List<Message> after = res.messages();
            // Most recent attempt (c4) must remain verbatim.
            boolean hasC4 = after.stream().anyMatch(m -> m instanceof AssistantMessage am
                    && am.hasToolCalls()
                    && am.getToolCalls().stream().anyMatch(tc -> "c4".equals(tc.id())));
            assertThat(hasC4).isTrue();
            // Older attempts (c1, c2, c3) folded out.
            for (String old : List.of("c1", "c2", "c3")) {
                boolean stillThere = after.stream().anyMatch(m -> m instanceof AssistantMessage am
                        && am.hasToolCalls()
                        && am.getToolCalls().stream().anyMatch(tc -> old.equals(tc.id())));
                assertThat(stillThere).as("attempt %s should be folded", old).isFalse();
            }
            // One synthetic note naming the verdict + count.
            String note = after.stream()
                    .filter(m -> m instanceof SystemMessage)
                    .map(Message::getText)
                    .filter(t -> t.startsWith("[compacted: agent attempted"))
                    .findFirst()
                    .orElseThrow();
            assertThat(note).contains("create_rule");
            assertThat(note).contains("4 time(s)");
            assertThat(note).contains("REJECT_PREREQUISITE_NEVER_MATCHED");
        }

        @Test
        @DisplayName("interrupted by a successful response — no collapse")
        void interruptedRun_notCollapsed() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("persona"));
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "create_rule", "{\"x\":1}"));
            msgs.add(toolResponse("c1", "create_rule", "REJECT_PREREQUISITE_NEVER_MATCHED"));
            msgs.add(toolCall("c2", "create_rule", "{\"x\":1}"));
            msgs.add(toolResponse("c2", "create_rule", "success"));    // interrupts the failure run
            msgs.add(toolCall("c3", "create_rule", "{\"x\":1}"));
            msgs.add(toolResponse("c3", "create_rule", "REJECT_PREREQUISITE_NEVER_MATCHED"));

            var res = CompactionStrategies.collapseRetryLoops(msgs);

            assertThat(res.count()).isZero();
            assertThat(res.messages()).hasSameSizeAs(msgs);
        }

        @Test
        @DisplayName("non-consecutive failures with intervening user message — no collapse")
        void nonConsecutiveFailures_notCollapsed() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("persona"));
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "create_rule", "{\"x\":1}"));
            msgs.add(toolResponse("c1", "create_rule", "Error calling tool: bad outline"));
            msgs.add(new UserMessage("try again with outline-Y"));
            msgs.add(toolCall("c2", "create_rule", "{\"x\":1}"));
            msgs.add(toolResponse("c2", "create_rule", "Error calling tool: still bad"));

            var res = CompactionStrategies.collapseRetryLoops(msgs);

            // UserMessage between the two pairs breaks consecutiveness.
            assertThat(res.count()).isZero();
        }

        @Test
        @DisplayName("similar but not identical args — collapses when keys overlap ≥80 %")
        void similarArgs_collapses() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("persona"));
            msgs.add(new UserMessage("go"));
            // Both args share keys "outlineId" and "ruleJson" (100 % key overlap, only values differ).
            msgs.add(toolCall("c1", "create_rule", "{\"outlineId\":\"u1\",\"ruleJson\":\"v1\"}"));
            msgs.add(toolResponse("c1", "create_rule", "ANTI_PATTERN: bad var ref"));
            msgs.add(toolCall("c2", "create_rule", "{\"outlineId\":\"u2\",\"ruleJson\":\"v2\"}"));
            msgs.add(toolResponse("c2", "create_rule", "ANTI_PATTERN: bad var ref"));

            var res = CompactionStrategies.collapseRetryLoops(msgs);

            assertThat(res.count()).isEqualTo(1);
            String note = res.messages().stream()
                    .filter(m -> m instanceof SystemMessage)
                    .map(Message::getText)
                    .filter(t -> t.startsWith("[compacted:"))
                    .findFirst()
                    .orElseThrow();
            assertThat(note).contains("ANTI_PATTERN");
        }

        @Test
        @DisplayName("only one failed pair — no collapse (minimum run is 2)")
        void singleFailure_noCollapse() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("persona"));
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "x", "{}"));
            msgs.add(toolResponse("c1", "x", "Error calling tool: nope"));

            var res = CompactionStrategies.collapseRetryLoops(msgs);

            assertThat(res.count()).isZero();
            assertThat(res.messages()).hasSameSizeAs(msgs);
        }
    }

    @Nested
    @DisplayName("summariseOldToolResponses (Phase C)")
    class SummariseOldResponses {

        private final ToolCompactionRegistry registry = new ToolCompactionRegistry();

        @Test
        @DisplayName("registered summary replaces older response body, latest kept verbatim")
        void registeredSummary_appliedToOlder_keepsLatestVerbatim() {
            registry.register("introspect", (args, data) ->
                    "summary len=" + data.length());

            String bigBody = "x".repeat(800);
            List<Message> msgs = new ArrayList<>();
            msgs.add(new SystemMessage("persona"));
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "introspect", "{\"t\":\"X\"}"));
            msgs.add(toolResponse("c1", "introspect", bigBody));
            msgs.add(toolCall("c2", "introspect", "{\"t\":\"Y\"}"));
            msgs.add(toolResponse("c2", "introspect", bigBody));
            msgs.add(toolCall("c3", "introspect", "{\"t\":\"Z\"}"));
            msgs.add(toolResponse("c3", "introspect", bigBody));

            var res = CompactionStrategies.summariseOldToolResponses(msgs, registry, 1, false);

            assertThat(res.count()).isEqualTo(2);
            // c3 (newest) is the only one in the keep window — body untouched.
            String c3Data = ((ToolResponseMessage) res.messages().get(7))
                    .getResponses().get(0).responseData();
            assertThat(c3Data).hasSize(800);
            // c1 and c2 bodies replaced with the registered summary.
            String c1Data = ((ToolResponseMessage) res.messages().get(3))
                    .getResponses().get(0).responseData();
            assertThat(c1Data).startsWith("summary len=");
        }

        @Test
        @DisplayName("no registered summary + default elision off — body untouched")
        void noSummary_elisionOff_untouched() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "noop", "{}"));
            msgs.add(toolResponse("c1", "noop", "x".repeat(500)));
            msgs.add(toolCall("c2", "noop", "{}"));
            msgs.add(toolResponse("c2", "noop", "x".repeat(500)));

            var res = CompactionStrategies.summariseOldToolResponses(msgs, registry, 1, false);

            assertThat(res.count()).isZero();
            String c1Data = ((ToolResponseMessage) res.messages().get(2))
                    .getResponses().get(0).responseData();
            assertThat(c1Data).hasSize(500);
        }

        @Test
        @DisplayName("no registered summary + default elision on — body replaced with marker")
        void noSummary_elisionOn_elidesOld() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "noop", "{\"k\":\"v\"}"));
            msgs.add(toolResponse("c1", "noop", "x".repeat(500)));
            msgs.add(toolCall("c2", "noop", "{\"k\":\"v\"}"));
            msgs.add(toolResponse("c2", "noop", "x".repeat(500)));

            var res = CompactionStrategies.summariseOldToolResponses(msgs, registry, 1, true);

            assertThat(res.count()).isEqualTo(1);
            String c1Data = ((ToolResponseMessage) res.messages().get(2))
                    .getResponses().get(0).responseData();
            assertThat(c1Data).contains("elided by P24-C");
            assertThat(c1Data).contains("tool=noop");
            assertThat(c1Data).contains("original_length=500");
        }

        @Test
        @DisplayName("short responses below MIN_ELIDE_CHARS — not elided")
        void shortResponse_notElided() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "noop", "{}"));
            msgs.add(toolResponse("c1", "noop", "tiny"));   // 4 chars
            msgs.add(toolCall("c2", "noop", "{}"));
            msgs.add(toolResponse("c2", "noop", "tiny"));

            var res = CompactionStrategies.summariseOldToolResponses(msgs, registry, 1, true);

            assertThat(res.count()).isZero();
        }

        @Test
        @DisplayName("not enough pairs to exceed keep window — no-op")
        void underKeepWindow_noOp() {
            List<Message> msgs = new ArrayList<>();
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "introspect", "{}"));
            msgs.add(toolResponse("c1", "introspect", "x".repeat(800)));

            var res = CompactionStrategies.summariseOldToolResponses(msgs, registry, 1, true);

            assertThat(res.count()).isZero();
        }

        @Test
        @DisplayName("registered summary throws — falls back to default elision")
        void summaryThrows_fallsBackToElision() {
            registry.register("flaky", (args, data) -> { throw new RuntimeException("kaboom"); });

            List<Message> msgs = new ArrayList<>();
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "flaky", "{}"));
            msgs.add(toolResponse("c1", "flaky", "x".repeat(500)));
            msgs.add(toolCall("c2", "flaky", "{}"));
            msgs.add(toolResponse("c2", "flaky", "x".repeat(500)));

            var res = CompactionStrategies.summariseOldToolResponses(msgs, registry, 1, true);

            assertThat(res.count()).isEqualTo(1);
            String c1Data = ((ToolResponseMessage) res.messages().get(2))
                    .getResponses().get(0).responseData();
            assertThat(c1Data).contains("elided by P24-C");
        }

        @Test
        @DisplayName("over-long registered summary — truncated at 500 chars")
        void overLongSummary_truncated() {
            registry.register("verbose", (args, data) -> "y".repeat(2_000));

            List<Message> msgs = new ArrayList<>();
            msgs.add(new UserMessage("go"));
            msgs.add(toolCall("c1", "verbose", "{}"));
            msgs.add(toolResponse("c1", "verbose", "x".repeat(500)));
            msgs.add(toolCall("c2", "verbose", "{}"));
            msgs.add(toolResponse("c2", "verbose", "x".repeat(500)));

            var res = CompactionStrategies.summariseOldToolResponses(msgs, registry, 1, false);

            assertThat(res.count()).isEqualTo(1);
            String c1Data = ((ToolResponseMessage) res.messages().get(2))
                    .getResponses().get(0).responseData();
            assertThat(c1Data).hasSize(500 + 3);   // 500 'y's + "..." truncation marker
            assertThat(c1Data).endsWith("...");
        }
    }
}
