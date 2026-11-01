package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.DaemonTrigger;
import ai.kukuvaia.agent.daemon.def.Overlap;
import ai.kukuvaia.agent.daemon.exec.DaemonExecutor;
import ai.kukuvaia.agent.daemon.exec.ExecutionOutcome;
import ai.kukuvaia.agent.daemon.exec.JobInvocation;
import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.agent.daemon.exec.JobLimits;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.provider.service.LlmProviderService;
import ai.kukuvaia.security.ErrorSanitizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DaemonRuntime — guards, lifecycle and fail-closed token accounting")
class DaemonRuntimeTest {

    /** In-memory audit table. */
    static class FakeStore extends DaemonTaskStore {
        final AtomicLong ids = new AtomicLong();
        final Map<Long, String> status = new ConcurrentHashMap<>();
        final Map<Long, String> error = new ConcurrentHashMap<>();
        final List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile boolean failOnComplete;

        FakeStore() {
            super(null);
        }

        @Override
        public long insertQueued(DaemonJobDefinition job, JobRequest request) {
            long id = ids.incrementAndGet();
            status.put(id, "QUEUED");
            return id;
        }

        @Override
        public long insertSkipped(DaemonJobDefinition job, JobRequest request, String reason) {
            long id = ids.incrementAndGet();
            status.put(id, "SKIPPED");
            error.put(id, reason);
            return id;
        }

        @Override
        public void markRunning(long id) {
            status.put(id, "RUNNING");
        }

        @Override
        public void markCompleted(long id, ExecutionOutcome outcome, long durationMs) {
            order.add("finalize:" + id);
            if (failOnComplete) throw new IllegalStateException("db down");
            status.put(id, "COMPLETED");
        }

        @Override
        public void markFinished(long id, DaemonTaskStatus s, String err, long durationMs) {
            order.add("finalize:" + id);
            status.put(id, s.name());
            if (err != null) error.put(id, err);
        }
    }

    /** Budget guard with an in-memory ledger but the real reservation logic. */
    static DaemonBudgetGuard budget(int dailyBudget, AtomicInteger ledger, List<String> order) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenAnswer(inv -> ledger.get());
        // recordUsage(taskId, tokens) → jdbc.update(sql, tokens, taskId)
        // recordUsage SETS token_usage for one task (UPDATE ... SET token_usage = ?), the daily total is
        // the sum over tasks — model exactly that. (Object) casts select update(String, Object...).
        Map<Object, Integer> perTask = new ConcurrentHashMap<>();
        when(jdbc.update(anyString(), (Object) any(), (Object) any())).thenAnswer(inv -> {
            perTask.put(inv.getArgument(2), (Integer) inv.getArgument(1));
            ledger.set(perTask.values().stream().mapToInt(Integer::intValue).sum());
            order.add("usage:" + inv.getArgument(2));
            return 1;
        });
        DaemonBudgetGuard guard = new DaemonBudgetGuard(jdbc);
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "dailyTokenBudget", dailyBudget);
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "alertThreshold", 0.8);
        org.springframework.test.util.ReflectionTestUtils.setField(guard, "daemonEnabled", true);
        return guard;
    }

    static DaemonExecutor executor(JobKind kind, Function<JobInvocation, ExecutionOutcome> behaviour) {
        return new DaemonExecutor() {
            @Override
            public JobKind kind() {
                return kind;
            }

            @Override
            public ExecutionOutcome execute(JobInvocation invocation, JobLimits limits) {
                return behaviour.apply(invocation);
            }
        };
    }

    static DaemonJobDefinition job(String name, JobKind kind, JobLimits limits, Overlap overlap) {
        return new DaemonJobDefinition(name, "", kind, "target", List.of(), "prompt",
                List.of(new DaemonTrigger.Manual()), null, limits, overlap, List.of("log"), true, "test");
    }

    static final JobLimits LIMITS = new JobLimits(Duration.ofSeconds(5), 1000, 3, 10, 5000);

    private final FakeStore store = new FakeStore();
    private final AtomicInteger ledger = new AtomicInteger();
    private final DaemonProperties properties = new DaemonProperties();
    private DaemonRuntime runtime;

    private DaemonRuntime runtime(int dailyBudget, DaemonExecutor... executors) {
        runtime = new DaemonRuntime(List.of(executors), budget(dailyBudget, ledger, store.order),
                new DaemonScheduleGuard(), store, properties, new ErrorSanitizer(), new SimpleMeterRegistry());
        return runtime;
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) runtime.shutdown();
    }

    @Test
    @DisplayName("completed run: QUEUED → RUNNING → COMPLETED, reported usage charged")
    void completed_chargesReportedUsage() {
        runtime(100_000, executor(JobKind.SPECIALIST, inv ->
                new ExecutionOutcome("done", null, 70, 30, true, "daemon", "m")));

        DaemonTaskResult r = runtime.runSync(job("j", JobKind.SPECIALIST, LIMITS, Overlap.skip()), JobRequest.of("p", "manual"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.COMPLETED);
        assertThat(r.result()).isEqualTo("done");
        assertThat(r.tokenUsage()).isEqualTo(100);
        assertThat(ledger.get()).isEqualTo(100);
        assertThat(store.status.get(r.taskId())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("no reported usage → charged the run's token_budget (fail closed)")
    void noUsage_chargesMaxTokens() {
        runtime(100_000, executor(JobKind.SPECIALIST, inv -> new ExecutionOutcome("x", null, 0, 0, false, null, null)));

        DaemonTaskResult r = runtime.runSync(job("j", JobKind.SPECIALIST, LIMITS, Overlap.skip()), JobRequest.of("p", "manual"));

        assertThat(r.tokenUsage()).isEqualTo(5000);
        assertThat(ledger.get()).isEqualTo(5000);
    }

    @Test
    @DisplayName("deadline exceeded → TIMEOUT, the run is interrupted, token_budget charged")
    void deadline_timeoutCancelsAndCharges() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        runtime(100_000, executor(JobKind.SPECIALIST, inv -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return ExecutionOutcome.ofText("late");
        }));
        JobLimits fast = LIMITS.withTimeout(Duration.ofMillis(300));

        DaemonTaskResult r = runtime.runSync(job("slow", JobKind.SPECIALIST, fast, Overlap.skip()), JobRequest.of("p", "cron"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.TIMEOUT);
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(ledger.get()).isEqualTo(5000);
        assertThat(store.status.get(r.taskId())).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("failure after the call started is charged; pre-flight failures are free")
    void failures_chargingRules() {
        runtime(100_000, executor(JobKind.SPECIALIST, inv -> {
            if (inv.jobName().equals("preflight")) throw new LlmProviderService.DaemonProviderPolicyException("test");
            if (inv.jobName().equals("unknown")) throw new SubAgentFactory.UnknownSpecialistException("x");
            throw new IllegalStateException("provider 503 on round 4");
        }));

        DaemonTaskResult midRun = runtime.runSync(job("midrun", JobKind.SPECIALIST, LIMITS, Overlap.skip()), JobRequest.of("p", "m"));
        assertThat(midRun.status()).isEqualTo(DaemonTaskStatus.FAILED);
        assertThat(ledger.get()).isEqualTo(5000);

        runtime.runSync(job("preflight", JobKind.SPECIALIST, LIMITS, Overlap.skip()), JobRequest.of("p", "m"));
        runtime.runSync(job("unknown", JobKind.SPECIALIST, LIMITS, Overlap.skip()), JobRequest.of("p", "m"));
        assertThat(ledger.get()).as("pre-flight failures cost nothing").isEqualTo(5000);
    }

    @Test
    @DisplayName("T-011: finished runs are notified; a failing sink never changes the run's status")
    void notification_failureDoesNotFailTask() {
        runtime(100_000, executor(JobKind.JAVA, inv -> ExecutionOutcome.ofText("all good")));
        var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
        ai.kukuvaia.agent.daemon.notify.NotificationSink failing = new ai.kukuvaia.agent.daemon.notify.NotificationSink() {
            public String name() { return "log"; }
            public void send(NotificationSanitizer.NotificationPayload p) {
                seen.add(p.status());
                throw new IllegalStateException("sink down");
            }
        };
        runtime.setNotifier(new ai.kukuvaia.agent.daemon.notify.DaemonNotifier(List.of(failing), new NotificationSanitizer()));

        DaemonTaskResult r = runtime.runSync(job("j", JobKind.JAVA, LIMITS, Overlap.skip()), JobRequest.of(null, "m"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.COMPLETED);
        assertThat(store.status.get(r.taskId())).isEqualTo("COMPLETED");
        assertThat(seen).containsExactly("COMPLETED");
    }

    @Test
    @DisplayName("error text is sanitized before it is stored")
    void error_sanitized() {
        runtime(100_000, executor(JobKind.JAVA, inv -> {
            throw new IllegalStateException("connect failed jdbc:postgresql://db.internal:5432/kukuvaia?password=hunter2");
        }));

        DaemonTaskResult r = runtime.runSync(job("j", JobKind.JAVA, LIMITS, Overlap.skip()), JobRequest.of(null, "m"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.FAILED);
        assertThat(store.error.get(r.taskId())).doesNotContain("hunter2").doesNotContain("jdbc:postgresql://db.internal");
    }

    @Test
    @DisplayName("usage is recorded before the audit row is finalized; a row error does not lose tokens or flip the status")
    void usageBeforeFinalize() {
        runtime(100_000, executor(JobKind.SPECIALIST, inv -> new ExecutionOutcome("ok", null, 5, 5, true, null, null)));
        store.failOnComplete = true;

        DaemonTaskResult r = runtime.runSync(job("j", JobKind.SPECIALIST, LIMITS, Overlap.skip()), JobRequest.of("p", "m"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.COMPLETED);
        assertThat(ledger.get()).isEqualTo(10);
        assertThat(store.order).containsSubsequence("usage:" + r.taskId(), "finalize:" + r.taskId());
    }

    @Test
    @DisplayName("budget is reserved at admission: concurrent runs cannot overshoot the daily cap")
    void budgetReservation_preventsOvershoot() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        properties.setMaxConcurrent(5);
        runtime(12_000, executor(JobKind.SPECIALIST, inv -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return new ExecutionOutcome("ok", null, 500, 500, true, null, null);
        }));
        var allow = Overlap.allow(5);

        var a = runtime.submit(job("j", JobKind.SPECIALIST, LIMITS, allow), JobRequest.of("p", "m"));
        var b = runtime.submit(job("j", JobKind.SPECIALIST, LIMITS, allow), JobRequest.of("p", "m"));
        var c = runtime.submit(job("j", JobKind.SPECIALIST, LIMITS, allow), JobRequest.of("p", "m"));

        assertThat(a.accepted()).isTrue();
        assertThat(b.accepted()).isTrue();
        assertThat(c.status()).isEqualTo(DaemonTaskStatus.SKIPPED);
        assertThat(c.reason()).contains("budget");
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> "COMPLETED".equals(store.status.get(b.taskId())));
    }

    @Test
    @DisplayName("budget exhausted → SKIPPED, executor never called; Java jobs are not budget-gated")
    void budgetExhausted_skipsTokenKindsOnly() {
        AtomicInteger calls = new AtomicInteger();
        runtime(4000, executor(JobKind.SPECIALIST, inv -> { calls.incrementAndGet(); return ExecutionOutcome.ofText("x"); }),
                executor(JobKind.JAVA, inv -> ExecutionOutcome.ofText("java ok")));

        DaemonTaskResult llm = runtime.runSync(job("llm", JobKind.SPECIALIST, LIMITS, Overlap.skip()), JobRequest.of("p", "m"));
        DaemonTaskResult java = runtime.runSync(job("java", JobKind.JAVA, LIMITS, Overlap.skip()), JobRequest.of(null, "m"));

        assertThat(llm.status()).isEqualTo(DaemonTaskStatus.SKIPPED);
        assertThat(calls).hasValue(0);
        assertThat(java.status()).isEqualTo(DaemonTaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("overlap skip: a second run while the first is active is SKIPPED")
    void overlapSkip() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime(100_000, executor(JobKind.JAVA, inv -> {
            running.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return ExecutionOutcome.ofText("ok");
        }));
        var j = job("cron-job", JobKind.JAVA, LIMITS, Overlap.skip());

        var first = runtime.submit(j, JobRequest.of(null, "cron"));
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        var second = runtime.submit(j, JobRequest.of(null, "cron"));

        assertThat(first.accepted()).isTrue();
        assertThat(second.status()).isEqualTo(DaemonTaskStatus.SKIPPED);
        assertThat(second.reason()).contains("overlap");
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> "COMPLETED".equals(store.status.get(first.taskId())));
        assertThat(runtime.submit(j, JobRequest.of(null, "cron")).accepted()).as("slot released after completion").isTrue();
    }

    @Test
    @DisplayName("global concurrency limit: at most max-concurrent runs execute at once")
    void concurrencyLimit() throws Exception {
        properties.setMaxConcurrent(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        runtime(100_000, executor(JobKind.JAVA, inv -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            active.decrementAndGet();
            return ExecutionOutcome.ofText("ok");
        }));
        var j = job("many", JobKind.JAVA, LIMITS, Overlap.allow(10));
        List<DaemonRuntime.Submission> subs = new ArrayList<>();
        for (int i = 0; i < 5; i++) subs.add(runtime.submit(j, JobRequest.of(null, "m")));

        await().atMost(Duration.ofSeconds(5)).until(() -> active.get() == 2);
        Thread.sleep(200);
        assertThat(peak.get()).isEqualTo(2);
        assertThat(runtime.waitingRuns()).isEqualTo(3);
        release.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() ->
                subs.stream().allMatch(s -> "COMPLETED".equals(store.status.get(s.taskId()))));
    }

    @Test
    @DisplayName("kill switch and disabled job → SKIPPED without running")
    void killSwitchAndDisabled() {
        AtomicInteger calls = new AtomicInteger();
        runtime(100_000, executor(JobKind.JAVA, inv -> { calls.incrementAndGet(); return ExecutionOutcome.ofText("x"); }));
        var disabled = new DaemonJobDefinition("off", "", JobKind.JAVA, "t", List.of(), null, List.of(), null,
                LIMITS, Overlap.skip(), List.of(), false, "test");

        assertThat(runtime.runSync(disabled, JobRequest.of(null, "m")).status()).isEqualTo(DaemonTaskStatus.SKIPPED);
        properties.setEnabled(false);
        assertThat(runtime.runSync(job("j", JobKind.JAVA, LIMITS, Overlap.skip()), JobRequest.of(null, "m")).status())
                .isEqualTo(DaemonTaskStatus.SKIPPED);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("queue full → SKIPPED (backpressure)")
    void queueFull() {
        properties.setMaxConcurrent(1);
        properties.setMaxQueued(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime(100_000, executor(JobKind.JAVA, inv -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            return ExecutionOutcome.ofText("ok");
        }));
        var j = job("q", JobKind.JAVA, LIMITS, Overlap.allow(10));

        runtime.submit(j, JobRequest.of(null, "m"));
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.freeSlots() == 0);
        runtime.submit(j, JobRequest.of(null, "m")); // waits
        var third = runtime.submit(j, JobRequest.of(null, "m"));

        assertThat(third.status()).isEqualTo(DaemonTaskStatus.SKIPPED);
        assertThat(third.reason()).contains("queue full");
        release.countDown();
    }

    @Test
    @DisplayName("unknown kind (no executor bean) → SKIPPED with a clear reason")
    void noExecutor() {
        runtime(100_000, executor(JobKind.JAVA, inv -> ExecutionOutcome.ofText("x")));

        DaemonTaskResult r = runtime.runSync(job("a2a", JobKind.A2A, LIMITS, Overlap.skip()), JobRequest.of(null, "m"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.SKIPPED);
        assertThat(r.error()).contains("a2a");
    }

    @Test
    @DisplayName("review #1: reservation is the run's token_budget, not the per-response max_tokens")
    void reservationUsesTokenBudget() {
        AtomicInteger calls = new AtomicInteger();
        runtime(4999, executor(JobKind.SPECIALIST, inv -> { calls.incrementAndGet(); return ExecutionOutcome.ofText("x"); }));

        // max_tokens (1000) fits the budget, token_budget (5000) does not → refused before running
        DaemonTaskResult r = runtime.runSync(job("big", JobKind.SPECIALIST, LIMITS, Overlap.skip()), JobRequest.of("p", "m"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.SKIPPED);
        assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("review #2: a failing budget reservation does not leak the overlap lock")
    void reservationFailure_releasesOverlap() {
        DaemonBudgetGuard flaky = mock(DaemonBudgetGuard.class);
        when(flaky.tryReserve(org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("db blip"))
                .thenReturn(true);
        runtime = new DaemonRuntime(List.of(executor(JobKind.SPECIALIST, inv -> ExecutionOutcome.ofText("ok"))), flaky,
                new DaemonScheduleGuard(), store, properties, new ErrorSanitizer(), new SimpleMeterRegistry());
        var j = job("skip-job", JobKind.SPECIALIST, LIMITS, Overlap.skip());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runtime.runSync(j, JobRequest.of("p", "m")))
                .hasMessageContaining("db blip");
        assertThat(runtime.runSync(j, JobRequest.of("p", "m")).status())
                .as("overlap lock must not be held by the failed admission").isEqualTo(DaemonTaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("review #3: a run that ignores interruption keeps its slot and overlap lock until it really ends")
    void zombieRun_holdsSlotAndOverlap() throws Exception {
        properties.setMaxConcurrent(2);
        properties.setCancelGraceMillis(100);
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        runtime(100_000, executor(JobKind.JAVA, inv -> {
            while (!stop.get()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    // deliberately ignores cancellation
                }
            }
            return ExecutionOutcome.ofText("finally");
        }));
        var j = job("stubborn", JobKind.JAVA, LIMITS.withTimeout(Duration.ofMillis(200)), Overlap.skip());

        DaemonTaskResult r = runtime.runSync(j, JobRequest.of(null, "cron"));

        assertThat(r.status()).isEqualTo(DaemonTaskStatus.TIMEOUT);
        assertThat(runtime.zombieRuns()).isEqualTo(1);
        assertThat(runtime.freeSlots()).as("zombie still occupies a slot").isEqualTo(1);
        assertThat(runtime.submit(j, JobRequest.of(null, "cron")).status())
                .as("overlap: skip still blocks while the zombie runs").isEqualTo(DaemonTaskStatus.SKIPPED);

        stop.set(true);
        await().atMost(Duration.ofSeconds(5)).until(() -> runtime.freeSlots() == 2 && runtime.zombieRuns() == 0);
        assertThat(runtime.submit(j, JobRequest.of(null, "cron")).accepted()).isTrue();
    }

    @Test
    @DisplayName("review #6: restart recovery cancels rows of a previous process only")
    void restartRecovery() {
        var seen = new java.util.concurrent.atomic.AtomicReference<java.time.Instant>();
        FakeStore recording = new FakeStore() {
            @Override
            public int cancelInterrupted(java.time.Instant processStart, int llmCharge) {
                seen.set(processStart);
                return 2;
            }
        };
        var rt = new DaemonRuntime(List.of(executor(JobKind.JAVA, inv -> ExecutionOutcome.ofText("x"))),
                budget(100_000, ledger, recording.order), new DaemonScheduleGuard(), recording, properties,
                new ErrorSanitizer(), new SimpleMeterRegistry());
        try {
            rt.recoverInterruptedRuns();
            assertThat(seen.get()).isNotNull().isBeforeOrEqualTo(java.time.Instant.now());
        } finally {
            rt.shutdown();
        }
    }
}
