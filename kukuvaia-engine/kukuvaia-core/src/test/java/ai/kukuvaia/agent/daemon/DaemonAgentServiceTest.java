package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.exec.JobInvocation;
import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.agent.daemon.exec.JobLimits;
import ai.kukuvaia.agent.daemon.exec.SpecialistExecutor;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.SubAgentGuard;
import ai.kukuvaia.agent.subagent.SubAgentResult;
import ai.kukuvaia.agent.subagent.SubAgentSpec;
import ai.kukuvaia.provider.model.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DaemonAgentService facade + SpecialistExecutor")
class DaemonAgentServiceTest {

    private static final SubAgentSpec ANALYST = new SubAgentSpec("analyst", "d", null, null, "p",
            List.of("read_file", "save_memory"), null, 4096, 5, 0.1, 60);

    @Test
    @DisplayName("facade runs an ad-hoc specialist job with limits taken from the specialist spec")
    void facade_buildsJobFromSpec() {
        DaemonRuntime runtime = mock(DaemonRuntime.class);
        SubAgentFactory factory = mock(SubAgentFactory.class);
        when(factory.spec("analyst")).thenReturn(Optional.of(ANALYST));
        DaemonTaskResult expected = new DaemonTaskResult(1, "ci", "analyst", null, DaemonTaskStatus.COMPLETED,
                "ok", null, 10, 5, "webhook:ci", Instant.now(), Instant.now());
        when(runtime.runSync(any(), any())).thenReturn(expected);

        DaemonTaskResult result = new DaemonAgentService(runtime, factory)
                .execute("ci", "analyst", "prompt", "webhook:ci", null);

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<DaemonJobDefinition> job = ArgumentCaptor.forClass(DaemonJobDefinition.class);
        ArgumentCaptor<JobRequest> request = ArgumentCaptor.forClass(JobRequest.class);
        verify(runtime).runSync(job.capture(), request.capture());
        assertThat(job.getValue().kind()).isEqualTo(JobKind.SPECIALIST);
        assertThat(job.getValue().target()).isEqualTo("analyst");
        assertThat(job.getValue().limits().maxTokens()).isEqualTo(4096);
        assertThat(job.getValue().limits().maxToolRounds()).isEqualTo(5);
        assertThat(job.getValue().limits().timeout()).as("deadline > specialist timeout")
                .isGreaterThan(Duration.ofSeconds(60));
        assertThat(request.getValue().prompt()).isEqualTo("prompt");
        assertThat(request.getValue().triggerSource()).isEqualTo("webhook:ci");
    }

    @Test
    @DisplayName("SpecialistExecutor: DAEMON context, daemon allowlist as tool scope (null when empty), tool-round cap")
    void specialistExecutor_passesDaemonScope() {
        SubAgentFactory factory = mock(SubAgentFactory.class);
        when(factory.executeForResult(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(new SubAgentResult("ok", 3, 4, true));
        DaemonProperties props = new DaemonProperties();
        var executor = new SpecialistExecutor(factory, props);
        var invocation = new JobInvocation(1, "j", "analyst", "prompt", Map.of(), null, Instant.now());
        var limits = new JobLimits(Duration.ofSeconds(10), 100, 2, 10, 1000);

        var outcome = executor.execute(invocation, limits);
        verify(factory).executeForResult("prompt", "analyst", ExecutionContext.DAEMON, null, 0, null, 2, 100, invocation.lifetime());
        assertThat(outcome.totalTokens()).isEqualTo(7);
        assertThat(outcome.usageReported()).isTrue();

        props.setAllowedTools(List.of("read_file"));
        executor.execute(invocation, limits);
        verify(factory).executeForResult("prompt", "analyst", ExecutionContext.DAEMON, null, 0, List.of("read_file"), 2, 100, invocation.lifetime());
    }

    @Test
    @DisplayName("allowlist intersection strips tools the daemon must not use (e.g. save_memory)")
    void allowlist_intersectionStripsSaveMemory() {
        DaemonProperties props = new DaemonProperties();
        props.setAllowedTools(List.of("read_file"));
        var guard = new SubAgentGuard(3);

        assertThat(guard.filterTools(ANALYST.tools(), props.toolScopeOrNull())).containsExactly("read_file");
        assertThat(guard.filterTools(ANALYST.tools(), new DaemonProperties().toolScopeOrNull()))
                .containsExactlyInAnyOrder("read_file", "save_memory");
    }
}
