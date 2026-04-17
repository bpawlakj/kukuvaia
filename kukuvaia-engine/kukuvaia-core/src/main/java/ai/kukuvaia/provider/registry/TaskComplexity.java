package ai.kukuvaia.provider.registry;

/**
 * Classification of task/action complexity for automatic model tier assignment.
 * Each complexity level maps to a routing role (e.g., "worker", "supervisor", "advisor")
 * via the {@code kukuvaia.complexity_mappings} table.
 *
 * Used by Embabel agents with {@code @ActionComplexity} annotation
 * and by the model routing system for per-action cost optimization.
 */
public enum TaskComplexity {

    /** Structured data extraction, parsing. Low reasoning. */
    EXTRACTION("worker"),

    /** Format conversion, mapping, reformatting. Mechanical. */
    TRANSFORMATION("worker"),

    /** Categorization, labeling, tagging. Bounded output. */
    CLASSIFICATION("worker"),

    /** Search, lookup, filtering. No generation. */
    RETRIEVAL("worker"),

    /** Pattern finding, comparison, reasoning. Medium complexity. */
    ANALYSIS("supervisor"),

    /** Creative content, code, new ideas. High complexity. */
    GENERATION("supervisor"),

    /** Merge multiple inputs, summarization. Medium complexity. */
    SYNTHESIS("supervisor"),

    /** Planning, architecture, high-level decisions. Highest complexity. */
    STRATEGY("advisor"),

    /** Quality assessment, review, scoring. Medium-high complexity. */
    EVALUATION("supervisor");

    private final String defaultRole;

    TaskComplexity(String defaultRole) {
        this.defaultRole = defaultRole;
    }

    /** Default role for this complexity when no DB mapping exists. */
    public String defaultRole() {
        return defaultRole;
    }
}
