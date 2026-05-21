package ai.kukuvaia.advisors;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Registry of per-tool response summaries used by P24 Phase C. Tool-owning modules
 * (or their Spring {@code @Configuration} beans) register a {@link ToolCompactSummary}
 * keyed by the tool name as the LLM/MCP sees it.
 *
 * <p>Why a registry instead of an annotation: tool implementations may live in another
 * JVM (MCP servers) where the engine has no reflection access; the tool name string is
 * the only stable identifier we share. Registration happens at startup so the lookup
 * path during compaction is a single {@code ConcurrentHashMap.get}.
 *
 * <p>Thread-safe: backed by {@link ConcurrentHashMap}. Registrations from
 * {@code @PostConstruct} or {@code ApplicationReadyEvent} are safe; runtime
 * registrations are allowed but unusual.
 */
@Component
public class ToolCompactionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolCompactionRegistry.class);

    private final ConcurrentHashMap<String, ToolCompactSummary> summaries = new ConcurrentHashMap<>();

    /**
     * Tools whose responses must NOT be compacted (P24 Phase E pinning). A pinned tool's
     * responses survive Phase A drops and Phase C summaries even when older than the
     * keep window — useful when the in-flight workflow depends on raw response data
     * (e.g. sampled IDs the active persona is referencing across turns).
     */
    private final Set<String> pinnedTools = ConcurrentHashMap.newKeySet();

    public void register(String toolName, ToolCompactSummary summary) {
        if (toolName == null || toolName.isBlank() || summary == null) return;
        ToolCompactSummary prev = summaries.put(toolName, summary);
        if (prev != null) {
            log.info("ToolCompactionRegistry: replacing summary for '{}'", toolName);
        } else {
            log.debug("ToolCompactionRegistry: registered summary for '{}'", toolName);
        }
    }

    public Optional<ToolCompactSummary> get(String toolName) {
        if (toolName == null) return Optional.empty();
        return Optional.ofNullable(summaries.get(toolName));
    }

    public boolean has(String toolName) {
        return toolName != null && summaries.containsKey(toolName);
    }

    /**
     * Mark a tool as pin-protected. Its responses are skipped by Phase A drops and Phase C
     * summaries. Idempotent — repeated calls are no-ops.
     */
    public void pin(String toolName) {
        if (toolName == null || toolName.isBlank()) return;
        if (pinnedTools.add(toolName)) {
            log.info("ToolCompactionRegistry: pinned '{}' — responses will not be compacted", toolName);
        }
    }

    public void unpin(String toolName) {
        if (toolName == null) return;
        pinnedTools.remove(toolName);
    }

    public boolean isPinned(String toolName) {
        return toolName != null && pinnedTools.contains(toolName);
    }

    /** Visible for tests. */
    int size() {
        return summaries.size();
    }

    /** Visible for tests. */
    int pinnedCount() {
        return pinnedTools.size();
    }
}
