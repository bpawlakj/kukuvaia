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
            // English
            "yes", "yep", "yeah", "approve", "approved", "confirm", "confirmed", "ok", "okay",
            // Polish
            "tak", "zatwierdź", "zatwierdz", "zatwierdzam", "zatwierdzone",
            "akceptuję", "akceptuje", "akceptuj", "zgoda", "zgadzam się", "zgadzam sie",
            "potwierdzam", "potwierdź", "potwierdz", "potwierdzone",
            // Common typos — kept explicit rather than fuzzy-match to keep trigger
            // behaviour inspectable and testable.
            "zatwirdz", "zatwirdź", "zatwierz", "zatwier", "akcpetuj", "akceptje"
    );

    /** Triggers that clearly ask to revise the plan — used to distinguish "please change X"
     *  from "unrecognised confirmation typo". Without this, any unknown word in APPROVAL
     *  phase would silently revert to DRAFTING, which is how "zatwirdz" originally slipped
     *  into a creative-writing response. */
    private static final Set<String> REVISE_TRIGGERS = Set.of(
            "zmień", "zmien", "zmieniam", "popraw", "poprawki", "inaczej", "zmiana", "zmieńmy", "zmienmy",
            "revise", "change", "modify", "edit", "rewrite",
            "dodaj", "usuń", "usun", "wyrzuć", "wyrzuc"
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

        String lower = trimmed.toLowerCase();

        // Approval-word safety net runs BEFORE phase dispatch — if the user
        // says a confirmation word and a draft plan exists for this session,
        // approve it regardless of whether in-memory phase is APPROVAL or has
        // drifted to DRAFTING through an earlier bug or trigger miss. This
        // fixes the "approval button silent" case where phase flipped away
        // from APPROVAL and then "tak" was just handed to the LLM.
        if (matchesAny(lower, APPROVAL_TRIGGERS) && planningModeService.hasDraftPlan(sessionId)) {
            log.info("Approval word + draft plan in DB — approving regardless of in-memory phase, sessionId={}",
                    sessionId);
            planningModeService.approvePlan(sessionId);
            return agentService.streamChat(sessionId,
                    "The user approved the plan. Confirm that the plan is saved and active. " +
                            "Summarize next steps briefly.");
        }

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
        String trimmedLower = args.toLowerCase().trim();

        // Sub-commands that take arguments — parsed before the fixed-arg switch
        // so "/plan resume <uuid>" / "/plan combine <ids> <task>" / "/plan abandon <id>"
        // don't fall into the "/plan <task>" default.
        if (trimmedLower.startsWith("resume ")) {
            return handlePlanResume(args.substring("resume ".length()).trim(), sessionId);
        }
        if (trimmedLower.startsWith("combine ")) {
            return handlePlanCombine(args.substring("combine ".length()).trim(), sessionId);
        }
        if (trimmedLower.startsWith("abandon ")) {
            return handlePlanAbandon(args.substring("abandon ".length()).trim());
        }

        return switch (trimmedLower) {
            case "" -> Flux.just(new TextBlock(
                    "Usage: /plan <task description>\nSub-commands: /plan status, /plan cancel, /plan resume <id>, /plan combine <id1,id2,...> <task>, /plan abandon <id>", "error"));
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

    private Flux<OutputBlock> handlePlanResume(String planIdStr, String sessionId) {
        java.util.UUID planId;
        try {
            planId = java.util.UUID.fromString(planIdStr);
        } catch (IllegalArgumentException e) {
            return Flux.just(new TextBlock("Invalid plan id: " + planIdStr, "error"));
        }
        try {
            var resumed = planningModeService.resumeFromDb(planId, sessionId);
            log.info("Plan resumed: planId={} newSession={} phase={}", planId, sessionId, resumed.phase());
            return Flux.just(new TextBlock(
                    "Resumed plan '%s' in phase %s.".formatted(resumed.task(), resumed.phase()), null));
        } catch (IllegalStateException e) {
            return Flux.just(new TextBlock("Cannot resume: " + e.getMessage(), "error"));
        } catch (Exception e) {
            log.warn("resumeFromDb failed: {}", e.getMessage());
            return Flux.just(new TextBlock("Resume failed: " + e.getMessage(), "error"));
        }
    }

    private Flux<OutputBlock> handlePlanCombine(String argStr, String sessionId) {
        // Format: "<id1>,<id2>[,<id3>...] <task description>"
        int space = argStr.indexOf(' ');
        if (space < 0) {
            return Flux.just(new TextBlock(
                    "Usage: /plan combine <id1,id2,...> <new task description>", "error"));
        }
        String idsCsv = argStr.substring(0, space).trim();
        String newTask = argStr.substring(space + 1).trim();
        if (newTask.isEmpty()) {
            return Flux.just(new TextBlock("Combine requires a task description.", "error"));
        }
        java.util.List<java.util.UUID> parents = new java.util.ArrayList<>();
        for (String id : idsCsv.split(",")) {
            try {
                parents.add(java.util.UUID.fromString(id.trim()));
            } catch (IllegalArgumentException e) {
                return Flux.just(new TextBlock("Invalid plan id in list: " + id, "error"));
            }
        }
        if (parents.size() < 1) {
            return Flux.just(new TextBlock("Provide at least one parent plan id.", "error"));
        }
        try {
            var combined = planningModeService.combinePlans(parents, newTask, sessionId);
            log.info("Plans combined: parents={} newPlanId={} session={}",
                    parents, combined.newPlanId(), sessionId);
            return agentService.streamChat(sessionId,
                    "I am starting a combined planning session that merges these prior plans. " +
                            "Summarise the merged facts and ask what additional information is needed.");
        } catch (IllegalStateException e) {
            return Flux.just(new TextBlock("Combine failed: " + e.getMessage(), "error"));
        } catch (Exception e) {
            log.warn("combinePlans failed: {}", e.getMessage());
            return Flux.just(new TextBlock("Combine failed: " + e.getMessage(), "error"));
        }
    }

    private Flux<OutputBlock> handlePlanAbandon(String planIdStr) {
        java.util.UUID planId;
        try {
            planId = java.util.UUID.fromString(planIdStr);
        } catch (IllegalArgumentException e) {
            return Flux.just(new TextBlock("Invalid plan id: " + planIdStr, "error"));
        }
        int updated = planningModeService.abandonPlan(planId);
        if (updated > 0) {
            return Flux.just(new TextBlock("Plan abandoned.", null));
        }
        return Flux.just(new TextBlock("Plan not found or already resolved.", "error"));
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
                if (matchesAny(lower, REVISE_TRIGGERS)) {
                    // Explicit revise intent — drop back to DRAFTING and let the
                    // agent revise based on the user's remarks.
                    planningModeService.revertToDrafting(sessionId);
                    log.info("Planning transition: APPROVAL → DRAFTING (explicit revise), sessionId={}", sessionId);
                    yield agentService.streamChat(sessionId, message);
                }
                // Ambiguous — do NOT silently revert, do NOT forward to the LLM
                // (it will happily creative-write an approval that the DB never
                // sees). Ask the user to be explicit.
                log.info("APPROVAL phase unrecognised input — asking user to clarify, sessionId={}", sessionId);
                yield Flux.just(new TextBlock(
                        "Nie rozpoznałem odpowiedzi. Wpisz `tak` / `approve` żeby zatwierdzić plan, "
                                + "albo `zmień` wraz z opisem poprawek żeby przejść do rewizji.",
                        null));
            }
        };
    }

    /**
     * Token-level match — splits input on whitespace and punctuation, returns true
     * if any token is in the trigger set. Replaces the old exact-equals check that
     * missed "tak, zatwierdzam ten plan", "approve this plan please", etc.
     */
    private static boolean matchesAny(String input, Set<String> triggers) {
        if (input == null || input.isBlank()) return false;
        String[] tokens = input.toLowerCase().split("[\\s\\p{Punct}]+");
        for (String t : tokens) {
            if (!t.isEmpty() && triggers.contains(t)) return true;
        }
        return false;
    }
}
