package ai.kukuvaia.tools;

import ai.kukuvaia.agent.daemon.ExecutionContext;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.WorkerResult;
import ai.kukuvaia.agent.subagent.WorkerTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DelegationTools — LLM tool interface for specialist delegation")
@ExtendWith(MockitoExtension.class)
class DelegationToolsTest {

    @Mock private SubAgentFactory subAgentFactory;
    @Captor private ArgumentCaptor<List<WorkerTask>> tasksCaptor;

    private DelegationTools tools;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tools = new DelegationTools(subAgentFactory, objectMapper);
    }

    @Test
    @DisplayName("delegate_to_specialist — delegates to SubAgentFactory.execute()")
    void delegateToSpecialist_delegatesToFactory() {
        when(subAgentFactory.execute("analyze this", "analyst", ExecutionContext.INTERACTIVE, null))
                .thenReturn("Analysis complete: 3 patterns found.");

        String result = tools.delegate_to_specialist("analyze this", "analyst");

        assertThat(result).isEqualTo("Analysis complete: 3 patterns found.");
        verify(subAgentFactory).execute("analyze this", "analyst", ExecutionContext.INTERACTIVE, null);
    }

    @Test
    @DisplayName("delegate_to_workers — parses JSON and delegates parallel")
    void delegateToWorkers_parsesAndDelegatesParallel() {
        var workerResults = List.of(
                new WorkerResult("analyst", WorkerResult.WorkerStatus.COMPLETED, "result1", null, 100),
                new WorkerResult("summarizer", WorkerResult.WorkerStatus.COMPLETED, "result2", null, 80)
        );
        when(subAgentFactory.executeParallel(any(), eq(ExecutionContext.INTERACTIVE), eq(null), eq(null)))
                .thenReturn(workerResults);

        String tasksJson = """
                [
                    {"task": "analyze X", "specialistType": "analyst"},
                    {"task": "summarize Y", "specialistType": "summarizer"}
                ]
                """;

        String result = tools.delegate_to_workers(tasksJson);

        verify(subAgentFactory).executeParallel(tasksCaptor.capture(), eq(ExecutionContext.INTERACTIVE), eq(null), eq(null));

        List<WorkerTask> capturedTasks = tasksCaptor.getValue();
        assertThat(capturedTasks).hasSize(2);
        assertThat(capturedTasks.get(0).task()).isEqualTo("analyze X");
        assertThat(capturedTasks.get(0).specialistType()).isEqualTo("analyst");
        assertThat(capturedTasks.get(1).task()).isEqualTo("summarize Y");
        assertThat(capturedTasks.get(1).specialistType()).isEqualTo("summarizer");

        // Result should be JSON with results
        assertThat(result).contains("analyst");
        assertThat(result).contains("COMPLETED");
    }

    @Test
    @DisplayName("delegate_to_workers — invalid JSON returns error message")
    void delegateToWorkers_invalidJson_returnsError() {
        String result = tools.delegate_to_workers("not valid json");

        assertThat(result).startsWith("Delegation failed:");
    }

    @Test
    @DisplayName("delegate_to_workers — factory exception returns error message")
    void delegateToWorkers_factoryException_returnsError() {
        when(subAgentFactory.executeParallel(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        String tasksJson = """
                [{"task": "test", "specialistType": "analyst"}]
                """;

        String result = tools.delegate_to_workers(tasksJson);

        assertThat(result).contains("Delegation failed:");
        assertThat(result).contains("LLM unavailable");
    }
}
