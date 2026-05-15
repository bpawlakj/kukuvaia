package ai.kukuvaia.advisors;

import ai.kukuvaia.agent.PlanningModeService;
import ai.kukuvaia.agent.SessionEscalationService;
import ai.kukuvaia.provider.service.ChatModelCache;
import ai.kukuvaia.provider.service.ComplexityMappingService;
import ai.kukuvaia.provider.repository.ModelRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ModelRoutingAdvisor — complexity classification")
@ExtendWith(MockitoExtension.class)
class ModelRoutingAdvisorTest {

    @Mock private ChatModelCache chatModelCache;
    @Mock private ModelRepository modelRepository;
    @Mock private PlanningModeService planningModeService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ComplexityDetector complexityDetector;
    @Mock private ComplexityMappingService complexityMappingService;
    @Mock private LlmComplexityClassifier llmComplexityClassifier;

    private ModelRoutingAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new ModelRoutingAdvisor(chatModelCache, modelRepository,
                new TaskClassifier(), planningModeService, jdbcTemplate,
                complexityDetector, complexityMappingService,
                new SessionEscalationService(), new SimpleMeterRegistry());
    }

    // --- FAST (greeting → worker) ---

    @Test
    @DisplayName("classify — 'hi' → FAST")
    void classify_hi_fast() {
        assertThat(advisor.classify("hi", false)).isEqualTo(ModelRoutingAdvisor.RoutingDecision.FAST);
    }

    @Test
    @DisplayName("classify — 'hello!' → FAST")
    void classify_hello_fast() {
        assertThat(advisor.classify("hello!", false)).isEqualTo(ModelRoutingAdvisor.RoutingDecision.FAST);
    }

    @Test
    @DisplayName("classify — 'cześć' → FAST")
    void classify_czesc_fast() {
        assertThat(advisor.classify("cześć", false)).isEqualTo(ModelRoutingAdvisor.RoutingDecision.FAST);
    }

    @Test
    @DisplayName("classify — 'hey' → FAST")
    void classify_hey_fast() {
        assertThat(advisor.classify("hey", false)).isEqualTo(ModelRoutingAdvisor.RoutingDecision.FAST);
    }

    @Test
    @DisplayName("classify — 'thanks' → FAST")
    void classify_thanks_fast() {
        assertThat(advisor.classify("thanks", false)).isEqualTo(ModelRoutingAdvisor.RoutingDecision.FAST);
    }

    // --- DEFAULT (normal tasks → default/sonnet) ---

    @Test
    @DisplayName("classify — normal question → DEFAULT")
    void classify_normalQuestion_default() {
        assertThat(advisor.classify("explain how dependency injection works in Spring", false))
                .isEqualTo(ModelRoutingAdvisor.RoutingDecision.DEFAULT);
    }

    @Test
    @DisplayName("classify — code request → DEFAULT")
    void classify_codeRequest_default() {
        assertThat(advisor.classify("write a function to calculate fibonacci numbers", false))
                .isEqualTo(ModelRoutingAdvisor.RoutingDecision.DEFAULT);
    }

    @Test
    @DisplayName("classify — long greeting → DEFAULT (too long for FAST)")
    void classify_longGreeting_default() {
        assertThat(advisor.classify("hi there, how are you doing today? I wanted to discuss something", false))
                .isEqualTo(ModelRoutingAdvisor.RoutingDecision.DEFAULT);
    }

    // --- ESCALATE (complex/loop → advisor/opus) ---

    @Test
    @DisplayName("classify — escalate flag → ESCALATE")
    void classify_escalateFlag_escalate() {
        assertThat(advisor.classify("hi", true))
                .isEqualTo(ModelRoutingAdvisor.RoutingDecision.ESCALATE);
    }

    @Test
    @DisplayName("classify — 'think harder' → ESCALATE")
    void classify_thinkHarder_escalate() {
        assertThat(advisor.classify("think harder about this architecture problem", false))
                .isEqualTo(ModelRoutingAdvisor.RoutingDecision.ESCALATE);
    }

    @Test
    @DisplayName("classify — 'analyze deeply' → ESCALATE")
    void classify_analyzeDeeply_escalate() {
        assertThat(advisor.classify("analyze deeply the performance bottleneck", false))
                .isEqualTo(ModelRoutingAdvisor.RoutingDecision.ESCALATE);
    }

    @Test
    @DisplayName("classify — Polish escalation → ESCALATE")
    void classify_polishEscalation_escalate() {
        assertThat(advisor.classify("pomyśl głębiej o tym problemie", false))
                .isEqualTo(ModelRoutingAdvisor.RoutingDecision.ESCALATE);
    }

    // --- Edge cases ---

    @Test
    @DisplayName("classify — empty string → DEFAULT")
    void classify_empty_default() {
        assertThat(advisor.classify("", false))
                .isEqualTo(ModelRoutingAdvisor.RoutingDecision.DEFAULT);
    }

    @Test
    @DisplayName("classify — escalate flag overrides greeting")
    void classify_escalateFlagOverridesGreeting() {
        assertThat(advisor.classify("hi", true))
                .isEqualTo(ModelRoutingAdvisor.RoutingDecision.ESCALATE);
    }

    // --- Role mapping ---

    @Test
    @DisplayName("FAST decision maps to 'worker' role")
    void fast_mapsToWorkerRole() {
        assertThat(ModelRoutingAdvisor.RoutingDecision.FAST.role()).isEqualTo("worker");
    }

    @Test
    @DisplayName("DEFAULT decision maps to 'supervisor' role")
    void default_mapsToSupervisorRole() {
        assertThat(ModelRoutingAdvisor.RoutingDecision.DEFAULT.role()).isEqualTo("supervisor");
    }

    @Test
    @DisplayName("ESCALATE decision maps to 'advisor' role")
    void escalate_mapsToAdvisorRole() {
        assertThat(ModelRoutingAdvisor.RoutingDecision.ESCALATE.role()).isEqualTo("advisor");
    }
}
