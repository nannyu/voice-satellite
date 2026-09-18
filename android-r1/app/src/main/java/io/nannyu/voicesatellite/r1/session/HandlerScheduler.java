package io.nannyu.voicesatellite.r1.session;

import android.os.Handler;

/**
 * Android SessionController.Scheduler: posts delayed work on a Handler.
 * The Runnable itself is the cancel token (Handler.removeCallbacks identity).
 */
public final class HandlerScheduler implements SessionController.Scheduler {
    private final Handler handler;

    public HandlerScheduler(Handler handler) {
        if (handler == null) throw new IllegalArgumentException("handler is required");
        this.handler = handler;
    }

    @Override public Object schedule(long delayMs, Runnable task) {
        handler.postDelayed(task, delayMs);
        return task;
    }

    @Override public void cancel(Object handle) {
        if (handle instanceof Runnable) handler.removeCallbacks((Runnable) handle);
    }
}
