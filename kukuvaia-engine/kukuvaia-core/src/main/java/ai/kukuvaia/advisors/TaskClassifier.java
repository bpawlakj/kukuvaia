package ai.kukuvaia.advisors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Feature-scored classifier for per-turn model tier routing.
 *
 * Replaces the old keyword-binary decision in {@code ModelRoutingAdvisor.classify}
 * with additive scoring across 12 cheap-to-extract features. The highest-scoring
 * tier wins; ties fall back to DEFAULT (the safe choice). No LLM calls.
 *
 * Design notes:
 * <ul>
 *   <li>All features are O(n) on message length or cheaper. Single regex compile per feature.</li>
 *   <li>Planning mode and first-turn state come from the caller — this class does not know about
 *       {@code PlanningModeService} directly, keeping it reusable and testable.</li>
 *   <li>Scores are hand-tuned. Unit tests pin down behaviour for concrete cases; re-tune
 *       when those fail. No machine learning here — that is the Phase 2 fallback.</li>
 * </ul>
 */
@Component
public class TaskClassifier {

    private static final Logger log = LoggerFactory.getLogger(TaskClassifier.class);

    public enum Tier { FAST, DEFAULT, ESCALATE }

    public record ClassificationResult(
            Tier tier,
            Map<String, Integer> scores,
            String reason
    ) {}

    // Reuses the exact same sets as ModelRoutingAdvisor so the two stay aligned.
    private static final Set<String> GREETING_WORDS = Set.of(
            "hi", "hello", "hey", "cześć", "czesc", "hej", "siema", "yo",
            "thanks", "thx", "dzięki", "dzieki", "bye", "ok", "okej");

    private static final Set<String> COMPLEXITY_MARKERS = Set.of(
            "think harder", "think hard", "think deeper", "think carefully",
            "analyze deeply", "be thorough", "reason step by step",
            "pomyśl głębiej", "pomyśl dobrze", "pomyśl mocno",
            "pomysl glebiej", "pomysl dobrze", "pomysl mocno",
            "przeanalizuj dokładnie", "przeanalizuj dokladnie",
            "zastanów się dobrze", "zastanow sie dobrze");

    private static final Set<String> PLANNING_INTENT = Set.of(
            "plan", "zaplanuj", "zaplanować", "zaplanowac", "organize",
            "zorganizuj", "strategy", "strategia", "opracuj", "roadmap");

    private static final Set<String> ANALYSIS_INTENT = Set.of(
            "analyze", "analyse", "przeanalizuj", "analiza",
            "explain", "wyjaśnij", "wyjasnij", "wyjaśnienie",
            "compare", "porównaj", "porownaj",
            "summarize", "summarise", "streszcz", "streszczenie", "podsumuj",
            "evaluate", "oceń", "ocen");

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile(
            "\\S+\\.(java|kt|py|go|ts|tsx|js|jsx|md|sql|ya?ml|json|html|css|sh|xml|gradle|properties)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[.!?]\\s+|\\n+");

    /**
     * Classify a user turn.
     *
     * @param message the raw user message
     * @param escalateFlag explicit user escalation (context key {@code kukuvaia.escalate}) — hard override
     * @param inPlanningMode whether the session currently has an active planning phase
     * @param priorMessageCount number of prior messages in the session (0 = first turn)
     */
    public ClassificationResult classify(String message, boolean escalateFlag,
                                         boolean inPlanningMode, int priorMessageCount) {
        if (escalateFlag) {
            return result(Tier.ESCALATE, baseScores(), "escalate_flag");
        }
        if (message == null || message.isBlank()) {
            return result(Tier.DEFAULT, baseScores(), "empty_message");
        }

        Map<String, Integer> scores = baseScores();
        String lower = message.toLowerCase();
        int lenChars = message.length();
        int wordCount = message.trim().split("\\s+").length;

        String topFeature = "length_default";

        // Feature: length
        if (lenChars <= 30) {
            scores.merge("FAST", 3, Integer::sum);
            topFeature = "short_message";
        } else if (lenChars > 200) {
            scores.merge("FAST", -2, Integer::sum);
            scores.merge("DEFAULT", 1, Integer::sum);
            scores.merge("ESCALATE", 2, Integer::sum);
            topFeature = "long_message";
        }

        if (wordCount <= 3) {
            scores.merge("FAST", 2, Integer::sum);
        }

        // Feature: greeting
        if (containsWord(lower, GREETING_WORDS)) {
            scores.merge("FAST", 2, Integer::sum);
            topFeature = "greeting";
        }

        // Feature: complexity marker (strong escalate)
        if (containsAny(lower, COMPLEXITY_MARKERS)) {
            scores.merge("FAST", -3, Integer::sum);
            scores.merge("ESCALATE", 5, Integer::sum);
            topFeature = "complexity_marker";
        }

        // Feature: question mark
        if (message.contains("?")) {
            scores.merge("FAST", -1, Integer::sum);
            scores.merge("DEFAULT", 1, Integer::sum);
            scores.merge("ESCALATE", 1, Integer::sum);
        }

        // Feature: code block
        if (message.contains("```") || message.contains("`")) {
            scores.merge("FAST", -3, Integer::sum);
            scores.merge("DEFAULT", 2, Integer::sum);
            scores.merge("ESCALATE", 1, Integer::sum);
            topFeature = "code_block";
        }

        // Feature: file path
        if (FILE_PATH_PATTERN.matcher(message).find()) {
            scores.merge("FAST", -2, Integer::sum);
            scores.merge("DEFAULT", 2, Integer::sum);
            topFeature = "file_path";
        }

        // Feature: planning intent
        if (containsWord(lower, PLANNING_INTENT)) {
            scores.merge("FAST", -3, Integer::sum);
            scores.merge("DEFAULT", 3, Integer::sum);
            scores.merge("ESCALATE", 1, Integer::sum);
            topFeature = "planning_intent";
        }

        // Feature: analysis intent (analyze / summarize / compare).
        // Single match → DEFAULT (supervisor handles most analysis well, opus is overkill).
        // Multiple matches in one message → ESCALATE (real complexity signal).
        int analysisHits = countWordMatches(lower, ANALYSIS_INTENT);
        if (analysisHits >= 1) {
            scores.merge("FAST", -2, Integer::sum);
            scores.merge("DEFAULT", 2, Integer::sum);
            topFeature = "analysis_intent";
        }
        if (analysisHits >= 2) {
            // Two analysis verbs in one message = user wants substantial reasoning.
            scores.merge("ESCALATE", 3, Integer::sum);
            scores.merge("DEFAULT", -1, Integer::sum);
            topFeature = "multi_analysis_intent";
        }

        // Feature: planning mode session
        if (inPlanningMode) {
            scores.merge("FAST", -5, Integer::sum);
            scores.merge("DEFAULT", 3, Integer::sum);
            scores.merge("ESCALATE", 1, Integer::sum);
            topFeature = "planning_mode";
        }

        // Feature: first turn bias (onboarding deserves supervisor)
        if (priorMessageCount == 0) {
            scores.merge("FAST", -2, Integer::sum);
            scores.merge("DEFAULT", 2, Integer::sum);
        }

        // Feature: multiple sentences
        long sentences = SENTENCE_SPLIT.splitAsStream(message).filter(s -> !s.isBlank()).count();
        if (sentences >= 3) {
            scores.merge("FAST", -1, Integer::sum);
            scores.merge("DEFAULT", 1, Integer::sum);
            scores.merge("ESCALATE", 2, Integer::sum);
        }

        Tier winner = pickWinner(scores);
        log.debug("Classification: tier={} topFeature={} scores={}", winner, topFeature, scores);
        return result(winner, scores, topFeature);
    }

    /** Base scores — DEFAULT gets a slight edge so empty/ambiguous input lands there. */
    private static Map<String, Integer> baseScores() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("FAST", 0);
        m.put("DEFAULT", 1);
        m.put("ESCALATE", 0);
        return m;
    }

    private static Tier pickWinner(Map<String, Integer> scores) {
        int fast = scores.getOrDefault("FAST", 0);
        int defaultS = scores.getOrDefault("DEFAULT", 0);
        int escalate = scores.getOrDefault("ESCALATE", 0);
        // Tie-break: ESCALATE > DEFAULT > FAST at equal scores.
        // ESCALATE-first because under-routing (cheap model for hard task) is worse
        // than over-routing (expensive model for easy task).
        int max = Math.max(fast, Math.max(defaultS, escalate));
        if (escalate == max) return Tier.ESCALATE;
        if (defaultS == max) return Tier.DEFAULT;
        return Tier.FAST;
    }

    private static ClassificationResult result(Tier tier, Map<String, Integer> scores, String reason) {
        return new ClassificationResult(tier, scores, reason);
    }

    /** True if any whole-word in lower matches any element of words (after non-letter stripping). */
    private static boolean containsWord(String lower, Set<String> words) {
        return countWordMatches(lower, words) > 0;
    }

    /** Count how many whole-word tokens in lower match elements of words. */
    private static int countWordMatches(String lower, Set<String> words) {
        int n = 0;
        for (String w : lower.split("\\s+")) {
            String clean = w.replaceAll("[^a-ząćęłńóśźż]", "");
            if (words.contains(clean)) n++;
        }
        return n;
    }

    /** True if lower contains any of the given phrases as a substring. */
    private static boolean containsAny(String lower, Set<String> phrases) {
        for (String p : phrases) {
            if (lower.contains(p)) return true;
        }
        return false;
    }
}
