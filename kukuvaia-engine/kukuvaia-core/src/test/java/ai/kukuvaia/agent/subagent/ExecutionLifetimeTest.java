package ai.kukuvaia.agent.subagent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ExecutionLifetimeTest {

    @Test
    void cancellationDoesNotReportAnUninterruptibleCallableAsFinished() throws Exception {
        var lifetime = new ExecutionLifetime();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var call = lifetime.submit(executor, () -> {
                started.countDown();
                while (release.getCount() > 0) {
                    try { release.await(); } catch (InterruptedException ignored) { }
                }
                return "done";
            });
            try {
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                call.cancel(true);
                lifetime.close();
                assertThat(call.isDone()).isTrue();
                assertThat(lifetime.await(10)).isFalse();
                assertThatThrownBy(lifetime::register).isInstanceOf(IllegalStateException.class);
            } finally {
                release.countDown();
            }
            assertThat(lifetime.await(5000)).isTrue();
        }
    }

    @Test
    void cancellationBeforeStartDoesNotRetainAPhantomParticipant() throws Exception {
        var lifetime = new ExecutionLifetime();
        var running = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> { running.countDown(); release.await(); return null; });
            try {
                assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
                lifetime.submit(executor, () -> "never starts").cancel(false);
                lifetime.close();
                assertThat(lifetime.await(100)).isTrue();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void rejectedSubmissionReleasesItsRegistration() {
        var lifetime = new ExecutionLifetime();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.shutdown();
        assertThatThrownBy(() -> lifetime.submit(executor, () -> "never starts"))
                .isInstanceOf(RejectedExecutionException.class);
        lifetime.close();
        assertThat(lifetime.isFinished()).isTrue();
    }
}
