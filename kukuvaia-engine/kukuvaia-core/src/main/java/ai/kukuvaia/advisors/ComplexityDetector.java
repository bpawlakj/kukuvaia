package ai.kukuvaia.advisors;

import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.provider.registry.TaskComplexity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Entry point for complexity-driven routing: maps a user message to a
 * {@link TaskComplexity} value without keyword dictionaries.
 *
 * Pipeline:
 * <ol>
 *   <li>Hard override — explicit escalate flag → {@link TaskComplexity#STRATEGY}.</li>
 *   <li>{@link StructuralHeuristics} — if confidence ≥ 0.3, accept the heuristic top.</li>
 *   <li>{@link LlmComplexityClassifier} — worker-tier fallback for ambiguous cases.</li>
 *   <li>Default — heuristic top (even at low confidence) if LLM also fails.</li>
 * </ol>
 *
 * Output carries {@link Source} so callers can record which path decided
 * the routing (telemetry / shadow-mode diffing).
 */
@Component
public class ComplexityDetector {

    private static final Logger log = LoggerFactory.getLogger(ComplexityDetector.class);
    private static final double CONFIDENCE_THRESHOLD = 0.3;

    public enum Source { EXPLICIT_FLAG, HEURISTIC, LLM_FALLBACK, DEFAULT }

    public record DetectionResult(
            TaskComplexity complexity,
            Source source,
            double confidence,
            Map<TaskComplexity, Integer> heuristicScores
    ) {}

    private final StructuralHeuristics heuristics;
    private final LlmComplexityClassifier llmClassifier;
    private final PlanningModeService planningModeService;
    private final JdbcTemplate jdbcTemplate;

    public ComplexityDetector(StructuralHeuristics heuristics,
                              LlmComplexityClassifier llmClassifier,
                              PlanningModeService planningModeService,
                              JdbcTemplate jdbcTemplate) {
        this.heuristics = heuristics;
        this.llmClassifier = llmClassifier;
        this.planningModeService = planningModeService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public DetectionResult detect(String message, String sessionId, boolean escalateFlag) {
        if (escalateFlag) {
            log.debug("ComplexityDetector: escalate flag set → STRATEGY");
            return new DetectionResult(TaskComplexity.STRATEGY, Source.EXPLICIT_FLAG, 1.0, Map.of());
        }

        boolean inPlanning = sessionId != null && planningModeService.isInPlanningMode(sessionId);
        boolean isFirstTurn = sessionId != null && countMessages(sessionId) == 0;

        var heuristic = heuristics.score(message, inPlanning, isFirstTurn);

        if (heuristic.confidence() >= CONFIDENCE_THRESHOLD) {
            log.debug("ComplexityDetector: heuristic confident ({}) → {}",
                    heuristic.confidence(), heuristic.top());
            return new DetectionResult(heuristic.top(), Source.HEURISTIC,
                    heuristic.confidence(), heuristic.scores());
        }

        log.debug("ComplexityDetector: heuristic ambiguous ({}) → LLM fallback",
                heuristic.confidence());
        return llmClassifier.classify(message)
                .map(c -> new DetectionResult(c, Source.LLM_FALLBACK,
                        heuristic.confidence(), heuristic.scores()))
                .orElseGet(() -> {
                    TaskComplexity fallback = heuristic.top() != null
                            ? heuristic.top()
                            : TaskComplexity.GENERATION;
                    log.debug("ComplexityDetector: LLM fallback failed → {} (default)", fallback);
                    return new DetectionResult(fallback, Source.DEFAULT,
                            heuristic.confidence(), heuristic.scores());
                });
    }

    private int countMessages(String sessionId) {
        try {
            Integer c = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kukuvaia.spring_ai_chat_memory WHERE conversation_id = ?",
                    Integer.class, sessionId);
            return c != null ? c : 0;
        } catch (Exception e) {
            return 1;
        }
    }
}
