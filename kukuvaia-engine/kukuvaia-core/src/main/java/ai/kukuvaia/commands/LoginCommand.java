package ai.kukuvaia.commands;

import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.provider.service.LlmProviderService;
import ai.kukuvaia.provider.copilot.CopilotTokenProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * /login — provider auth status and GitHub Copilot device-flow login.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code /login} or {@code /login status} — show overall provider status (default LLM + Copilot session)</li>
 *   <li>{@code /login github} — start GitHub Copilot OAuth device flow; returns the user-code + verification URL,
 *       then polls for completion in the background</li>
 *   <li>{@code /login github logout} — wipe stored GitHub credentials</li>
 *   <li>{@code /login logout} — alias for {@code /login github logout}</li>
 * </ul>
 */
@Component
public class LoginCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(LoginCommand.class);

    private final LlmProviderService providerService;
    private final CopilotTokenProvider copilotTokenProvider;
    private final ExecutorService pollingExecutor;

    public LoginCommand(LlmProviderService providerService, CopilotTokenProvider copilotTokenProvider) {
        this.providerService = providerService;
        this.copilotTokenProvider = copilotTokenProvider;
        this.pollingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "login-github-poll");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        pollingExecutor.shutdownNow();
    }

    @Override
    public String name() {
        return "login";
    }

    @Override
    public String description() {
        return "Show auth status or log in to a provider (usage: /login [status|github [logout]|logout])";
    }

    @Override
    public List<OutputBlock> execute(String args, String sessionId) {
        String raw = args == null ? "" : args.trim();
        String[] parts = raw.isEmpty() ? new String[0] : raw.split("\\s+");
        String head = parts.length == 0 ? "status" : parts[0].toLowerCase();
        String tail = parts.length > 1 ? parts[1].toLowerCase() : "";

        return switch (head) {
            case "", "status" -> List.of(buildStatus());
            case "github" -> "logout".equals(tail) ? List.of(githubLogout()) : List.of(githubLogin());
            case "logout" -> List.of(githubLogout());
            default -> List.of(new TextBlock(
                    "Usage: /login [status | github [logout] | logout]", "error"));
        };
    }

    private TextBlock buildStatus() {
        boolean supervisorReady = providerService.isAvailable("supervisor");
        CopilotTokenProvider.Status copilotStatus = copilotTokenProvider.currentStatus();
        String copilotLine = switch (copilotStatus) {
            case AUTHENTICATED -> "GitHub Copilot: authenticated as %s"
                    .formatted(copilotTokenProvider.getGithubLogin().orElse("(unknown)"));
            case PENDING_DEVICE_AUTH -> "GitHub Copilot: device-flow pending — finish authorization in your browser";
            case NOT_AUTHENTICATED -> "GitHub Copilot: not authenticated — run /login github";
        };
        String supervisorLine = "Default LLM provider: %s"
                .formatted(supervisorReady ? "connected" : "not configured (set LLM_BASE_URL and LLM_API_KEY)");
        return new TextBlock(supervisorLine + "\n" + copilotLine, null);
    }

    private TextBlock githubLogin() {
        try {
            CopilotTokenProvider.DeviceAuthSession session = copilotTokenProvider.startDeviceFlow();
            pollingExecutor.submit(() -> {
                try {
                    copilotTokenProvider.pollForOAuthToken(session);
                } catch (Exception e) {
                    log.warn("[login] GitHub device-flow polling failed: {}", e.getMessage());
                }
            });
            String message = """
                    GitHub Copilot — open this URL in your browser:
                      %s

                    Enter this code: %s

                    Code expires at %s. After authorizing in the browser, run /login status
                    to confirm the connection.""".formatted(
                    session.verificationUri(), session.userCode(), session.expiresAt());
            return new TextBlock(message, null);
        } catch (CopilotTokenProvider.DeviceFlowException e) {
            return new TextBlock("Failed to start GitHub device flow: " + e.getMessage(), "error");
        }
    }

    private TextBlock githubLogout() {
        copilotTokenProvider.logout();
        return new TextBlock("GitHub Copilot credentials cleared.", null);
    }
}
