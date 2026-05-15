package ai.kukuvaia.advisors;

import ai.kukuvaia.provider.model.TaskComplexity;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Scores user messages against the 9 {@link TaskComplexity} values using
 * structural signals only — no word dictionaries.
 *
 * Signals: length, word/sentence count, code fences, file path regex,
 * question-mark count, planning-mode flag, first-turn flag.
 *
 * Returns the top-scoring complexity plus a confidence score
 * {@code (top − runnerUp) / top}. Caller uses the confidence to decide
 * whether to accept the heuristic or fall back to {@link LlmComplexityClassifier}.
 */
@Component
public class StructuralHeuristics {

    public record HeuristicResult(
            TaskComplexity top,
            double confidence,
            Map<TaskComplexity, Integer> scores
    ) {}

    private static final Pattern FILE_PATH = Pattern.compile(
            "\\S+\\.(java|kt|py|go|ts|tsx|js|jsx|md|sql|ya?ml|json|html|css|sh|xml|gradle|properties)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[.!?]\\s+|\\n+");

    public HeuristicResult score(String message, boolean inPlanningMode, boolean isFirstTurn) {
        Map<TaskComplexity, Integer> scores = emptyScores();

        // Default bias — unclassifiable messages land on supervisor tier (GENERATION).
        // Matches the old "DEFAULT" slot and prevents LLM fallback for empty input.
        scores.merge(TaskComplexity.GENERATION, 1, Integer::sum);

        if (message == null || message.isBlank()) {
            return resolveTop(scores);
        }

        int lenChars = message.length();
        int wordCount = message.trim().split("\\s+").length;
        long sentences = SENTENCE_SPLIT.splitAsStream(message).filter(s -> !s.isBlank()).count();
        long questionMarks = message.chars().filter(c -> c == '?').count();
        boolean hasCodeBlock = message.contains("```") || message.contains("`");
        boolean hasFilePath = FILE_PATH.matcher(message).find();

        if (lenChars <= 30) {
            scores.merge(TaskComplexity.RETRIEVAL, 2, Integer::sum);
            scores.merge(TaskComplexity.CLASSIFICATION, 1, Integer::sum);
        }
        if (lenChars > 300) {
            scores.merge(TaskComplexity.SYNTHESIS, 3, Integer::sum);
            scores.merge(TaskComplexity.STRATEGY, 1, Integer::sum);
            scores.merge(TaskComplexity.ANALYSIS, 1, Integer::sum);
        }
        if (wordCount <= 3) {
            scores.merge(TaskComplexity.RETRIEVAL, 2, Integer::sum);
        }
        if (sentences >= 3) {
            scores.merge(TaskComplexity.SYNTHESIS, 2, Integer::sum);
            scores.merge(TaskComplexity.STRATEGY, 1, Integer::sum);
        }
        if (hasCodeBlock) {
            // +4 — must beat stacked short-message signals (length≤30 + wordCount≤3
            // give RETRIEVAL=4) so short code snippets still land on GENERATION.
            scores.merge(TaskComplexity.GENERATION, 4, Integer::sum);
            scores.merge(TaskComplexity.TRANSFORMATION, 1, Integer::sum);
        }
        if (hasFilePath) {
            scores.merge(TaskComplexity.GENERATION, 1, Integer::sum);
            scores.merge(TaskComplexity.RETRIEVAL, 1, Integer::sum);
        }
        if (questionMarks >= 1) {
            scores.merge(TaskComplexity.ANALYSIS, 1, Integer::sum);
            scores.merge(TaskComplexity.RETRIEVAL, 1, Integer::sum);
        }
        if (questionMarks >= 2) {
            scores.merge(TaskComplexity.ANALYSIS, 2, Integer::sum);
        }
        if (inPlanningMode) {
            // +5 — must dominate competing short-message/length signals.
            // Planning mode is authoritative: user is inside /plan flow.
            scores.merge(TaskComplexity.STRATEGY, 5, Integer::sum);
            scores.merge(TaskComplexity.SYNTHESIS, 1, Integer::sum);
        }
        if (isFirstTurn) {
            scores.merge(TaskComplexity.GENERATION, 1, Integer::sum);
        }

        return resolveTop(scores);
    }

    private static Map<TaskComplexity, Integer> emptyScores() {
        EnumMap<TaskComplexity, Integer> m = new EnumMap<>(TaskComplexity.class);
        for (TaskComplexity c : TaskComplexity.values()) {
            m.put(c, 0);
        }
        return m;
    }

    private static HeuristicResult resolveTop(Map<TaskComplexity, Integer> scores) {
        TaskComplexity top = null;
        int topScore = Integer.MIN_VALUE;
        int runnerUp = Integer.MIN_VALUE;
        for (var e : scores.entrySet()) {
            int s = e.getValue();
            if (s > topScore) {
                runnerUp = topScore;
                topScore = s;
                top = e.getKey();
            } else if (s > runnerUp) {
                runnerUp = s;
            }
        }
        // Require the winner to have at least 2 points on top of the default
        // GENERATION bias — otherwise every neutral medium-length message would
        // score 1.0 confidence on the fallback bucket and starve the LLM path.
        double confidence = topScore < 2
                ? 0.0
                : (topScore - Math.max(runnerUp, 0)) / (double) topScore;
        return new HeuristicResult(top, confidence, Map.copyOf(scores));
    }
}
