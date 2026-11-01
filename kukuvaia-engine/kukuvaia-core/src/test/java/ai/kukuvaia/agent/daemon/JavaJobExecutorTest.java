package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.DaemonTrigger;
import ai.kukuvaia.agent.daemon.def.Overlap;
import ai.kukuvaia.agent.daemon.exec.JavaJobExecutor;
import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.agent.daemon.exec.JobLimits;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.SubAgentResult;
import ai.kukuvaia.agent.subagent.SubAgentSpec;
import ai.kukuvaia.provider.model.ExecutionContext;
import ai.kukuvaia.security.ErrorSanitizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("JavaJobExecutor — DaemonJob beans under the daemon runtime")
class JavaJobExecutorTest {

    private final DaemonRuntimeTest.FakeStore store = new DaemonRuntimeTest.FakeStore();
    private final AtomicInteger ledger = new AtomicInteger();
    private final SubAgentFactory factory = mock(SubAgentFactory.class);
    private final DaemonProperties properties = new DaemonProperties();
    private DaemonRuntime runtime;

    private static DaemonJob job(String name, java.util.function.Function<JobContext, JobResult> body) {
        return new DaemonJob() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public JobResult run(JobContext context) {
                return body.apply(context);
            }
        };
    }

    private DaemonRuntime runtime(int budget, DaemonJob... jobs) {
        DaemonBudgetGuard guard = DaemonRuntimeTest.budget(budget, ledger, new ArrayList<>());
        var executor = new JavaJobExecutor(List.of(jobs), factory, guard, properties);
        runtime = new DaemonRuntime(List.of(executor), guard, new DaemonScheduleGuard(), store, properties,
                new ErrorSanitizer(), new SimpleMeterRegistry());
        return runtime;
    }

    private static DaemonJobDefinition def(String target, Duration timeout) {
        return new DaemonJobDefinition("job-" + target, "", JobKind.JAVA, target, List.of("repo"), null,
                List.of(new DaemonTrigger.Manual()), null, JobLimits.defaults().withTimeout(timeout),
                Overlap.skip(), List.of("log"), true, "test");
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) runtime.shutdown();
    }

    @Test
    @DisplayName("a Java job runs with the sanitized fields and returns summary + structured output")
    void runsJob() {
        runtime(100_000, job("scan", ctx -> new JobResult("scanned " + ctx.fields().get("repo"),
                JsonMapper.builder().build().createObjectNode().put("findings", 3))));

        DaemonTaskResult r = runtime.runSync(def("scan", Duration.ofSeconds(5)),
                new JobRequest(null, Map.of("repo", "hub"), "cron", null));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.COMPLETED);
        assertThat(r.result()).isEqualTo("scanned hub");
        assertThat(r.tokenUsage()).isZero();
    }

    @Test
    @DisplayName("callSpecialist goes through DAEMON context + budget; tokens accounted on the task")
    void callSpecialist_accounted() {
        when(factory.spec("analyst")).thenReturn(Optional.of(new SubAgentSpec("analyst", "", null, null, "p",
                List.of(), null, 1000, 3, 0.1, 30)));
        when(factory.executeForResult(eq("summarize"), eq("analyst"), eq(ExecutionContext.DAEMON), any(), anyInt(), any()))
                .thenReturn(new SubAgentResult("summary", 40, 10, true));
        runtime(100_000, job("ask", ctx -> JobResult.of(ctx.callSpecialist("analyst", "summarize").content())));

        DaemonTaskResult r = runtime.runSync(def("ask", Duration.ofSeconds(5)), JobRequest.of(null, "manual"));

        assertThat(r.result()).isEqualTo("summary");
        assertThat(r.tokenUsage()).isEqualTo(50);
        assertThat(ledger.get()).as("daily budget charged the task's usage once").isEqualTo(50);
    }

    @Test
    @DisplayName("callSpecialist is refused when the daily budget cannot cover the call")
    void callSpecialist_budgetRefused() {
        when(factory.spec("analyst")).thenReturn(Optional.of(new SubAgentSpec("analyst", "", null, null, "p",
                List.of(), null, 1000, 3, 0.1, 30)));
        runtime(500, job("ask", ctx -> JobResult.of(ctx.callSpecialist("analyst", "x").content())));

        DaemonTaskResult r = runtime.runSync(def("ask", Duration.ofSeconds(5)), JobRequest.of(null, "manual"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.FAILED);
        assertThat(r.error()).contains("budget");
    }

    @Test
    @DisplayName("a job past its deadline is cancelled (TIMEOUT) and sees isCancelled()")
    void deadline_cancelsJob() throws Exception {
        AtomicBoolean sawCancel = new AtomicBoolean();
        var finished = new java.util.concurrent.CountDownLatch(1);
        runtime(100_000, job("slow", ctx -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                sawCancel.set(ctx.isCancelled());
            } finally {
                finished.countDown();
            }
            return JobResult.of("late");
        }));

        DaemonTaskResult r = runtime.runSync(def("slow", Duration.ofMillis(300)), JobRequest.of(null, "cron"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.TIMEOUT);
        assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(sawCancel).isTrue();
    }

    @Test
    @DisplayName("unknown job bean → FAILED with a clear error; duplicate bean names fail at startup")
    void unknownAndDuplicate() {
        runtime(100_000, job("a", ctx -> JobResult.of("a")));
        DaemonTaskResult r = runtime.runSync(def("missing", Duration.ofSeconds(5)), JobRequest.of(null, "m"));
        assertThat(r.status()).isEqualTo(DaemonTaskStatus.FAILED);
        assertThat(r.error()).contains("missing");

        assertThatThrownBy(() -> new JavaJobExecutor(List.of(job("x", c -> null), job("x", c -> null)),
                factory, mock(DaemonBudgetGuard.class), properties))
                .isInstanceOf(IllegalStateException.class);
    }
}
