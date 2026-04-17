package ai.kukuvaia.agent;

import ai.kukuvaia.commands.CommandRegistry;
import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.skills.SkillExecutor;
import ai.kukuvaia.skills.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Set;

/**
 * Routes user input: /command → CommandRegistry, /trigger → SkillRegistry, free text → ChatClient.
 * Planning mode: detects phase transitions via deterministic string matching.
 */
@Component
public class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private static final Set<String> READY_TRIGGERS = Set.of(
            "ready", "gotowe", "gotowy", "create plan", "stwórz plan", "stworz plan",
            "zrób plan", "zrob plan", "jestem gotowy", "jestem gotowa"
    );
    private static final Set<String> APPROVAL_TRIGGERS = Set.of(
            "yes", "tak", "approve", "zatwierdź", "zatwierdz", "zatwierdzam",
            "ok", "akceptuję", "akceptuje", "zgoda"
    );

    private final CommandRegistry commandRegistry;
    private final AgentService agentService;
    private final SkillRegistry skillRegistry;
    private final SkillExecutor skillExecutor;
    private final PlanningModeService planningModeService;

    public CommandRouter(CommandRegistry commandRegistry, AgentService agentService,
                         SkillRegistry skillRegistry, SkillExecutor skillExecutor,
                         PlanningModeService planningModeService) {
        this.commandRegistry = commandRegistry;
        this.agentService = agentService;
        this.skillRegistry = skillRegistry;
        this.skillExecutor = skillExecutor;
        this.planningModeService = planningModeService;
    }

    /**
     * Route input to either slash command, planning mode handler, or agent.
     */
    public Flux<OutputBlock> route(String input, String sessionId) {
        if (input == null || input.isBlank()) {
            return Flux.just(new TextBlock("Empty input", "error"));
        }

        String trimmed = input.trim();

        if (trimmed.startsWith("/")) {
            return routeCommand(trimmed, sessionId);
        }

        // Planning mode: detect phase transitions before forwarding to agent
        if (planningModeService.isInPlanningMode(sessionId)) {
            return handlePlanningMessage(trimmed, sessionId);
        }

        return agentService.streamChat(sessionId, trimmed);
    }

    private Flux<OutputBlock> routeCommand(String input, String sessionId) {
        String withoutSlash = input.substring(1);
        int spaceIndex = withoutSlash.indexOf(' ');
        String commandName = spaceIndex > 0 ? withoutSlash.substring(0, spaceIndex) : withoutSlash;
        String args = spaceIndex > 0 ? withoutSlash.substring(spaceIndex + 1).trim() : "";

        // /plan sub-commands
        if ("plan".equals(commandName)) {
            return handlePlanCommand(args, sessionId);
        }

        return commandRegistry.resolve(commandName)
                .map(cmd -> {
                    log.info("Executing command: /{} args='{}' sessionId={}", commandName, args, sessionId);
                    List<OutputBlock> result = cmd.execute(args, sessionId);
                    return Flux.fromIterable(result);
                })
                .orElseGet(() -> {
                    return skillRegistry.resolveByTrigger("/" + commandName)
                            .map(skill -> {
                                log.info("Executing skill trigger: /{} sessionId={}", commandName, sessionId);
                                return Flux.fromIterable(skillExecutor.execute(skill, args, sessionId));
                            })
                            .orElseGet(() -> {
                                log.warn("Unknown command: /{}", commandName);
                                return Flux.just(new TextBlock(
                                        "Unknown command: /" + commandName + ". Type /help for available commands.", "error"));
                            });
                });
    }

    // --- Planning ---

    private Flux<OutputBlock> handlePlanCommand(String args, String sessionId) {
        return switch (args.toLowerCase().trim()) {
            case "" -> Flux.just(new TextBlock("Usage: /plan <task description>\nSub-commands: /plan status, /plan cancel", "error"));
            case "status" -> {
                var session = planningModeService.getSession(sessionId);
                if (session.isEmpty()) {
                    yield Flux.just(new TextBlock("Not in planning mode. Use /plan <task> to start.", null));
                }
                var s = session.get();
                yield Flux.just(new TextBlock(
                        "Planning: **%s**\nPhase: **%s**\nStarted: %s".formatted(s.task(), s.phase(), s.startedAt()), null));
            }
            case "cancel" -> {
                if (!planningModeService.isInPlanningMode(sessionId)) {
                    yield Flux.just(new TextBlock("Not in planning mode.", null));
                } else {
                    planningModeService.cancelPlanning(sessionId);
                    yield Flux.just(new TextBlock("Planning cancelled.", null));
                }
            }
            default -> {
                // /plan <task> — start planning mode (cancel existing if any)
                if (planningModeService.isInPlanningMode(sessionId)) {
                    planningModeService.cancelPlanning(sessionId);
                    log.info("Previous planning cancelled, starting new: sessionId={}", sessionId);
                }
                planningModeService.startPlanning(sessionId, args);
                log.info("Planning started: sessionId={}, task='{}'", sessionId, args);
                yield agentService.streamChat(sessionId, args);
            }
        };
    }

    private Flux<OutputBlock> handlePlanningMessage(String message, String sessionId) {
        var session = planningModeService.getSession(sessionId).orElseThrow();
        String lower = message.toLowerCase().trim();

        return switch (session.phase()) {
            case DISCOVERY -> {
                if (matchesAny(lower, READY_TRIGGERS)) {
                    planningModeService.advanceToDrafting(sessionId);
                    log.info("Planning transition: DISCOVERY → DRAFTING, sessionId={}", sessionId);
                }
                // Forward to agent — advisor injects current phase prompt
                yield agentService.streamChat(sessionId, message);
            }
            case DRAFTING -> agentService.streamChat(sessionId, message);
            case APPROVAL -> {
                if (matchesAny(lower, APPROVAL_TRIGGERS)) {
                    planningModeService.approvePlan(sessionId);
                    log.info("Plan approved, sessionId={}", sessionId);
                    yield agentService.streamChat(sessionId,
                            "The user approved the plan. Confirm that the plan is saved and active. " +
                            "Summarize next steps briefly.");
                }
                // User wants changes — revert to drafting
                planningModeService.revertToDrafting(sessionId);
                log.info("Planning transition: APPROVAL → DRAFTING (changes), sessionId={}", sessionId);
                yield agentService.streamChat(sessionId, message);
            }
        };
    }

    private static boolean matchesAny(String input, Set<String> triggers) {
        return triggers.contains(input);
    }
}
