package ai.kukuvaia.tools;

import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.WorkerResult;
import ai.kukuvaia.agent.subagent.WorkerTask;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("DelegationTools — LLM tool interface for specialist delegation")
@ExtendWith(MockitoExtension.class)
class DelegationToolsTest {

    @Mock private SubAgentFactory subAgentFactory;
    @Captor private ArgumentCaptor<List<WorkerTask>> tasksCaptor;

    private DelegationTools tools;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> allowedTools = Set.of("read_data");
    private final ToolContext context = new ToolContext(
            Map.of(DelegationTools.PERSONA_TOOLS_CONTEXT_KEY, allowedTools));

    @BeforeEach
    void setUp() {
        tools = new DelegationTools(subAgentFactory, objectMapper);
    }

    @Test
    @DisplayName("delegate_to_specialist — delegates to SubAgentFactory.execute()")
    void delegateToSpecialist_delegatesToFactory() {
        when(subAgentFactory.execute("analyze this", "analyst", ExecutionContext.INTERACTIVE, null, 0, allowedTools))
                .thenReturn("Analysis complete: 3 patterns found.");

        String result = tools.delegate_to_specialist("analyze this", "analyst", context);

        assertThat(result).isEqualTo("Analysis complete: 3 patterns found.");
        verify(subAgentFactory).execute("analyze this", "analyst", ExecutionContext.INTERACTIVE, null, 0, allowedTools);
    }

    @Test
    @DisplayName("delegate_to_workers — parses JSON and delegates parallel")
    void delegateToWorkers_parsesAndDelegatesParallel() {
        var workerResults = List.of(
                new WorkerResult("analyst", WorkerResult.WorkerStatus.COMPLETED, "result1", null, 100),
                new WorkerResult("summarizer", WorkerResult.WorkerStatus.COMPLETED, "result2", null, 80)
        );
        when(subAgentFactory.executeParallel(any(), eq(ExecutionContext.INTERACTIVE), eq(null), eq(allowedTools)))
                .thenReturn(workerResults);

        String tasksJson = """
                [
                    {"task": "analyze X", "specialistType": "analyst"},
                    {"task": "summarize Y", "specialistType": "summarizer"}
                ]
                """;

        String result = tools.delegate_to_workers(tasksJson, context);

        verify(subAgentFactory).executeParallel(tasksCaptor.capture(), eq(ExecutionContext.INTERACTIVE), eq(null), eq(allowedTools));

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
        String result = tools.delegate_to_workers("not valid json", context);

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

        String result = tools.delegate_to_workers(tasksJson, context);

        assertThat(result).contains("Delegation failed:");
        assertThat(result).contains("LLM unavailable");
    }

    @Test
    void delegate_missingScopeFailsClosed() {
        ToolContext missingScope = new ToolContext(Map.of());
        assertThatThrownBy(() -> tools.delegate_to_specialist("task", "analyst", missingScope))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tools.delegate_to_workers("[]", missingScope))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(subAgentFactory);
    }

    @Test
    void delegate_scopeIsNotPartOfModelInputSchema() {
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
        for (var callback : callbacks) {
            assertThat(callback.getToolDefinition().inputSchema())
                    .doesNotContain("toolContext", "personaTools", "allowedTools");
        }
    }

    @Test
    void delegate_modelCannotOverrideTrustedScope() {
        when(subAgentFactory.execute("task", "analyst", ExecutionContext.INTERACTIVE, null, 0, allowedTools))
                .thenReturn("ok");
        var callback = java.util.Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(tools).build().getToolCallbacks())
                .filter(tool -> tool.getToolDefinition().name().equals("delegate_to_specialist"))
                .findFirst().orElseThrow();

        callback.call("""
                {"task":"task","specialistType":"analyst","toolContext":{"kukuvaia.personaTools":["write_data"]}}
                """, context);

        verify(subAgentFactory).execute("task", "analyst", ExecutionContext.INTERACTIVE, null, 0, allowedTools);
    }
}
