package ai.kukuvaia.extensions;

import java.util.List;

/**
 * Structured rule definition parsed from .kukuvaia/rules/*.md frontmatter.
 *
 * Types:
 * - CONSTRAINT: Always-on hard rules (e.g., "never expose credentials")
 * - BEHAVIOR: Response style modifiers (e.g., "think step by step", "be concise")
 * - FORMAT: Output format requirements (e.g., "respond in JSON", "use tables")
 *
 * Scopes:
 * - GLOBAL: Applied to every conversation
 * - PERSONA: Applied only when matching persona is active
 * - INTENT: Applied only when matching intent is detected
 */
public record RuleSpec(
        String name,
        String description,
        RuleType type,
        RuleScope scope,
        int priority,
        String persona,
        List<String> intents,
        String body
) {
    public enum RuleType { CONSTRAINT, BEHAVIOR, FORMAT }
    public enum RuleScope { GLOBAL, PERSONA, INTENT }

    public RuleSpec {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Rule name is required");
        if (type == null) type = RuleType.CONSTRAINT;
        if (scope == null) scope = RuleScope.GLOBAL;
        if (priority <= 0) priority = 50;
        if (intents == null) intents = List.of();
        if (body == null || body.isBlank()) throw new IllegalArgumentException("Rule body is required");
    }

    /**
     * Check if this rule applies to the given context.
     */
    public boolean appliesTo(String activePersona, String detectedIntent) {
        return switch (scope) {
            case GLOBAL -> true;
            case PERSONA -> persona != null && persona.equals(activePersona);
            case INTENT -> intents.isEmpty() || intents.contains(detectedIntent);
        };
    }
}
