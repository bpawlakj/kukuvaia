package ai.kukuvaia.agentrun;

import java.util.List;

/**
 * Optional run-to-run recall. When enabled, AgentRunService prepends the top-K most similar
 * past run outputs (cosine over embedding) to the user prompt.
 */
public record SimilarityContextSpec(
        boolean enabled,
        int topK,
        Integer maxAgeDays,
        boolean filterByInvokerName,
        List<String> filterByTags) {

    public SimilarityContextSpec {
        if (topK <= 0) topK = 3;
        filterByTags = filterByTags == null ? List.of() : List.copyOf(filterByTags);
    }

    public static SimilarityContextSpec disabled() {
        return new SimilarityContextSpec(false, 3, 30, true, List.of());
    }
}
