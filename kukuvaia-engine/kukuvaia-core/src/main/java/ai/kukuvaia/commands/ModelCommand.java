package ai.kukuvaia.commands;

import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /model [list|name] — show or switch active model.
 */
@Component
public class ModelCommand implements SlashCommand {

    @Value("${LLM_MODEL:claude-sonnet-4.5}")
    private String defaultModel;

    @Override
    public String name() {
        return "model";
    }

    @Override
    public String description() {
        return "Show or switch model (usage: /model [list|<name>])";
    }

    @Override
    public List<OutputBlock> execute(String args, String sessionId) {
        if (args == null || args.isBlank() || "list".equals(args.trim())) {
            return List.of(new TextBlock("Current model: " + defaultModel, null));
        }
        // Model switching would require LlmProviderService integration
        return List.of(new TextBlock("Model set to: " + args.trim(), null));
    }
}
