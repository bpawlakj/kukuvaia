package ai.kukuvaia.advisors;

import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.provider.model.TaskComplexity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ComplexityDetector — explicit flag / heuristic / LLM fallback")
@ExtendWith(MockitoExtension.class)
class ComplexityDetectorTest {

    @Mock private StructuralHeuristics heuristics;
    @Mock private LlmComplexityClassifier llmClassifier;
    @Mock private PlanningModeService planningModeService;
    @Mock private JdbcTemplate jdbcTemplate;

    private ComplexityDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ComplexityDetector(heuristics, llmClassifier, planningModeService, jdbcTemplate);
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object.class)))
                .thenReturn(5);
    }

    @Test
    @DisplayName("escalate flag → STRATEGY via EXPLICIT_FLAG (skips heuristics and LLM)")
    void escalateFlag_explicitStrategy() {
        var r = detector.detect("anything", "s1", true);

        assertThat(r.complexity()).isEqualTo(TaskComplexity.STRATEGY);
        assertThat(r.source()).isEqualTo(ComplexityDetector.Source.EXPLICIT_FLAG);
        assertThat(r.confidence()).isEqualTo(1.0);
        verify(heuristics, never()).score(anyString(), anyBoolean(), anyBoolean());
        verify(llmClassifier, never()).classify(anyString());
    }

    @Test
    @DisplayName("confident heuristic skips LLM classifier")
    void confidentHeuristic_skipsLlm() {
        when(heuristics.score(anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(new StructuralHeuristics.HeuristicResult(
                        TaskComplexity.RETRIEVAL, 0.8,
                        Map.of(TaskComplexity.RETRIEVAL, 5, TaskComplexity.GENERATION, 1)));

        var r = detector.detect("hej", "s1", false);

        assertThat(r.source()).isEqualTo(ComplexityDetector.Source.HEURISTIC);
        assertThat(r.complexity()).isEqualTo(TaskComplexity.RETRIEVAL);
        assertThat(r.confidence()).isEqualTo(0.8);
        verify(llmClassifier, never()).classify(anyString());
    }

    @Test
    @DisplayName("ambiguous heuristic triggers LLM fallback")
    void ambiguousHeuristic_triggersLlm() {
        when(heuristics.score(anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(new StructuralHeuristics.HeuristicResult(
                        TaskComplexity.GENERATION, 0.0, Map.of()));
        when(llmClassifier.classify("ambiguous input"))
                .thenReturn(Optional.of(TaskComplexity.ANALYSIS));

        var r = detector.detect("ambiguous input", "s1", false);

        assertThat(r.source()).isEqualTo(ComplexityDetector.Source.LLM_FALLBACK);
        assertThat(r.complexity()).isEqualTo(TaskComplexity.ANALYSIS);
        verify(llmClassifier).classify("ambiguous input");
    }

    @Test
    @DisplayName("ambiguous heuristic + LLM empty → DEFAULT with heuristic top")
    void ambiguousHeuristic_llmEmpty_fallsBackToHeuristicTop() {
        when(heuristics.score(anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(new StructuralHeuristics.HeuristicResult(
                        TaskComplexity.SYNTHESIS, 0.1, Map.of()));
        when(llmClassifier.classify(anyString())).thenReturn(Optional.empty());

        var r = detector.detect("low-signal message", "s1", false);

        assertThat(r.source()).isEqualTo(ComplexityDetector.Source.DEFAULT);
        assertThat(r.complexity()).isEqualTo(TaskComplexity.SYNTHESIS);
    }

    @Test
    @DisplayName("planning mode flag is forwarded to heuristics as inPlanningMode=true")
    void planningMode_flagForwardedToHeuristics() {
        when(planningModeService.isInPlanningMode("s1")).thenReturn(true);
        when(heuristics.score(anyString(), anyBoolean(), anyBoolean()))
                .thenReturn(new StructuralHeuristics.HeuristicResult(
                        TaskComplexity.STRATEGY, 0.8, Map.of()));

        var r = detector.detect("let's do it", "s1", false);

        verify(heuristics).score("let's do it", true, false);
        assertThat(r.complexity()).isEqualTo(TaskComplexity.STRATEGY);
    }
}
