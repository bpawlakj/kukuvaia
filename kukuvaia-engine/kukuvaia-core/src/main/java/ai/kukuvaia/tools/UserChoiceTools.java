package ai.kukuvaia.tools;

import ai.kukuvaia.output.ChoiceBlock;
import ai.kukuvaia.output.SessionOutputSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generic "ask the user to pick one" capability. Whenever the LLM cannot pick an answer for the
 * operator on its own — either because a discovery tool returned multiple matches (typical for
 * {@code find_outline_templates}) or because the user's intent is genuinely ambiguous — calling
 * this tool emits a {@link ChoiceBlock} into the SSE stream so the CLI can render an
 * arrow-navigable picker. The tool returns immediately; the LLM is expected to STOP its turn,
 * the operator's selection arrives as the next user message, and the LLM picks up from there.
 *
 * <p>Why a tool and not just a prompt instruction: the LLM may pick the "best looking" candidate
 * silently if not forced through a tool call. Going through a tool guarantees the picker is
 * actually shown and a structured reply gets back into the chat history, so future turns have a
 * machine-readable record of the operator's choice rather than a free-text quote.
 */
@Component
public class UserChoiceTools {

    private static final Logger log = LoggerFactory.getLogger(UserChoiceTools.class);

    private final SessionOutputSink outputSink;

    public UserChoiceTools(SessionOutputSink outputSink) {
        this.outputSink = outputSink;
    }

    @Tool(name = "ask_user_to_choose", description = """
            Present the operator with a list of options and STOP your turn — do NOT call other tools \
            or write reply text afterwards. The CLI shows an arrow-navigable picker; the operator's \
            choice arrives as the next user message in the form `[user-choice <id>] <label> (id=<value>)`. \
            Resume your work in the next turn using that choice. \

            Call this whenever: \
              - a discovery tool returned more than one viable candidate; \
              - the operator's intent is ambiguous and you would otherwise have to guess; \
              - you need explicit confirmation before an irreversible action (delete, promote, etc.). \

            Provide concise option labels (≤ 60 chars) and a short description (≤ 120 chars) so the \
            picker is scannable. The `value` field is what you'll get back — make it stable (e.g. an id).""")
    public Map<String, Object> askUserToChoose(
            @ToolParam(description = "Short question/prompt shown above the picker. Plain English, "
                    + "≤ 200 chars.") String prompt,
            @ToolParam(description = "List of options. Each option must have value (machine id), "
                    + "label (human name), and description (one-line context).")
            List<ChoiceOptionInput> options) {

        if (prompt == null || prompt.isBlank()) {
            return Map.of("ok", false, "error", "prompt must not be blank");
        }
        if (options == null || options.isEmpty()) {
            return Map.of("ok", false, "error", "options must not be empty");
        }

        String sessionId = PlanningTools.getCurrentSessionId();
        if (sessionId == null) {
            log.warn("askUserToChoose called without session context — cannot emit ChoiceBlock");
            return Map.of("ok", false, "error", "no session context — tool must run inside a chat turn");
        }

        String choiceId = UUID.randomUUID().toString();
        List<ChoiceBlock.ChoiceOption> mapped = new ArrayList<>(options.size());
        for (ChoiceOptionInput o : options) {
            if (o == null || o.value() == null || o.value().isBlank()) continue;
            mapped.add(new ChoiceBlock.ChoiceOption(
                    o.value(),
                    o.label() != null ? o.label() : o.value(),
                    o.description() != null ? o.description() : ""));
        }
        if (mapped.isEmpty()) {
            return Map.of("ok", false, "error", "no valid options after sanitisation");
        }

        outputSink.emit(sessionId, new ChoiceBlock(choiceId, prompt, mapped));
        log.info("askUserToChoose: sessionId={} choiceId={} options={}",
                sessionId, choiceId, mapped.size());

        return Map.of(
                "ok", true,
                "choiceId", choiceId,
                "presented", mapped.size(),
                "instruction",
                "User has been shown the picker. STOP your turn now — do not call more tools or "
                        + "write reply text. The user's selection will arrive as the next user message.");
    }

    /**
     * Tool-input shape for one choice option. Spring AI converts the JSON the LLM emits into this
     * record automatically; keeping fields nullable so a partially-completed call still surfaces a
     * useful error rather than a Jackson exception.
     */
    public record ChoiceOptionInput(String value, String label, String description) {
    }
}
