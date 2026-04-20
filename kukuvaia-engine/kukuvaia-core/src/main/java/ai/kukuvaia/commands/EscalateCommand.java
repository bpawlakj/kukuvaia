package ai.kukuvaia.commands;

import ai.kukuvaia.agent.SessionEscalationService;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /escalate — set one-shot escalation flag so the NEXT user message is routed
 * to the advisor-tier model (STRATEGY complexity). Replaces magic phrases like
 * "think harder" that used to drive escalation via keyword matching.
 */
@Component
public class EscalateCommand implements SlashCommand {

    private final SessionEscalationService escalationService;

    public EscalateCommand(SessionEscalationService escalationService) {
        this.escalationService = escalationService;
    }

    @Override
    public String name() {
        return "escalate";
    }

    @Override
    public String description() {
        return "Force the next message to run on the advisor-tier model (one-turn override).";
    }

    @Override
    public List<OutputBlock> execute(String args, String sessionId) {
        escalationService.markEscalate(sessionId);
        return List.of(new TextBlock(
                "Next message will route to the advisor-tier model. Send your question.",
                null));
    }
}
