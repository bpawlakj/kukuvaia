package ai.kukuvaia.output;

import java.util.List;

/**
 * Interactive choice prompt — emitted whenever the agent needs the operator to disambiguate
 * between several candidates (e.g. find_outline_templates returned 3 matches) or to confirm a
 * specific course of action.
 *
 * <p>The CLI auto-opens an arrow-navigable picker on receipt; the operator selects an option and
 * the CLI sends back a follow-up user message of the form
 * {@code "[user-choice <choiceId>] <label> (id=<value>)"} which the LLM picks up on its next
 * turn. {@code choiceId} carries no semantics beyond correlating one prompt with one reply,
 * useful when the chat scrolls back over multiple choices in the same session.
 *
 * <p>{@code value} on each option is what the agent should treat as the canonical machine-readable
 * choice (template id, rule id, etc.); {@code label} + {@code description} are for the human eye.
 */
public record ChoiceBlock(
        String choiceId,
        String prompt,
        List<ChoiceOption> options
) implements OutputBlock {

    public record ChoiceOption(String value, String label, String description) {
    }
}
