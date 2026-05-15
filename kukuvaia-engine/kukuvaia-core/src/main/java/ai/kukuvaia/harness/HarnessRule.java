package ai.kukuvaia.harness;

import java.util.List;

/**
 * One harness rule loaded from a {@link HarnessRuleStore}. Rules are markdown documents with a
 * YAML frontmatter; the body is the human-readable instruction injected into the LLM system prompt
 * by {@link HarnessAdvisor}.
 *
 * <p>Replaces the legacy DB-backed {@code RuleRecord} (V8 migration) with a file-sourced shape:
 * version control over operator-tunable runtime, and reproducibility for non-interactive agent
 * runs (P23) — the same dispatch produces the same compiled context across processes / pods,
 * because the rules are committed artifacts, not mutable rows.
 *
 * @param name     stable identifier within the store — typically the filename without extension.
 *                 Used by the admin panel for read/edit/delete.
 * @param key      override key. When two rules share a key, lower-scope wins (user beats platform).
 *                 Falls back to {@code name} if not set explicitly in the frontmatter.
 * @param scope    {@code "platform"} (applies to everyone) or {@code "user:<userId>"}
 *                 (applies only to the named user). Group scope is not supported in this iteration —
 *                 add later when group membership has a real backing store.
 * @param type     classifies the rule for grouping in the compiled output:
 *                 {@code instruction} | {@code constraint} | {@code context} | {@code preference}.
 * @param content  the body markdown — what the LLM actually sees.
 * @param priority higher-priority rules sort first within a section (stable when equal).
 * @param enabled  disabled rules stay in the store for visibility but are skipped at compile time.
 * @param tags     free-form labels for filtering / browsing in the admin panel.
 */
public record HarnessRule(
        String name,
        String key,
        String scope,
        String type,
        String content,
        int priority,
        boolean enabled,
        List<String> tags) {

    public static final String SCOPE_PLATFORM = "platform";
    public static final String SCOPE_USER_PREFIX = "user:";

    public HarnessRule {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type required");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content required");
        if (scope == null || scope.isBlank()) scope = SCOPE_PLATFORM;
        if (key == null || key.isBlank()) key = name;
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public boolean isPlatformScope() {
        return SCOPE_PLATFORM.equals(scope);
    }

    public boolean appliesToUser(String userId) {
        if (userId == null) return false;
        return scope.equals(SCOPE_USER_PREFIX + userId);
    }
}
