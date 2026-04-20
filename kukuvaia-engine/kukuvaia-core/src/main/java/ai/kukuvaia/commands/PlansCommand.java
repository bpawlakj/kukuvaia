package ai.kukuvaia.commands;

import ai.kukuvaia.output.OutputBlock;
import ai.kukuvaia.output.PlanListBlock;
import ai.kukuvaia.output.TextBlock;
import ai.kukuvaia.plans.PlansRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * /plans — open the plan registry picker.
 *
 * Subcommands:
 *   /plans             — list all plans for current user (default: 50 newest)
 *   /plans draft       — filter by status
 *   /plans active
 *   /plans completed
 *   /plans abandoned
 *
 * Always returns exactly one {@code PlanListBlock}; the CLI auto-opens its
 * picker on receipt of this block type.
 */
@Component
public class PlansCommand implements SlashCommand {

    private static final Logger log = LoggerFactory.getLogger(PlansCommand.class);
    // TODO: resolve from auth context — same TODO as TodoCommand.
    private static final String USER_ID = "bartek";
    private static final int LIMIT = 50;

    private final PlansRepository plansRepository;

    public PlansCommand(PlansRepository plansRepository) {
        this.plansRepository = plansRepository;
    }

    @Override
    public String name() {
        return "plans";
    }

    @Override
    public String description() {
        return "List your plans with resume/combine/abandon. Usage: /plans [draft|active|completed|abandoned]";
    }

    @Override
    public List<OutputBlock> execute(String args, String sessionId) {
        String filter = normaliseFilter(args);
        var plans = plansRepository.findByUser(USER_ID, filter, LIMIT);
        log.info("/plans: user={} filter={} → {} plans", USER_ID, filter, plans.size());
        if (plans.isEmpty() && filter == null) {
            // Empty-state hint on first-ever use. Filter misses don't get the hint —
            // they are a legitimate "no active plans right now" answer.
            return List.of(
                    new PlanListBlock(plans, null),
                    new TextBlock("No plans yet. Use `/plan <task>` to start one.", null));
        }
        return List.of(new PlanListBlock(plans, filter));
    }

    private static String normaliseFilter(String args) {
        if (args == null) return null;
        String trimmed = args.trim().toLowerCase();
        return switch (trimmed) {
            case "", "all" -> null;
            case "draft", "active", "completed", "abandoned" -> trimmed;
            default -> null;  // unknown filter → show all
        };
    }
}
