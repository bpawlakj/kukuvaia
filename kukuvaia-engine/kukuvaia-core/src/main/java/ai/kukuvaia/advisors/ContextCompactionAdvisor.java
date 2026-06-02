package ai.kukuvaia.advisors;

import ai.kukuvaia.memory.repository.ConversationSummaryRepository;
import ai.kukuvaia.output.SessionOutputSink;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.provider.service.ChatModelCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * P24 Context Compaction. Inspects every outbound LLM request and, when the estimated
 * token count would exceed the active model's context window minus a safety margin,
 * applies compaction strategies in order until the prompt fits.
 *
 * <p><b>Strategy ladder (Phases A + B + C + D shipped):</b>
 * <ol>
 *   <li><b>Phase B — retry-loop collapse:</b> consecutive failed pairs for the same tool
 *       (similar args) are merged into one synthetic note + the most recent attempt verbatim.
 *       Catches the common "retry loop with identical wrong args" pattern that otherwise
 *       overflows the window.</li>
 *   <li><b>Phase B — duplicate fold:</b> older tool-call pairs with the same tool + canonical
 *       args as a later pair are replaced with one-line {@link SystemMessage} pointers.</li>
 *   <li><b>Phase C — per-tool response summary:</b> tool-call pairs OUTSIDE the last
 *       {@code keepLastTurns} window have their response bodies replaced via the
 *       {@link ToolCompactionRegistry} (e.g. a 50 KB schema dump becomes one line).
 *       When no per-tool summary is registered, an optional default elision marker
 *       takes the body's place.</li>
 *   <li><b>Phase A — hard-cap drop:</b> oldest non-pinned tool-call pairs are dropped as
 *       units. Cheap, but loses content silently — Phase D below catches narrative bloat
 *       that A cannot touch (user/assistant text without tool calls).</li>
 *   <li><b>Phase D — LLM-driven summarisation (fallback):</b> if STILL over budget after
 *       A drops every eligible tool pair, the oldest user/assistant turns (outside the
 *       summarisation keep-last-turns window) are fed to a cheap worker model that emits
 *       a rolling summary. The summarised range is replaced with one {@code SystemMessage}
 *       carrying the new summary, and the summary is persisted to
 *       {@code kukuvaia.conversations.summary} so future turns can read it on resume.</li>
 * </ol>
 * Phase B runs only when the prompt is already over the hard cap; for under-cap prompts the
 * advisor is a no-op.
 *
 * <p><b>Phase E (provider-aware pinning):</b> Tools registered via
 * {@link ToolCompactionRegistry#pin(String)} have their pairs skipped by Phase A and their
 * response bodies skipped by Phase C — verbatim survival even when older than the keep
 * window. Per-model context windows are resolved from {@code ModelRepository}; a missing
 * {@code contextWindow} logs a one-time warning per model so operators can backfill.
 *
 * <p><b>TODO — P08 prompt-cache coordination:</b> when P08 ships, this advisor should
 * read cache-marker metadata from request options and treat those ranges as pinned (no
 * mutation inside a cached prefix, or the next request misses the cache on every call).
 * The pinning mechanism is in place; the cache-marker reader is the remaining work.
 *
 * <p><b>Pinning policy (never dropped or folded by Phase A):</b>
 * <ul>
 *   <li>All {@link SystemMessage} entries — persona/system prompt is the contract.</li>
 *   <li>The last {@code keepLastTurns} (default 2) tool-call pairs — the LLM needs to see
 *       what it just did to make the next decision.</li>
 *   <li>The most recent {@link UserMessage} — the operator's current request.</li>
 * </ul>
 * Phase B strategies always preserve the most recent copy/attempt verbatim; they never drop
 * a tool_call without its tool_response (pair invariant).
 *
 * <p>Mounted after {@code MessageChatMemoryAdvisor} (which has order {@code HIGHEST_PRECEDENCE
 * + 1000}) so the chat history is already injected into the request; our order is therefore
 * {@code HIGHEST_PRECEDENCE + 1100}.
 *
 * <p>Active-model resolution: reads {@link ModelRoutingAdvisor#CTX_ROUTED_MODEL} from the
 * request context. If routing did not set it (routing disabled, or no override fired), falls
 * back to {@code defaultWindowTokens} from config — conservative 200 000 by default, matching
 * Claude Opus 4.7 / Sonnet 4.6.
 */
@Component
public class ContextCompactionAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactionAdvisor.class);
    private static final String CTX_COMPACTION_APPLIED = "kukuvaia.context-compaction.applied";

    /**
     * Models we've already logged a "missing contextWindow" warning for. Rate-limits the
     * Phase E warn-on-null so a misconfigured model doesn't spam the log every request.
     * Cleared at process restart, which is fine — admins fix the config and bounce.
     */
    private final Set<String> warnedMissingWindowModels = ConcurrentHashMap.newKeySet();

    private final TokenEstimator tokenEstimator;
    private final ChatModelCache chatModelCache;
    private final SessionOutputSink outputSink;
    private final ConversationSummariser summariser;
    private final ConversationSummaryRepository summaryRepository;
    private final ToolCompactionRegistry toolCompactionRegistry;

    private final boolean enabled;
    private final int defaultWindowTokens;
    private final int safetyMarginTokens;
    private final int keepLastTurns;
    private final boolean summarisationEnabled;
    private final int summarisationKeepLastTurns;
    private final boolean toolSummariesEnabled;
    private final boolean toolSummariesDefaultElision;

    public ContextCompactionAdvisor(
            TokenEstimator tokenEstimator,
            ChatModelCache chatModelCache,
            SessionOutputSink outputSink,
            ConversationSummariser summariser,
            ConversationSummaryRepository summaryRepository,
            ToolCompactionRegistry toolCompactionRegistry,
            @Value("${kukuvaia.context-compaction.enabled:true}") boolean enabled,
            @Value("${kukuvaia.context-compaction.default-window-tokens:200000}") int defaultWindowTokens,
            @Value("${kukuvaia.context-compaction.safety-margin-tokens:5000}") int safetyMarginTokens,
            @Value("${kukuvaia.context-compaction.keep-last-turns:2}") int keepLastTurns,
            @Value("${kukuvaia.context-compaction.summarisation-enabled:true}") boolean summarisationEnabled,
            @Value("${kukuvaia.context-compaction.summarisation-keep-last-turns:4}") int summarisationKeepLastTurns,
            @Value("${kukuvaia.context-compaction.tool-summaries-enabled:true}") boolean toolSummariesEnabled,
            @Value("${kukuvaia.context-compaction.tool-summaries-default-elision:true}") boolean toolSummariesDefaultElision) {
        this.tokenEstimator = tokenEstimator;
        this.chatModelCache = chatModelCache;
        this.outputSink = outputSink;
        this.summariser = summariser;
        this.summaryRepository = summaryRepository;
        this.toolCompactionRegistry = toolCompactionRegistry;
        this.enabled = enabled;
        this.defaultWindowTokens = defaultWindowTokens;
        this.safetyMarginTokens = safetyMarginTokens;
        this.keepLastTurns = keepLastTurns;
        this.summarisationEnabled = summarisationEnabled;
        this.summarisationKeepLastTurns = Math.max(1, summarisationKeepLastTurns);
        this.toolSummariesEnabled = toolSummariesEnabled;
        this.toolSummariesDefaultElision = toolSummariesDefaultElision;
    }

    @Override
    public int getOrder() {
        // MessageChatMemoryAdvisor is HIGHEST_PRECEDENCE + 1000. We must run AFTER it so we
        // see the injected history.
        return Ordered.HIGHEST_PRECEDENCE + 1100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!enabled) return request;

        List<Message> messages = request.prompt().getInstructions();
        if (messages == null || messages.size() < 3) {
            // Nothing meaningful to compact — there's no history yet.
            return request;
        }

        int currentTokens = tokenEstimator.estimate(messages);
        int hardCap = resolveHardCap(request);

        if (currentTokens <= hardCap) {
            return request;
        }

        CompactionOutcome outcome = compact(messages, hardCap, sessionIdOrNull(request));
        int finalTokens = tokenEstimator.estimate(outcome.messages());

        log.warn("Context compaction fired: original={} tokens, final={} tokens, hardCap={}, "
                        + "duplicatesFolded={}, retryLoopsCollapsed={}, toolResponsesSummarised={}, "
                        + "messagesDropped={}, messagesSummarised={}, sessionId={}",
                currentTokens, finalTokens, hardCap,
                outcome.duplicatesFolded(), outcome.retryLoopsCollapsed(),
                outcome.toolResponsesSummarised(),
                outcome.messagesDropped(), outcome.messagesSummarised(),
                sessionIdOrNull(request));

        emitWarningBlock(request, currentTokens, finalTokens, outcome);

        return request.mutate()
                .prompt(new Prompt(outcome.messages(), request.prompt().getOptions()))
                .context(CTX_COMPACTION_APPLIED, true)
                .build();
    }

    private record CompactionOutcome(
            List<Message> messages,
            int duplicatesFolded,
            int retryLoopsCollapsed,
            int toolResponsesSummarised,
            int messagesDropped,
            int messagesSummarised) {}

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /**
     * Apply the compaction ladder until the prompt fits under {@code hardCap}. Strategies run
     * in the order: retry-loop collapse → duplicate fold → hard-cap drop. Each strategy is
     * a no-op if it has nothing to do; later strategies see the reduced list and may also
     * decide to skip.
     *
     * <p>If even the hard-cap drop cannot get the prompt under budget (all middle pairs
     * already gone), we return what we have — the provider will reject, but Phase A doesn't
     * touch user/system content. Phase D (LLM-driven summarisation, pending) handles that
     * fallback.
     */
    private CompactionOutcome compact(List<Message> original, int hardCap, String sessionId) {
        List<Message> result = original;
        int duplicatesFolded = 0;
        int retryLoopsCollapsed = 0;
        int toolResponsesSummarised = 0;
        int messagesDropped = 0;
        int messagesSummarised = 0;

        // Phase B (1/2): collapse consecutive failed retries first — captures verdict info
        // before duplicate-fold would otherwise flatten identical-arg pairs to pointer notes.
        CompactionStrategies.StrategyResult retryRes = CompactionStrategies.collapseRetryLoops(result);
        result = retryRes.messages();
        retryLoopsCollapsed = retryRes.count();

        // Phase B (2/2): fold remaining duplicate calls (older copies → pointer messages).
        CompactionStrategies.StrategyResult dupRes = CompactionStrategies.foldDuplicateToolCalls(result);
        result = dupRes.messages();
        duplicatesFolded = dupRes.count();

        // Phase C: per-tool summary hooks. Rewrites response bodies of OLD tool pairs
        // (outside the keep-last-pairs window) via the registry, plus optional default
        // elision for unregistered tools. Runs before Phase A so a tool pair that could
        // have been preserved as a one-line summary isn't dropped wholesale.
        if (toolSummariesEnabled) {
            CompactionStrategies.StrategyResult toolRes = CompactionStrategies.summariseOldToolResponses(
                    result, toolCompactionRegistry, keepLastTurns, toolSummariesDefaultElision);
            result = toolRes.messages();
            toolResponsesSummarised = toolRes.count();
        }

        // Phase A: hard-cap drop of oldest non-pinned tool pairs.
        if (tokenEstimator.estimate(result) > hardCap) {
            int sizeBefore = result.size();
            result = hardCapDropOldestPairs(result, hardCap);
            messagesDropped = sizeBefore - result.size();
        }

        // Phase D (fallback): if STILL over budget, summarise oldest user/assistant turns
        // via a cheap LLM and replace them with one rolling-summary SystemMessage. Skips
        // silently when disabled, no session id, no eligible turns, or the LLM call fails.
        if (summarisationEnabled && sessionId != null
                && tokenEstimator.estimate(result) > hardCap) {
            PhaseDOutcome dOut = applyPhaseD(result, sessionId);
            if (dOut != null) {
                result = dOut.messages;
                messagesSummarised = dOut.messagesSummarised;
            }
        }
        return new CompactionOutcome(result, duplicatesFolded, retryLoopsCollapsed,
                toolResponsesSummarised, messagesDropped, messagesSummarised);
    }

    private record PhaseDOutcome(List<Message> messages, int messagesSummarised) {}

    /**
     * Phase D step: identify the oldest user→assistant turns (anything before the last
     * {@code summarisationKeepLastTurns} user messages), feed them plus any prior summary
     * to {@link ConversationSummariser}, and on success replace the range with one
     * {@link SystemMessage} carrying the new rolling summary. The summary is persisted
     * to {@code kukuvaia.conversations.summary} for read on session resume.
     *
     * <p>Returns {@code null} if no eligible region exists (too few user messages to
     * leave a keep-window after summarising) or the summariser call failed. Caller treats
     * a {@code null} return as "Phase D didn't fire".
     */
    private PhaseDOutcome applyPhaseD(List<Message> messages, String sessionId) {
        List<Integer> userIndices = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) userIndices.add(i);
        }
        // Need at least (keepLast + 1) user messages: one to summarise + the keep window.
        if (userIndices.size() <= summarisationKeepLastTurns) return null;

        int summariseFrom = userIndices.get(0);
        int summariseTo = userIndices.get(userIndices.size() - summarisationKeepLastTurns);
        if (summariseTo <= summariseFrom) return null;

        List<Message> toSummarise = new ArrayList<>(messages.subList(summariseFrom, summariseTo));
        String previousSummary = summaryRepository.findBySessionId(sessionId).orElse("");

        Optional<String> newSummary = summariser.summarise(previousSummary, toSummarise);
        if (newSummary.isEmpty()) {
            log.warn("Phase D summarisation returned empty; falling through. sessionId={}", sessionId);
            return null;
        }

        try {
            summaryRepository.upsertSummary(sessionId, newSummary.get());
        } catch (Exception e) {
            // Persistence failure shouldn't block the in-flight turn — proceed with the
            // in-memory summary so this request fits, accept that resume won't see it.
            log.warn("Phase D: failed to persist summary for session {}: {}", sessionId, e.getMessage());
        }

        List<Message> compacted = new ArrayList<>(messages.size());
        for (int i = 0; i < summariseFrom; i++) compacted.add(messages.get(i));
        compacted.add(new SystemMessage("Conversation summary so far:\n" + newSummary.get()));
        for (int i = summariseTo; i < messages.size(); i++) compacted.add(messages.get(i));
        return new PhaseDOutcome(compacted, toSummarise.size());
    }

    /**
     * Phase A's original behaviour: drop oldest non-pinned tool-call pairs as units until
     * the prompt fits. SystemMessages, the last user message, and the last
     * {@code keepLastTurns} pairs are pinned. Phase E adds: pairs whose any tool_call
     * targets a {@link ToolCompactionRegistry}-pinned tool are also skipped — the LLM
     * needs the verbatim response for in-flight work.
     */
    private List<Message> hardCapDropOldestPairs(List<Message> messages, int hardCap) {
        int cutoffIndex = computeRecentCutoff(messages);

        List<Message> result = new ArrayList<>(messages);
        int i = 0;
        while (i < result.size() && tokenEstimator.estimate(result) > hardCap) {
            Message m = result.get(i);
            if (isPinned(m) || i >= cutoffIndex) {
                i++;
                continue;
            }
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                if (containsRegistryPinnedTool(am)) {
                    // Skip past this pair — leave it intact even though it's old.
                    int pairEnd = i + 1;
                    while (pairEnd < result.size() && result.get(pairEnd) instanceof ToolResponseMessage) {
                        pairEnd++;
                    }
                    i = pairEnd;
                    continue;
                }
                int pairEnd = i + 1;
                while (pairEnd < result.size() && result.get(pairEnd) instanceof ToolResponseMessage) {
                    pairEnd++;
                }
                int dropCount = pairEnd - i;
                for (int d = 0; d < dropCount; d++) {
                    result.remove(i);
                }
                cutoffIndex -= dropCount;
                continue;
            }
            // Non-pair unpinned message — leave it; Phase A doesn't drop these.
            i++;
        }
        return result;
    }

    private boolean containsRegistryPinnedTool(AssistantMessage am) {
        if (toolCompactionRegistry == null) return false;
        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            if (toolCompactionRegistry.isPinned(tc.name())) return true;
        }
        return false;
    }

    /**
     * Returns the index BEFORE which messages are pinned-recent. Counts back
     * {@code keepLastTurns} tool-call pairs (or all of them if fewer exist) plus the
     * trailing user message.
     */
    private int computeRecentCutoff(List<Message> messages) {
        int pairsFound = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                pairsFound++;
                if (pairsFound >= keepLastTurns) {
                    return i;
                }
            }
        }
        // Fewer pairs than keepLastTurns — nothing in the unpinned region is droppable.
        return 0;
    }

    private static boolean isPinned(Message m) {
        if (m instanceof SystemMessage) return true;
        return false;
    }

    private int resolveHardCap(ChatClientRequest request) {
        Object modelIdObj = request.context().get(ModelRoutingAdvisor.CTX_ROUTED_MODEL);
        Integer window = null;
        String modelId = null;
        if (modelIdObj instanceof String mid && !mid.isBlank()) {
            modelId = mid;
            window = chatModelCache.getModelRecord(mid)
                    .map(rec -> rec.contextWindow())
                    .orElse(null);
        }
        // Phase E: surface a one-time-per-model warning when the routed model has no
        // contextWindow recorded — we silently fall back to defaultWindowTokens, but the
        // operator should know their registry is missing data. Rate-limited via a Set so
        // a misconfigured model doesn't spam the log on every turn.
        if (modelId != null && (window == null || window <= 0)
                && warnedMissingWindowModels.add(modelId)) {
            log.warn("Routed model '{}' has no contextWindow in the registry — "
                            + "falling back to default {} tokens. Backfill the model record to remove this guess.",
                    modelId, defaultWindowTokens);
        }
        int effectiveWindow = window != null && window > 0 ? window : defaultWindowTokens;
        return Math.max(safetyMarginTokens + 1, effectiveWindow - safetyMarginTokens);
    }

    private void emitWarningBlock(ChatClientRequest request, int before, int after, CompactionOutcome outcome) {
        String sessionId = sessionIdOrNull(request);
        if (sessionId == null) return;
        List<String> parts = new ArrayList<>(5);
        if (outcome.retryLoopsCollapsed() > 0) {
            parts.add(outcome.retryLoopsCollapsed() + " retry-loop(s) collapsed");
        }
        if (outcome.duplicatesFolded() > 0) {
            parts.add(outcome.duplicatesFolded() + " duplicate(s) folded");
        }
        if (outcome.toolResponsesSummarised() > 0) {
            parts.add(outcome.toolResponsesSummarised() + " tool response(s) summarised");
        }
        if (outcome.messagesDropped() > 0) {
            parts.add(outcome.messagesDropped() + " older message(s) dropped");
        }
        if (outcome.messagesSummarised() > 0) {
            parts.add(outcome.messagesSummarised() + " older message(s) summarised");
        }
        String summary = parts.isEmpty() ? "no reducible content found" : String.join(", ", parts);
        String msg = String.format(
                "Context approached the model's window — %s (%,d tokens → %,d tokens). "
                        + "The agent's recent work is preserved.",
                summary, before, after);
        outputSink.emit(sessionId, new TextBlock(msg, "warning"));
    }

    private static String sessionIdOrNull(ChatClientRequest request) {
        Object sid = request.context().get("chat_memory_conversation_id");
        return sid != null ? sid.toString() : null;
    }
}
