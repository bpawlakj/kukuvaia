package ai.kukuvaia.agent.subagent;

import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.config.ToolRegistryConfig;
import ai.kukuvaia.provider.service.LlmProviderService;
import ai.kukuvaia.security.ToolResultSanitizingAdvisor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@DisplayName("SubAgentFactory — parallel worker execution")
@ExtendWith(MockitoExtension.class)
class SubAgentFactoryParallelTest {

    @Mock private LlmProviderService providerService;
    @Mock private ToolResultSanitizingAdvisor toolResultAdvisor;
    @Mock private ToolRegistryConfig toolRegistry;
    @Mock private ChatModel workerModel1;
    @Mock private ChatModel workerModel2;

    private SubAgentGuard guard;
    private SubAgentFactory factory;

    @BeforeEach
    void setUp() {
        guard = new SubAgentGuard(3);

        // Create a spec loader that returns test specialists
        var specLoader = mock(SubAgentSpecLoader.class);
        when(specLoader.loadAll()).thenReturn(Map.of(
                "analyst", new SubAgentSpec("analyst", "test analyst", null, "worker",
                        "You are an analyst.", List.of(), "haiku", 1024, 5, 0.1, 10),
                "summarizer", new SubAgentSpec("summarizer", "test summarizer", null, "worker",
                        "You are a summarizer.", List.of(), "haiku", 1024, 1, 0.0, 10)
        ));

        factory = new SubAgentFactory(providerService, guard, toolResultAdvisor, toolRegistry, specLoader, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("executeParallel — guard rejects count over limit")
    void executeParallel_overLimit_throws() {
        var tasks = List.of(
                new WorkerTask("task1", "analyst"),
                new WorkerTask("task2", "analyst"),
                new WorkerTask("task3", "analyst"),
                new WorkerTask("task4", "analyst") // 4 > max 3
        );

        assertThatThrownBy(() -> factory.executeParallel(tasks, ExecutionContext.INTERACTIVE, null, null))
                .isInstanceOf(SubAgentGuard.ParallelLimitExceededException.class)
                .hasMessageContaining("4")
                .hasMessageContaining("max is 3");
    }

    @Test
    @DisplayName("executeParallel — resolves worker models for round-robin")
    void executeParallel_resolvesWorkerModels() {
        when(providerService.resolveWorkerModels()).thenReturn(List.of(workerModel1, workerModel2));

        var tasks = List.of(
                new WorkerTask("analyze X", "analyst"),
                new WorkerTask("summarize Y", "summarizer")
        );

        // Execute will fail at ChatClient.builder level (mocked ChatModel can't prompt)
        // but we can verify the models were resolved
        List<WorkerResult> results = factory.executeParallel(tasks, ExecutionContext.INTERACTIVE, null, null);

        // Both tasks should complete (as FAILED since ChatModel is a mock)
        assertThat(results).hasSize(2);
        assertThat(results.get(0).specialistType()).isEqualTo("analyst");
        assertThat(results.get(1).specialistType()).isEqualTo("summarizer");

        verify(providerService).resolveWorkerModels();
    }

    @Test
    @DisplayName("executeParallel — empty worker models falls back gracefully")
    void executeParallel_noWorkerModels_fallsBack() {
        when(providerService.resolveWorkerModels()).thenReturn(List.of());

        var tasks = List.of(new WorkerTask("task1", "analyst"));

        // Will fail at model resolution, but should return FAILED result, not crash
        List<WorkerResult> results = factory.executeParallel(tasks, ExecutionContext.INTERACTIVE, null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(WorkerResult.WorkerStatus.FAILED);
    }

    @Test
    @DisplayName("executeParallel — results preserve input order")
    void executeParallel_preservesOrder() {
        when(providerService.resolveWorkerModels()).thenReturn(List.of(workerModel1));

        var tasks = List.of(
                new WorkerTask("first", "analyst"),
                new WorkerTask("second", "summarizer"),
                new WorkerTask("third", "analyst")
        );

        List<WorkerResult> results = factory.executeParallel(tasks, ExecutionContext.INTERACTIVE, null, null);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).specialistType()).isEqualTo("analyst");
        assertThat(results.get(1).specialistType()).isEqualTo("summarizer");
        assertThat(results.get(2).specialistType()).isEqualTo("analyst");
    }

    @Test
    @DisplayName("executeParallel — unknown specialist type returns FAILED")
    void executeParallel_unknownSpecialist_fails() {
        when(providerService.resolveWorkerModels()).thenReturn(List.of(workerModel1));

        var tasks = List.of(new WorkerTask("task", "nonexistent"));

        List<WorkerResult> results = factory.executeParallel(tasks, ExecutionContext.INTERACTIVE, null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo(WorkerResult.WorkerStatus.FAILED);
        assertThat(results.get(0).error()).contains("Unknown specialist type");
    }

    @Test
    @DisplayName("availableSpecialists — returns loaded spec names")
    void availableSpecialists_returnsNames() {
        assertThat(factory.availableSpecialists()).containsExactlyInAnyOrder("analyst", "summarizer");
    }
}
