package ai.kukuvaia.agent;

import ai.kukuvaia.extensions.RulesLoader;
import org.springframework.stereotype.Component;

/**
 * Assembles system prompt with static/dynamic boundary for cache efficiency.
 *
 * Static section: planning instructions, verification mandate (globally cacheable).
 * Dynamic section: persona prompt, structured rules (by type/scope), active skill.
 *
 * Rules are grouped by type:
 * - Constraints: non-negotiable hard rules
 * - Behavior: response style modifiers (thinking, concise, etc.)
 * - Format: output format requirements (JSON, tables, etc.)
 */
@Component
public class SystemPromptBuilder {

    private static final String STATIC_BOUNDARY = "\n──── __SYSTEM_PROMPT_DYNAMIC_BOUNDARY__ ────\n";

    private static final String VERIFICATION_MANDATE = """

            ## Verification Mandate
            After creating or modifying ANY document:
            1. Re-read using read_document
            2. Call verify_document with checks: [structure, formatting, consistency]
            3. If issues found: fix, then verify again
            4. Only report completion after verification passes

            NEVER say "done" without verifying.

            Report outcomes faithfully: if checks fail, say so with specific issues.
            Never claim "all checks pass" when output shows failures.
            """;

    private static final String PLANNING_INSTRUCTIONS = """

            ## Planning
            For tasks with 3 or more steps, call create_plan FIRST.
            Track progress by calling complete_step after each step.
            If circumstances change, call revise_plan with the updated steps.
            """;

    private final RulesLoader rulesLoader;

    public SystemPromptBuilder(RulesLoader rulesLoader) {
        this.rulesLoader = rulesLoader;
    }

    /**
     * Build complete system prompt from persona, rules, and context.
     *
     * @param persona        active persona (system prompt + tool filter)
     * @param activePersona  persona name for rule scoping
     * @param detectedIntent detected intent for rule scoping (CONVERSATION, DOCUMENT_READ, etc.)
     * @param activeSkill    active skill body (if any)
     */
    public String build(PersonaSpec persona, String activePersona, String detectedIntent, String activeSkill) {
        var sb = new StringBuilder();

        // Static section (globally cacheable)
        sb.append(PLANNING_INSTRUCTIONS);
        sb.append(VERIFICATION_MANDATE);

        sb.append(STATIC_BOUNDARY);

        // Dynamic section (per user/session)
        if (persona != null) {
            sb.append("\n## Persona\n").append(persona.systemPrompt()).append("\n");
        }

        // Structured rules — grouped by type, filtered by scope
        String rulesBlock = rulesLoader.buildRulesPrompt(activePersona, detectedIntent);
        if (!rulesBlock.isBlank()) {
            sb.append(rulesBlock);
        }

        if (activeSkill != null && !activeSkill.isBlank()) {
            sb.append("\n## Active Skill\n").append(activeSkill).append("\n");
        }

        return sb.toString();
    }

    /**
     * Simplified build without intent/skill context.
     */
    public String build(PersonaSpec persona, String userRules, String activeSkill) {
        var sb = new StringBuilder();

        sb.append(PLANNING_INSTRUCTIONS);
        sb.append(VERIFICATION_MANDATE);
        sb.append(STATIC_BOUNDARY);

        if (persona != null) {
            sb.append("\n## Persona\n").append(persona.systemPrompt()).append("\n");
        }

        if (userRules != null && !userRules.isBlank()) {
            sb.append("\n## User Rules\n").append(userRules).append("\n");
        }

        if (activeSkill != null && !activeSkill.isBlank()) {
            sb.append("\n## Active Skill\n").append(activeSkill).append("\n");
        }

        return sb.toString();
    }
}
