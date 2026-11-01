package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.DaemonJobRegistry;
import ai.kukuvaia.agent.daemon.exec.DaemonExecutor;
import ai.kukuvaia.agent.daemon.exec.ExecutionOutcome;
import ai.kukuvaia.agent.daemon.exec.JobInvocation;
import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.agent.daemon.exec.JobLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DaemonCronScheduler — dedicated scheduler, kill switch")
class DaemonCronSchedulerTest {

    private static final DaemonExecutor JAVA = new DaemonExecutor() {
        @Override
        public JobKind kind() {
            return JobKind.JAVA;
        }

        @Override
        public ExecutionOutcome execute(JobInvocation invocation, JobLimits limits) {
            return ExecutionOutcome.ofText("x");
        }
    };

    private static DaemonJobRegistry registry(String cron) {
        return new DaemonJobRegistry(List.of(JAVA), List.of(
                Map.entry("a.yaml", "name: every-second\nkind: java\ntarget: scan\ntriggers: [ { cron: '" + cron + "' } ]\n"),
                Map.entry("b.yaml", "name: manual-only\nkind: java\ntarget: scan\n")));
    }

    @Test
    @DisplayName("cron triggers fire on the dedicated pool and only submit to the runtime")
    void firesCronJobs() {
        DaemonRuntime runtime = mock(DaemonRuntime.class);
        when(runtime.submit(any(), any()))
                .thenReturn(new DaemonRuntime.Submission(1, DaemonTaskResult.DaemonTaskStatus.QUEUED, null));
        var scheduler = new DaemonCronScheduler(registry("* * * * * *"), runtime, new DaemonProperties());

        scheduler.start();
        try {
            assertThat(scheduler.scheduledCount()).isEqualTo(1);
            ArgumentCaptor<DaemonJobDefinition> job = ArgumentCaptor.forClass(DaemonJobDefinition.class);
            ArgumentCaptor<JobRequest> request = ArgumentCaptor.forClass(JobRequest.class);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    verify(runtime, atLeastOnce()).submit(job.capture(), request.capture()));
            assertThat(job.getValue().name()).isEqualTo("every-second");
            assertThat(request.getValue().triggerSource()).isEqualTo("cron");
        } finally {
            scheduler.stop();
        }
    }

    @Test
    @DisplayName("kill switch: nothing is scheduled")
    void killSwitch() throws Exception {
        DaemonRuntime runtime = mock(DaemonRuntime.class);
        var props = new DaemonProperties();
        props.setEnabled(false);
        var scheduler = new DaemonCronScheduler(registry("* * * * * *"), runtime, props);

        scheduler.start();
        Thread.sleep(1500);
        scheduler.stop();

        assertThat(scheduler.scheduledCount()).isZero();
        verify(runtime, never()).submit(any(), any());
    }
}
