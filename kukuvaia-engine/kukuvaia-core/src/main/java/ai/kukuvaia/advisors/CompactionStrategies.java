package ai.kukuvaia.advisors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * Phase B compaction strategies for {@link ContextCompactionAdvisor}: fold duplicate tool
 * calls and collapse retry loops. Pure functions over the message list — no Spring graph,
 * no mutation of the input list, safe to call repeatedly.
 *
 * <p><b>Pair invariant.</b> Both strategies operate on complete tool-call pairs (assistant-
 * with-tool_calls + the immediately-following {@link ToolResponseMessage}s) and replace
 * entire pairs as units. Splitting a pair would leave a tool_use without its tool_result,
 * which providers reject.
 *
 * <p><b>Order of strategies.</b> The advisor calls {@link #collapseRetryLoops} BEFORE
 * {@link #foldDuplicateToolCalls} so verdict information from failed attempts is captured
 * in the synthetic note before identical-arg pairs would otherwise be flattened to
 * duplicate-pointer notes.
 */
final class CompactionStrategies {

    private static final Logger log = LoggerFactory.getLogger(CompactionStrategies.class);

    /**
     * Jackson mapper configured to sort map keys when serializing — gives us a canonical
     * string form of an arguments JSON so {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}}
     * group together when looking for duplicates.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    /**
     * Cap on per-tool summary output. A registered summariser that returns something longer
     * gets truncated — the point of Phase C is to fit, not to substitute one bloat for another.
     */
    private static final int MAX_SUMMARY_CHARS = 500;

    /**
     * Minimum response length below which default elision is skipped — small responses
     * cost nothing to keep verbatim and the elision marker itself would be comparable
     * in size.
     */
    private static final int MIN_ELIDE_CHARS = 200;

    /**
     * Tokens worth pulling out of a failed tool response into the retry-loop synthetic note.
     * Covers L1 anti-pattern verdicts, L2 reject verdicts, Spring AI's tool-execution error
     * prefix, and the kukuvaia/sl-validation domain invariant exception.
     */
    private static final Pattern VERDICT_PATTERN = Pattern.compile(
            "REJECT_[A-Z_]+|ANTI_PATTERN|InvariantViolationException|Error calling tool");

    /** Two pairs are "similar" when their top-level argument keys overlap by at least this fraction. */
    private static final double SIMILAR_ARGS_KEY_OVERLAP = 0.80;

    private CompactionStrategies() {}

    record StrategyResult(List<Message> messages, int count) {}

    /**
     * Fold duplicate tool-call pairs: same tool name + structurally identical arguments.
     * The most recent copy is kept verbatim; each older copy is replaced with one
     * {@link SystemMessage} pointer. Pairs whose assistant message holds more than one
     * tool_call are skipped — production traffic is overwhelmingly single-call pairs and
     * multi-call grouping would conflate distinct tool intents.
     */
    static StrategyResult foldDuplicateToolCalls(List<Message> messages) {
        List<Pair> pairs = collectPairs(messages);
        if (pairs.size() < 2) return new StrategyResult(messages, 0);

        Map<String, List<Integer>> grouped = new LinkedHashMap<>();
        for (int p = 0; p < pairs.size(); p++) {
            Pair pair = pairs.get(p);
            if (pair.toolName() == null) continue;
            String key = pair.toolName() + "|" + pair.canonicalArgs();
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        List<FoldAction> actions = new ArrayList<>();
        int folded = 0;
        for (List<Integer> group : grouped.values()) {
            if (group.size() < 2) continue;
            int latestPairIdx = group.get(group.size() - 1);
            Pair latest = pairs.get(latestPairIdx);
            for (int k = 0; k < group.size() - 1; k++) {
                Pair older = pairs.get(group.get(k));
                String note = String.format(
                        "[compacted: earlier call to `%s` with identical arguments — "
                                + "response identical to a later call shown below]",
                        latest.toolName());
                actions.add(new FoldAction(older.startIdx(), older.endIdx(), note));
                folded++;
            }
        }

        if (actions.isEmpty()) return new StrategyResult(messages, 0);
        return new StrategyResult(applyFolds(messages, actions), folded);
    }

    /**
     * Collapse a run of consecutive failed pairs (same tool, similar args, all failure
     * responses) into one synthetic note + the most recent attempt kept verbatim. The
     * note names verdict tokens and their counts so the LLM can self-correct on the
     * retained verbatim attempt without re-reading the failed runs.
     */
    static StrategyResult collapseRetryLoops(List<Message> messages) {
        List<Pair> pairs = collectPairs(messages);
        if (pairs.size() < 2) return new StrategyResult(messages, 0);

        List<FoldAction> actions = new ArrayList<>();
        int collapsedRuns = 0;

        int i = 0;
        while (i < pairs.size()) {
            Pair start = pairs.get(i);
            if (start.toolName() == null || !start.isFailure()) {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < pairs.size()) {
                Pair next = pairs.get(j);
                Pair prev = pairs.get(j - 1);
                if (next.toolName() == null || !next.isFailure()) break;
                if (!start.toolName().equals(next.toolName())) break;
                if (argsKeyOverlap(start.argsKeys(), next.argsKeys()) < SIMILAR_ARGS_KEY_OVERLAP) break;
                // Run must be CONSECUTIVE in the message list — any intervening message ends the run.
                if (next.startIdx() != prev.endIdx()) break;
                j++;
            }
            int runLength = j - i;
            if (runLength >= 2) {
                Map<String, Integer> verdicts = new LinkedHashMap<>();
                for (int k = i; k < j - 1; k++) {
                    for (String v : pairs.get(k).verdicts()) {
                        verdicts.merge(v, 1, Integer::sum);
                    }
                }
                Pair firstFolded = pairs.get(i);
                Pair lastFolded = pairs.get(j - 2);
                String note = buildRetryNote(start.toolName(), runLength, verdicts);
                actions.add(new FoldAction(firstFolded.startIdx(), lastFolded.endIdx(), note));
                collapsedRuns++;
                i = j;
            } else {
                i++;
            }
        }

        if (actions.isEmpty()) return new StrategyResult(messages, 0);
        return new StrategyResult(applyFolds(messages, actions), collapsedRuns);
    }

    private static String buildRetryNote(String toolName, int attempts, Map<String, Integer> verdicts) {
        StringBuilder sb = new StringBuilder();
        sb.append("[compacted: agent attempted `").append(toolName).append("` ")
                .append(attempts).append(" time(s); earlier ").append(attempts - 1)
                .append(" failed.");
        if (!verdicts.isEmpty()) {
            sb.append(" Verdicts: ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : verdicts.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey());
                if (e.getValue() > 1) sb.append("×").append(e.getValue());
                first = false;
            }
            sb.append(".");
        }
        sb.append(" Most recent attempt retained below.]");
        return sb.toString();
    }

    /**
     * Apply a list of fold actions to the message list. Each action replaces messages
     * in {@code [from, to)} with a single {@link SystemMessage} carrying the note text.
     * Actions are processed in ascending {@code from} order to keep cursor arithmetic simple.
     */
    private static List<Message> applyFolds(List<Message> messages, List<FoldAction> actions) {
        actions.sort(Comparator.comparingInt(FoldAction::messageFromIdx));
        List<Message> result = new ArrayList<>(messages.size());
        int cursor = 0;
        for (FoldAction a : actions) {
            for (int k = cursor; k < a.messageFromIdx(); k++) result.add(messages.get(k));
            result.add(new SystemMessage(a.noteText()));
            cursor = a.messageToIdx();
        }
        for (int k = cursor; k < messages.size(); k++) result.add(messages.get(k));
        return result;
    }

    /**
     * Walk the message list and collect tool-call pairs. A pair spans the
     * {@link AssistantMessage} with tool_calls plus any immediately-following
     * {@link ToolResponseMessage}s. Non-pair messages between pairs break consecutiveness
     * (used by retry-loop detection) but otherwise leave the surrounding structure alone.
     */
    private static List<Pair> collectPairs(List<Message> messages) {
        List<Pair> out = new ArrayList<>();
        int i = 0;
        while (i < messages.size()) {
            Message m = messages.get(i);
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                int j = i + 1;
                while (j < messages.size() && messages.get(j) instanceof ToolResponseMessage) {
                    j++;
                }
                out.add(buildPair(messages, i, j, am));
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    private static Pair buildPair(List<Message> messages, int startIdx, int endIdx, AssistantMessage am) {
        List<AssistantMessage.ToolCall> calls = am.getToolCalls();
        String toolName = null;
        String canonicalArgs = "";
        Set<String> argsKeys = Set.of();
        if (calls != null && calls.size() == 1) {
            AssistantMessage.ToolCall tc = calls.get(0);
            toolName = tc.name();
            String rawArgs = tc.arguments() != null ? tc.arguments() : "{}";
            try {
                JsonNode node = MAPPER.readTree(rawArgs);
                // Route through plain Java types so SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS
                // actually sorts. ObjectNode fields are not sorted by that feature directly.
                Object plain = MAPPER.convertValue(node, Object.class);
                canonicalArgs = MAPPER.writeValueAsString(plain);
                if (node.isObject()) {
                    argsKeys = new HashSet<>();
                    node.fieldNames().forEachRemaining(argsKeys::add);
                }
            } catch (Exception e) {
                canonicalArgs = rawArgs;
            }
        }

        boolean isFailure = false;
        List<String> verdicts = new ArrayList<>();
        for (int k = startIdx + 1; k < endIdx; k++) {
            if (messages.get(k) instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                    String data = r.responseData();
                    if (data == null) continue;
                    Matcher matcher = VERDICT_PATTERN.matcher(data);
                    while (matcher.find()) {
                        isFailure = true;
                        verdicts.add(matcher.group());
                    }
                }
            }
        }
        return new Pair(startIdx, endIdx, toolName, canonicalArgs, argsKeys, isFailure, verdicts);
    }

    private static double argsKeyOverlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersect = 0;
        for (String k : a) if (b.contains(k)) intersect++;
        int union = a.size() + b.size() - intersect;
        return (double) intersect / (double) union;
    }

    /**
     * Phase C — per-tool response summarisation. Walks the message list and rewrites the
     * {@link ToolResponseMessage}s that sit OUTSIDE the last {@code keepLastPairs} tool-call
     * pairs. Each contained {@link ToolResponseMessage.ToolResponse} is offered to a
     * registered {@link ToolCompactSummary} (looked up by tool name); if no summary is
     * registered AND {@code defaultElisionEnabled} is true AND the payload exceeds
     * {@link #MIN_ELIDE_CHARS}, the body is replaced with a one-line elision marker.
     *
     * <p>Pairs are not deleted — only their response bodies shrink. This preserves the
     * tool_call / tool_response structure that the provider requires while removing the
     * bulk of older payloads (e.g. a 50 KB schema dump becomes a one-line summary).
     *
     * <p>{@code count} on the returned result is the number of individual
     * {@link ToolResponseMessage.ToolResponse} entries actually rewritten (not the number
     * of {@link ToolResponseMessage} envelopes).
     */
    static StrategyResult summariseOldToolResponses(
            List<Message> messages,
            ToolCompactionRegistry registry,
            int keepLastPairs,
            boolean defaultElisionEnabled) {
        if (messages == null || messages.isEmpty()) return new StrategyResult(messages, 0);

        List<Pair> pairs = collectPairs(messages);
        if (pairs.size() <= keepLastPairs) return new StrategyResult(messages, 0);

        // Cutoff: the start index of the first recent (keep-window) pair. Anything before is "old".
        int cutoffMessageIdx = pairs.get(pairs.size() - keepLastPairs).startIdx();

        // Map tool_call id → (tool name, canonical args) so we can pair a ToolResponse with
        // the originating call without scanning the message list per response.
        Map<String, String[]> callIdToCall = buildCallIndex(messages);

        List<Message> result = new ArrayList<>(messages);
        int summarised = 0;
        for (int i = 0; i < cutoffMessageIdx; i++) {
            if (result.get(i) instanceof ToolResponseMessage trm) {
                CompactResult cr = compactToolResponseMessage(trm, callIdToCall, registry, defaultElisionEnabled);
                if (cr.changedCount > 0) {
                    result.set(i, cr.message);
                    summarised += cr.changedCount;
                }
            }
        }
        return new StrategyResult(result, summarised);
    }

    private record CompactResult(ToolResponseMessage message, int changedCount) {}

    private static CompactResult compactToolResponseMessage(
            ToolResponseMessage trm,
            Map<String, String[]> callIdToCall,
            ToolCompactionRegistry registry,
            boolean defaultElisionEnabled) {
        List<ToolResponseMessage.ToolResponse> originals = trm.getResponses();
        List<ToolResponseMessage.ToolResponse> rewritten = new ArrayList<>(originals.size());
        int changed = 0;
        for (ToolResponseMessage.ToolResponse r : originals) {
            String data = r.responseData();
            String toolName = r.name();
            // Phase E: pinned tools survive Phase C compaction.
            if (registry != null && registry.isPinned(toolName)) {
                rewritten.add(r);
                continue;
            }
            if (data == null || data.isBlank()) {
                rewritten.add(r);
                continue;
            }
            String[] call = callIdToCall.get(r.id());
            String args = call != null ? call[1] : "";

            String replacement = tryRegisteredSummary(registry, toolName, args, data);
            if (replacement == null && defaultElisionEnabled && data.length() >= MIN_ELIDE_CHARS) {
                replacement = elisionMarker(toolName, args, data.length());
            }
            if (replacement != null) {
                rewritten.add(new ToolResponseMessage.ToolResponse(r.id(), r.name(), replacement));
                changed++;
            } else {
                rewritten.add(r);
            }
        }
        if (changed == 0) return new CompactResult(trm, 0);
        return new CompactResult(ToolResponseMessage.builder().responses(rewritten).build(), changed);
    }

    /**
     * Apply a registered summary, defensively — return {@code null} on any exception so
     * the caller falls through to default elision. Truncate over-long results to keep
     * Phase C from accidentally substituting one bloat for another.
     */
    private static String tryRegisteredSummary(
            ToolCompactionRegistry registry, String toolName, String args, String data) {
        if (registry == null || toolName == null) return null;
        Optional<ToolCompactSummary> fn = registry.get(toolName);
        if (fn.isEmpty()) return null;
        try {
            String s = fn.get().summarise(args, data);
            if (s == null || s.isBlank()) return null;
            return s.length() <= MAX_SUMMARY_CHARS ? s : s.substring(0, MAX_SUMMARY_CHARS) + "...";
        } catch (Exception e) {
            log.debug("ToolCompactSummary for '{}' threw — falling back to elision: {}",
                    toolName, e.getMessage());
            return null;
        }
    }

    private static String elisionMarker(String toolName, String args, int originalLength) {
        Set<String> keys = topLevelKeysOf(args);
        return String.format(
                "[response elided by P24-C: tool=%s, arg_keys=%s, original_length=%d]",
                toolName == null ? "unknown" : toolName,
                keys.isEmpty() ? "(none)" : keys,
                originalLength);
    }

    private static Set<String> topLevelKeysOf(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Set.of();
        try {
            JsonNode node = MAPPER.readTree(argsJson);
            if (!node.isObject()) return Set.of();
            Set<String> keys = new HashSet<>();
            node.fieldNames().forEachRemaining(keys::add);
            return keys;
        } catch (Exception e) {
            return Set.of();
        }
    }

    /**
     * Build a {@code id → {toolName, args}} index from every assistant tool_call in the
     * message list. Walked once before Phase C does its per-response work — keeps the
     * inner loop O(1) per response.
     */
    private static Map<String, String[]> buildCallIndex(List<Message> messages) {
        Map<String, String[]> idx = new HashMap<>();
        for (Message m : messages) {
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    idx.put(tc.id(), new String[] { tc.name(), tc.arguments() == null ? "" : tc.arguments() });
                }
            }
        }
        return idx;
    }

    private record Pair(
            int startIdx,
            int endIdx,
            String toolName,
            String canonicalArgs,
            Set<String> argsKeys,
            boolean isFailure,
            List<String> verdicts) {}

    private record FoldAction(int messageFromIdx, int messageToIdx, String noteText) {}
}
