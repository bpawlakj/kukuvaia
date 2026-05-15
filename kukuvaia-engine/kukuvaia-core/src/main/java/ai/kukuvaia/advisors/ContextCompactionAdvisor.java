package ai.kukuvaia.advisors;

import ai.kukuvaia.output.SessionOutputSink;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.provider.repository.ModelRepository;
import java.util.ArrayList;
import java.util.List;
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
 * Phase A of P24 (Context Compaction). Inspects every outbound LLM request and, when the
 * estimated token count would exceed the active model's context window minus a safety
 * margin, drops the oldest non-pinned tool-call pairs until the prompt fits.
 *
 * <p>Phase A is intentionally crude — it does NOT summarise, it does NOT collapse retry
 * loops, it does NOT pin tool responses by relevance. It only stops the catastrophic
 * "HTTP 400 prompt too long" outcome we observed on 2026-05-13, where 8 internal turns
 * of authoring grew the prompt to 200 360 tokens against a 200 000-token cap. Smarter
 * strategies live in Phase B–E (see {@code docs/plan/P24-context-compaction.md}).
 *
 * <p>Pinning policy (never dropped):
 * <ul>
 *   <li>All {@link SystemMessage} entries — persona/system prompt is the contract.</li>
 *   <li>The last {@code keepLastTurns} (default 2) tool-call pairs — the LLM needs to see
 *       what it just did to make the next decision.</li>
 *   <li>The most recent {@link UserMessage} — the operator's current request.</li>
 * </ul>
 *
 * <p>Dropped: oldest {@link AssistantMessage} entries that contain tool_calls AND their paired
 * {@link ToolResponseMessage}. We drop pairs as units because dropping a tool result while
 * keeping the matching tool_use crashes the provider (it sees an unanswered tool call).
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

    private final TokenEstimator tokenEstimator;
    private final ModelRepository modelRepository;
    private final SessionOutputSink outputSink;

    private final boolean enabled;
    private final int defaultWindowTokens;
    private final int safetyMarginTokens;
    private final int keepLastTurns;

    public ContextCompactionAdvisor(
            TokenEstimator tokenEstimator,
            ModelRepository modelRepository,
            SessionOutputSink outputSink,
            @Value("${kukuvaia.context-compaction.enabled:true}") boolean enabled,
            @Value("${kukuvaia.context-compaction.default-window-tokens:200000}") int defaultWindowTokens,
            @Value("${kukuvaia.context-compaction.safety-margin-tokens:5000}") int safetyMarginTokens,
            @Value("${kukuvaia.context-compaction.keep-last-turns:2}") int keepLastTurns) {
        this.tokenEstimator = tokenEstimator;
        this.modelRepository = modelRepository;
        this.outputSink = outputSink;
        this.enabled = enabled;
        this.defaultWindowTokens = defaultWindowTokens;
        this.safetyMarginTokens = safetyMarginTokens;
        this.keepLastTurns = keepLastTurns;
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

        List<Message> compacted = compact(messages, hardCap);
        int compactedTokens = tokenEstimator.estimate(compacted);
        int dropped = messages.size() - compacted.size();

        log.warn("Context compaction fired: original={} tokens, compacted={} tokens, "
                + "hardCap={}, droppedMessages={}, sessionId={}",
                currentTokens, compactedTokens, hardCap, dropped, sessionIdOrNull(request));

        emitWarningBlock(request, currentTokens, compactedTokens, dropped);

        return request.mutate()
                .prompt(new Prompt(compacted, request.prompt().getOptions()))
                .context(CTX_COMPACTION_APPLIED, true)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    /**
     * Drop oldest non-pinned tool-call pairs (assistant-with-tool_calls + its paired
     * tool-response) until token estimate is under {@code hardCap}. Walks forward through
     * the unpinned middle region; SystemMessages, the last user message, and the last
     * {@code keepLastTurns} pairs are pinned.
     *
     * <p>If after dropping all eligible tool pairs the prompt is STILL over budget, returns
     * what we have — the provider will reject the request, but Phase A doesn't try to
     * mutate user/system content. Phase D (LLM-driven summarisation) handles that case.
     */
    private List<Message> compact(List<Message> original, int hardCap) {
        int totalMessages = original.size();
        // Index of the cut-off: messages from cutoffIndex inclusive are pinned-recent.
        int cutoffIndex = computeRecentCutoff(original);

        List<Message> result = new ArrayList<>(original);
        // Walk forward through the unpinned middle, dropping tool-call pairs.
        // We process pairs as units: an AssistantMessage with tool_calls + the
        // immediately-following ToolResponseMessage(s).
        int i = 0;
        while (i < result.size() && tokenEstimator.estimate(result) > hardCap) {
            Message m = result.get(i);
            if (isPinned(m) || i >= cutoffIndex) {
                i++;
                continue;
            }
            if (m instanceof AssistantMessage am && am.hasToolCalls()) {
                // Drop the assistant tool-call message + any consecutive tool responses.
                int pairEnd = i + 1;
                while (pairEnd < result.size() && result.get(pairEnd) instanceof ToolResponseMessage) {
                    pairEnd++;
                }
                int dropCount = pairEnd - i;
                for (int d = 0; d < dropCount; d++) {
                    result.remove(i);
                }
                cutoffIndex -= dropCount;
                // Re-process at same index — there may be more pairs to drop.
                continue;
            }
            // Non-pair unpinned message — leave it; Phase A doesn't drop these.
            i++;
        }
        return result;
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
        if (modelIdObj instanceof String modelId && !modelId.isBlank()) {
            window = modelRepository.findByModelId(modelId)
                    .map(rec -> rec.contextWindow())
                    .orElse(null);
        }
        int effectiveWindow = window != null && window > 0 ? window : defaultWindowTokens;
        return Math.max(safetyMarginTokens + 1, effectiveWindow - safetyMarginTokens);
    }

    private void emitWarningBlock(ChatClientRequest request, int before, int after, int dropped) {
        String sessionId = sessionIdOrNull(request);
        if (sessionId == null) return;
        String msg = String.format(
                "Context approached the model's window — %d older tool result(s) dropped to fit "
                        + "(%,d tokens → %,d tokens). The agent's recent work is preserved.",
                dropped, before, after);
        outputSink.emit(sessionId, new TextBlock(msg, "warning"));
    }

    private static String sessionIdOrNull(ChatClientRequest request) {
        Object sid = request.context().get("chat_memory_conversation_id");
        return sid != null ? sid.toString() : null;
    }
}
