package ai.kukuvaia.advisors;

/**
 * Per-tool response compaction hook used by P24 Phase C. A registered summariser receives
 * the tool call's original canonical arguments JSON and the raw response data, and returns
 * a short replacement string (typically under 500 chars) that preserves what the LLM needs
 * to reason about the call without dragging the full payload through context.
 *
 * <p>Implementations MUST be:
 * <ul>
 *   <li><b>Pure</b> — same inputs → same output. Phase C may call the same summary
 *       multiple times across turns.</li>
 *   <li><b>Crash-safe</b> — return {@code null} (and not throw) on unparseable input.
 *       A thrown exception falls back to the registry's default elision, so a malformed
 *       response cannot break the request.</li>
 *   <li><b>Self-contained</b> — no I/O, no DB, no external LLM calls. The whole point
 *       of Phase C is to be cheap; expensive summaries belong in Phase D.</li>
 * </ul>
 *
 * <p>Example: a registered summary might collapse a 50 KB JSON dump into a couple of hundred
 * bytes capturing headline counts (e.g. {@code "schema for <X>: 47 entries, 23 of kind A"}).
 */
@FunctionalInterface
public interface ToolCompactSummary {

    /**
     * Produce a short replacement for {@code responseData}. Returning {@code null} signals
     * "no summary available" and the caller falls back to the default elision (drop body +
     * keep a size marker).
     */
    String summarise(String argsJson, String responseData);
}
