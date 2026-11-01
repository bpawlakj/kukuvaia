package ai.kukuvaia.agent.daemon;

import ai.kukuvaia.agent.daemon.DaemonConfig.DaemonProperties;
import ai.kukuvaia.agent.daemon.DaemonTaskResult.DaemonTaskStatus;
import ai.kukuvaia.agent.daemon.def.DaemonJobDefinition;
import ai.kukuvaia.agent.daemon.def.Overlap;
import ai.kukuvaia.agent.daemon.exec.DaemonExecutor;
import ai.kukuvaia.agent.daemon.exec.ExecutionOutcome;
import ai.kukuvaia.agent.daemon.exec.JobInvocation;
import ai.kukuvaia.agent.daemon.exec.JobKind;
import ai.kukuvaia.agent.daemon.exec.JobLimits;
import ai.kukuvaia.agent.subagent.SubAgentFactory;
import ai.kukuvaia.agent.subagent.SubAgentGuard;
import ai.kukuvaia.provider.service.LlmProviderService;
import ai.kukuvaia.security.ErrorSanitizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The daemon runtime: runs every kind of daemon work under the same guards, audit and limits.
 *
 * <pre>
 * submit / runSync
 *   admission: kill switch → job enabled → executor exists → overlap → queue capacity → budget reservation
 *   audit:     daemon_tasks QUEUED → RUNNING → COMPLETED | FAILED | TIMEOUT | CANCELLED   (or SKIPPED at admission)
 *   run:       DaemonExecutor[kind] on a bounded slot pool, hard deadline, call cancelled on timeout
 *   settle:    usage recorded first (budget), then the audit row is finalized
 * </pre>
 *
 * <p>Token accounting fails closed: a run that reported no usage, timed out, was cancelled, or failed
 * after its call started is charged the job's {@code token_budget}. Only pre-flight failures (unknown
 * specialist, provider policy, provider not configured, depth guard) are free. The daily budget is a
 * hard stop under concurrency because admission <em>reserves</em> tokens and settlement releases them.
 *
 * <p>Covers security-review #10 (backpressure), #15 (audit), #17 (overlap) and the sub-agent DoS rule.
 */
@Service
public class DaemonRuntime {

    private static final Logger log = LoggerFactory.getLogger(DaemonRuntime.class);


    private final Map<JobKind, DaemonExecutor> executors = new EnumMap<>(JobKind.class);
    private final DaemonBudgetGuard budget;
    private final DaemonScheduleGuard scheduleGuard;
    private final DaemonTaskStore store;
    private final DaemonProperties properties;
    private final ErrorSanitizer errorSanitizer;
    private final MeterRegistry meterRegistry;

    private final Semaphore slots;
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger zombies = new AtomicInteger();
    /** Process start: rows created before it belong to a previous process (restart recovery). */
    private final Instant processStart = Instant.now();
    private final Map<String, Semaphore> allowSlots = new ConcurrentHashMap<>();
    private final ExecutorService dispatcher =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("kukuvaia-daemon-", 0).factory());
    private final ExecutorService callRunner =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("kukuvaia-daemon-call-", 0).factory());

    public DaemonRuntime(List<DaemonExecutor> executorBeans,
                         DaemonBudgetGuard budget,
                         DaemonScheduleGuard scheduleGuard,
                         DaemonTaskStore store,
                         DaemonProperties properties,
                         ErrorSanitizer errorSanitizer,
                         MeterRegistry meterRegistry) {
        for (DaemonExecutor executor : executorBeans) {
            DaemonExecutor previous = executors.put(executor.kind(), executor);
            if (previous != null) {
                throw new IllegalStateException("Two daemon executors for kind " + executor.kind());
            }
        }
        this.budget = budget;
        this.scheduleGuard = scheduleGuard;
        this.store = store;
        this.properties = properties;
        this.errorSanitizer = errorSanitizer;
        this.meterRegistry = meterRegistry;
        this.slots = new Semaphore(properties.getMaxConcurrent(), true);
        log.info("Daemon runtime ready: kinds={}, max-concurrent={}, max-queued={}",
                executors.keySet(), properties.getMaxConcurrent(), properties.getMaxQueued());
        if (properties.getAllowedTools().isEmpty()) {
            log.warn("kukuvaia.daemon.allowed-tools is empty — unattended runs may use every tool their "
                    + "specialist lists. Set an allowlist to narrow daemon tool access.");
        }
    }

    /**
     * Restart recovery: QUEUED/RUNNING rows created by a previous process can never finish. Mark them
     * CANCELLED with a reason; interrupted LLM runs are charged a conservative estimate (their real
     * usage was never recorded). Rows of this process are untouched.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        try {
            int recovered = store.cancelInterrupted(processStart, JobLimits.defaults().tokenBudget());
            if (recovered > 0) {
                log.warn("Daemon restart recovery: {} run(s) from a previous process marked CANCELLED", recovered);
            }
        } catch (RuntimeException e) {
            log.error("Daemon restart recovery failed: {}", e.getMessage());
        }
    }

    /** Optional: notifications for finished runs ({@code notify:} sinks). */
    private volatile ai.kukuvaia.agent.daemon.notify.DaemonNotifier notifier;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNotifier(ai.kukuvaia.agent.daemon.notify.DaemonNotifier notifier) {
        this.notifier = notifier;
    }

    private DaemonTaskResult notified(DaemonJobDefinition job, DaemonTaskResult result) {
        var n = notifier;
        if (n != null) {
            try {
                n.notifyFinished(job, result);
            } catch (RuntimeException e) {
                log.warn("Notification for task {} failed: {}", result.taskId(), e.getMessage());
            }
        }
        return result;
    }

    @PreDestroy
    void shutdown() {
        dispatcher.shutdownNow();
        callRunner.shutdownNow();
    }

    /** Result of an asynchronous submission. */
    public record Submission(long taskId, DaemonTaskStatus status, String reason) {
        public boolean accepted() {
            return status == DaemonTaskStatus.QUEUED;
        }
    }

    /**
     * Admit a run and execute it asynchronously. Returns immediately with the audit row id:
     * QUEUED if admitted, SKIPPED (with a reason) otherwise.
     */
    public Submission submit(DaemonJobDefinition job, JobRequest request) {
        Admission admission = admit(job, request, true);
        if (admission.skipped()) {
            return new Submission(admission.taskId(), DaemonTaskStatus.SKIPPED, admission.reason());
        }
        try {
            dispatcher.submit(() -> run(job, request, admission));
        } catch (RuntimeException e) { // executor shut down
            waiting.decrementAndGet();
            abandon(admission, job, DaemonTaskStatus.CANCELLED, "daemon runtime is shutting down");
            return new Submission(admission.taskId(), DaemonTaskStatus.CANCELLED, "daemon runtime is shutting down");
        }
        return new Submission(admission.taskId(), DaemonTaskStatus.QUEUED, null);
    }

    /** Admit and run on the calling thread; returns the final result. */
    public DaemonTaskResult runSync(DaemonJobDefinition job, JobRequest request) {
        Admission admission = admit(job, request, false);
        if (admission.skipped()) {
            Instant now = Instant.now();
            return new DaemonTaskResult(admission.taskId(), job.name(), job.target(), job.provider(),
                    DaemonTaskStatus.SKIPPED, null, admission.reason(), 0, 0, request.triggerSource(), now, now);
        }
        return run(job, request, admission);
    }

    /** Runs currently waiting for a slot (monitoring / API). */
    public int waitingRuns() {
        return waiting.get();
    }

    /** Free execution slots right now (monitoring / API). */
    public int freeSlots() {
        return slots.availablePermits();
    }

    // ── Admission ────────────────────────────────────────────────────────────

    private record Admission(long taskId, int reservedTokens, boolean skipped, String reason,
                             Runnable releaseOverlap, boolean queued) {}

    private Admission admit(DaemonJobDefinition job, JobRequest request, boolean queued) {
        if (!properties.isEnabled()) {
            return skipped(job, request, "daemon disabled (kukuvaia.daemon.enabled=false)");
        }
        if (!job.enabled()) {
            return skipped(job, request, "job '" + job.name() + "' is disabled");
        }
        if (!executors.containsKey(job.kind())) {
            return skipped(job, request, "no executor available for kind '" + job.kind().dbValue() + "'");
        }
        Runnable releaseOverlap = acquireOverlap(job);
        if (releaseOverlap == null) {
            return skipped(job, request, job.overlap().mode() == Overlap.Mode.SKIP
                    ? "previous run still active (overlap: skip)"
                    : "max concurrent runs of this job reached (" + job.overlap().maxConcurrent() + ")");
        }
        // Everything acquired from here on is released on every non-admitted path (incl. exceptions).
        boolean admitted = false;
        boolean counted = false;
        int reserved = 0;
        try {
            if (queued) {
                counted = true;
                if (waiting.incrementAndGet() > properties.getMaxQueued()) {
                    return skipped(job, request, "daemon queue full (" + properties.getMaxQueued() + ")");
                }
            }
            if (job.kind().consumesLocalTokens()) {
                int estimate = conservativeCharge(job);
                if (!budget.tryReserve(estimate)) {
                    return skipped(job, request, "daily token budget exhausted");
                }
                reserved = estimate;
            }
            long taskId = store.insertQueued(job, request);
            admitted = true;
            return new Admission(taskId, reserved, false, null, releaseOverlap, queued);
        } finally {
            if (!admitted) {
                releaseOverlap.run();
                if (reserved > 0) budget.releaseReservation(reserved);
                if (counted) waiting.decrementAndGet();
            }
        }
    }

    private Admission skipped(DaemonJobDefinition job, JobRequest request, String reason) {
        log.info("Daemon job '{}' skipped ({}): {}", job.name(), request.triggerSource(), reason);
        long taskId = store.insertSkipped(job, request, reason);
        recordMetrics(job, DaemonTaskStatus.SKIPPED, 0);
        return new Admission(taskId, 0, true, reason, () -> { }, false);
    }

    private Runnable acquireOverlap(DaemonJobDefinition job) {
        if (job.overlap().mode() == Overlap.Mode.SKIP) {
            return scheduleGuard.tryAcquire(job.name()) ? once(() -> scheduleGuard.release(job.name())) : null;
        }
        // Bounded by the number of job definitions, not by the number of runs.
        Semaphore permits = allowSlots.computeIfAbsent(job.name(), n -> new Semaphore(job.overlap().maxConcurrent()));
        return permits.tryAcquire() ? once(permits::release) : null;
    }

    private static Runnable once(Runnable action) {
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true)) action.run();
        };
    }

    // ── Execution ────────────────────────────────────────────────────────────

    private DaemonTaskResult run(DaemonJobDefinition job, JobRequest request, Admission admission) {
        long taskId = admission.taskId();
        boolean haveSlot = false;
        try {
            try {
                slots.acquire();
                haveSlot = true;
            } finally {
                if (admission.queued()) waiting.decrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.interrupted();
            abandon(admission, job, DaemonTaskStatus.CANCELLED, "cancelled while waiting for a daemon slot");
            Thread.currentThread().interrupt();
            return terminal(taskId, job, request, DaemonTaskStatus.CANCELLED, null, "cancelled", 0, 0, null, null);
        }

        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        boolean settled = false;
        CountDownLatch callDone = null;
        try {
            store.markRunning(taskId);
            Duration timeout = job.limits().timeout();
            JobInvocation invocation = new JobInvocation(taskId, job.name(), job.target(), request.prompt(),
                    request.fields(), job.provider(), startedAt.plus(timeout), job.args());
            DaemonExecutor executor = executors.get(job.kind());

            ExecutionOutcome outcome = null;
            DaemonTaskStatus status;
            String error = null;
            int charge;
            CountDownLatch workDone = new CountDownLatch(1);
            Future<ExecutionOutcome> call = callRunner.submit(() -> {
                try {
                    return executor.execute(invocation, job.limits());
                } finally {
                    workDone.countDown();
                }
            });
            callDone = workDone;
            try {
                outcome = call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                status = DaemonTaskStatus.COMPLETED;
                charge = billable(job, outcome);
            } catch (TimeoutException e) {
                call.cancel(true);
                status = DaemonTaskStatus.TIMEOUT;
                error = "deadline of " + timeout.toSeconds() + "s exceeded — run cancelled";
                charge = conservativeCharge(job);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                boolean timedOut = cause instanceof SubAgentFactory.SubAgentTimeoutException
                        || (cause instanceof ai.kukuvaia.agent.daemon.exec.ExecutionFailedException f && f.timedOut());
                status = timedOut ? DaemonTaskStatus.TIMEOUT : DaemonTaskStatus.FAILED;
                error = errorSanitizer.sanitize(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                if (isPreflight(cause)) {
                    charge = 0;
                } else if (cause instanceof ai.kukuvaia.agent.daemon.exec.ExecutionFailedException f && f.usageReported()
                        && job.kind().consumesLocalTokens()) {
                    charge = f.totalTokens(); // real usage of a run stopped mid-way (e.g. Embabel budget)
                } else {
                    charge = conservativeCharge(job);
                }
            } catch (InterruptedException e) {
                call.cancel(true);
                status = DaemonTaskStatus.CANCELLED;
                error = "cancelled";
                charge = conservativeCharge(job);
            }

            // Writes below must not run on an interrupted thread (some JDBC drivers fail fast).
            boolean interrupted = Thread.interrupted();
            long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            // 1) Usage first, independently of the audit row — a DB error finalizing the row must
            //    not lose the tokens, and a budget error must not flip a completed run to FAILED.
            settled = settle(taskId, admission.reservedTokens(), charge, job);
            // 2) Finalize the audit row.
            try {
                if (status == DaemonTaskStatus.COMPLETED) {
                    store.markCompleted(taskId, outcome, durationMs);
                } else {
                    store.markFinished(taskId, status, error, durationMs);
                }
            } catch (RuntimeException e) {
                log.error("Daemon task {} ('{}') ended {} but its audit row could not be finalized: {}",
                        taskId, job.name(), status, e.getMessage());
            }
            recordMetrics(job, status, durationMs);
            if (status == DaemonTaskStatus.COMPLETED) {
                log.info("Daemon job '{}' completed in {}ms (task #{}, {} tokens)", job.name(), durationMs, taskId, charge);
            } else {
                log.warn("Daemon job '{}' ended {} after {}ms (task #{}): {}", job.name(), status, durationMs, taskId, error);
            }
            DaemonTaskResult result = notified(job,
                    terminal(taskId, job, request, status, outcome, error, charge, durationMs, startedAt, Instant.now()));
            if (interrupted) Thread.currentThread().interrupt();
            return result;
        } catch (RuntimeException | Error e) {
            // Infrastructure failure (e.g. markRunning). Record as FAILED, keep tokens safe.
            long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            String error = errorSanitizer.sanitize(e.getMessage());
            if (!settled) {
                budget.releaseReservation(admission.reservedTokens());
            }
            try {
                store.markFinished(taskId, DaemonTaskStatus.FAILED, error, durationMs);
            } catch (RuntimeException ignored) {
                log.error("Daemon task {} could not be marked FAILED: {}", taskId, ignored.getMessage());
            }
            recordMetrics(job, DaemonTaskStatus.FAILED, durationMs);
            if (e instanceof Error err) throw err;
            return notified(job,
                    terminal(taskId, job, request, DaemonTaskStatus.FAILED, null, error, 0, durationMs, startedAt, Instant.now()));
        } finally {
            releaseWhenWorkEnds(job, taskId, callDone, haveSlot, admission.releaseOverlap());
        }
    }

    /**
     * Release the run's slot and overlap lock — but only once the work has really stopped. A run that
     * ignores interruption after its deadline keeps both (it still counts against max-concurrent and
     * overlap: skip), and they are released by a hook when it finally returns.
     */
    private void releaseWhenWorkEnds(DaemonJobDefinition job, long taskId, CountDownLatch callDone,
                                     boolean haveSlot, Runnable releaseOverlap) {
        Runnable release = () -> {
            if (haveSlot) slots.release();
            releaseOverlap.run();
        };
        boolean finished = callDone == null || callDone.getCount() == 0;
        if (!finished) {
            try {
                finished = callDone.await(properties.getCancelGraceMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (finished) {
            release.run();
            return;
        }
        zombies.incrementAndGet();
        log.error("Daemon task {} ('{}') did not stop after cancellation — holding its slot until it ends",
                taskId, job.name());
        Thread.ofVirtual().name("kukuvaia-daemon-zombie-" + taskId).start(() -> {
            try {
                callDone.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                zombies.decrementAndGet();
                release.run();
                log.warn("Daemon task {} ('{}') finally stopped — slot released", taskId, job.name());
            }
        });
    }

    /** Runs that missed their deadline and ignore interruption (monitoring / API). */
    public int zombieRuns() {
        return zombies.get();
    }

    /** Record usage against the budget. Returns true once the reservation has been released. */
    private boolean settle(long taskId, int reserved, int charge, DaemonJobDefinition job) {
        if (reserved == 0 && charge == 0) {
            return true;
        }
        try {
            budget.settle(taskId, reserved, charge);
            return true;
        } catch (RuntimeException e) {
            log.error("Could not record {} tokens for daemon task {} ('{}'): {}", charge, taskId, job.name(), e.getMessage());
            return true; // settle() releases the reservation in a finally block
        }
    }

    private void abandon(Admission admission, DaemonJobDefinition job, DaemonTaskStatus status, String reason) {
        budget.releaseReservation(admission.reservedTokens());
        admission.releaseOverlap().run();
        try {
            store.markFinished(admission.taskId(), status, reason, 0);
        } catch (RuntimeException e) {
            log.error("Daemon task {} could not be marked {}: {}", admission.taskId(), status, e.getMessage());
        }
        recordMetrics(job, status, 0);
    }

    /** Tokens to charge for a completed run: reported usage, else the conservative estimate. */
    private int billable(DaemonJobDefinition job, ExecutionOutcome outcome) {
        if (outcome == null) return conservativeCharge(job);
        if (!job.kind().consumesLocalTokens()) {
            return outcome.totalTokens(); // e.g. a Java job that called a specialist
        }
        if (outcome.usageReported()) {
            if (outcome.totalTokens() > job.limits().tokenBudget()) {
                log.warn("Daemon job '{}' used {} tokens, above its token_budget of {} — raise the budget or the "
                        + "reservation under-protects the daily cap", job.name(), outcome.totalTokens(), job.limits().tokenBudget());
            }
            return outcome.totalTokens();
        }
        int estimate = conservativeCharge(job);
        log.warn("Daemon job '{}' reported no token usage — charging {} tokens (fail closed)", job.name(), estimate);
        return estimate;
    }

    /**
     * Reservation and fail-closed charge for one run: its {@code token_budget} — the cumulative
     * prompt + completion tokens a run may spend over all tool rounds. ({@code max_tokens} only caps
     * a single response and would under-reserve multi-round runs.)
     */
    private static int conservativeCharge(DaemonJobDefinition job) {
        if (!job.kind().consumesLocalTokens()) return 0;
        return job.limits().tokenBudget();
    }

    /** Failures raised before any model call — these cost nothing. */
    private static boolean isPreflight(Throwable cause) {
        return cause instanceof SubAgentFactory.UnknownSpecialistException
                || cause instanceof LlmProviderService.DaemonProviderPolicyException
                || cause instanceof LlmProviderService.ProviderNotAvailableException
                || cause instanceof SubAgentGuard.SubAgentDepthExceededException;
    }

    private DaemonTaskResult terminal(long taskId, DaemonJobDefinition job, JobRequest request,
                                      DaemonTaskStatus status, ExecutionOutcome outcome, String error,
                                      int tokens, long durationMs, Instant startedAt, Instant completedAt) {
        String provider = outcome != null && outcome.provider() != null ? outcome.provider() : job.provider();
        return new DaemonTaskResult(taskId, job.name(), job.target(), provider, status,
                outcome != null ? outcome.text() : null, error, tokens, durationMs, request.triggerSource(),
                startedAt, completedAt);
    }

    private void recordMetrics(DaemonJobDefinition job, DaemonTaskStatus status, long durationMs) {
        String statusTag = status.name().toLowerCase();
        Timer.builder("kukuvaia.daemon.task.duration")
                .tag("task", job.name())
                .tag("kind", job.kind().dbValue())
                .tag("status", statusTag)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
        Counter.builder("kukuvaia.daemon.task.total")
                .tag("task", job.name())
                .tag("kind", job.kind().dbValue())
                .tag("status", statusTag)
                .register(meterRegistry)
                .increment();
    }
}
