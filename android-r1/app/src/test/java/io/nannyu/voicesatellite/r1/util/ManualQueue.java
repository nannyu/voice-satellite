package io.nannyu.voicesatellite.r1.util;

import java.util.PriorityQueue;

/** Virtual clock: no sleeping or real network is needed for protocol/timer tests. */
public final class ManualQueue implements TaskQueue {
    private static final class Task implements Comparable<Task> {
        final long when, order;
        final Runnable action;
        boolean cancelled;
        Task(long when, long order, Runnable action) { this.when = when; this.order = order; this.action = action; }
        @Override public int compareTo(Task other) {
            int time = Long.compare(when, other.when);
            return time != 0 ? time : Long.compare(order, other.order);
        }
    }
    private final PriorityQueue<Task> tasks = new PriorityQueue<>();
    private long now, sequence;
    private boolean closed;
    public long now() { return now; }
    @Override public void execute(Runnable task) { if (!closed) schedule(0, task); }
    @Override public Object schedule(long delayMs, Runnable task) {
        if (closed) throw new IllegalStateException("queue closed");
        Task entry = new Task(now + delayMs, sequence++, task);
        tasks.add(entry);
        return entry;
    }
    @Override public void cancel(Object task) { ((Task) task).cancelled = true; }
    @Override public void shutdown() { closed = true; tasks.clear(); }
    public void runReady() { advance(0); }
    public void advance(long ms) {
        long target = now + ms;
        while (!tasks.isEmpty() && tasks.peek().when <= target) {
            Task task = tasks.remove();
            now = task.when;
            if (!task.cancelled) task.action.run();
        }
        now = target;
    }
}
