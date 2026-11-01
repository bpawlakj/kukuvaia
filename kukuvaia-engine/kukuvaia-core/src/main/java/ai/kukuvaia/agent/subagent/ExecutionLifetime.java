package ai.kukuvaia.agent.subagent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks actual execution separately from Future cancellation. Cancelling a Future marks it done
 * immediately, even when its callable ignores interruption. Daemon slots must include that callable
 * and all nested model/process work until their real completion.
 */
public final class ExecutionLifetime implements AutoCloseable {

    private final Phaser work = new Phaser(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    public final class Registration implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        private Registration() {}

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) work.arriveAndDeregister();
        }
    }

    public Registration register() {
        if (closed.get() || work.register() < 0) {
            throw new IllegalStateException("The execution lifetime has ended");
        }
        if (closed.get()) {
            work.arriveAndDeregister();
            throw new IllegalStateException("The execution lifetime has ended");
        }
        return new Registration();
    }

    public <T> Future<T> submit(ExecutorService executor, Callable<T> callable) {
        Registration registration = register();
        var task = new FutureTask<T>(callable) {
            private final AtomicBoolean started = new AtomicBoolean();

            @Override
            public void run() {
                if (started.compareAndSet(false, true)) {
                    try { super.run(); } finally { registration.close(); }
                }
            }

            @Override
            protected void done() {
                // Cancellation before execution must not leave a phantom participant behind.
                if (started.compareAndSet(false, true)) registration.close();
            }
        };
        try {
            executor.execute(task);
        } catch (RuntimeException | Error e) {
            task.cancel(false);
            throw e;
        }
        return task;
    }

    public boolean isClosed() { return closed.get(); }

    public boolean isFinished() { return work.isTerminated(); }

    public boolean await(long millis) throws InterruptedException {
        try {
            work.awaitAdvanceInterruptibly(0, millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /** The zombie monitor must never free a live slot because its own wait was interrupted. */
    public void await() { work.awaitAdvance(0); }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) work.arriveAndDeregister();
    }
}
