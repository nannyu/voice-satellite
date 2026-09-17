package io.nannyu.voicesatellite.r1.util;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import io.nannyu.voicesatellite.r1.session.SessionController;

/** Serial event queue with injectable scheduling for deterministic tests. */
public interface TaskQueue extends SessionController.Scheduler {
    void execute(Runnable task);
    void shutdown();

    final class Serial implements TaskQueue {
        private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        public Serial() {
            executor.setRemoveOnCancelPolicy(true);
            executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        }
        @Override public void execute(Runnable task) {
            try { executor.execute(task); } catch (RejectedExecutionException ignored) {
                // Late device/socket callbacks after shutdown have no owner.
            }
        }
        @Override public Object schedule(long delayMs, Runnable task) {
            return executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        }
        @Override public void cancel(Object handle) { ((ScheduledFuture<?>) handle).cancel(false); }
        @Override public void shutdown() { executor.shutdown(); }
    }
}
