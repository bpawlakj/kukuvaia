package ai.kukuvaia.commands;

import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.provider.LlmProviderService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /login [status|logout] — show provider status or manage authentication.
 */
@Component
public class LoginCommand implements SlashCommand {

    private final LlmProviderService providerService;

    public LoginCommand(LlmProviderService providerService) {
        this.providerService = providerService;
    }

    @Override
    public String name() {
        return "login";
    }

    @Override
    public String description() {
        return "Show provider status (usage: /login [status|logout])";
    }

    @Override
    public List<OutputBlock> execute(String args, String sessionId) {
        String subcommand = args != null ? args.trim().toLowerCase() : "status";

        return switch (subcommand) {
            case "status" -> {
                boolean available = providerService.isAvailable("supervisor");
                yield List.of(new TextBlock(
                        "LLM provider: %s".formatted(available ? "connected" : "not configured — set LLM_BASE_URL and LLM_API_KEY"),
                        null));
            }
            case "logout" ->
                    List.of(new TextBlock("Logged out", null));
            default ->
                    List.of(new TextBlock("Usage: /login [status|logout]", "error"));
        };
    }
}
