package io.nannyu.voicesatellite.r1.audio;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns one capture generation. Only its worker may release a started session. */
public final class CaptureWorker implements AutoCloseable {
    public interface Session {
        void start();
        int read(byte[] frame, int offset, int length);
        void process(byte[] frame);
        void stop(); // Must unblock read; must not release resources.
        void close();
    }
    public interface Factory { Session create(); }
    public interface Errors { void onError(String message); }
    private final int frameBytes;
    private final long joinMs;
    private final Errors errors;
    private Run active;

    private static final class Run {
        final Session session;
        final CountDownLatch finished = new CountDownLatch(1);
        volatile boolean stopping;
        boolean stopped, released;
        Thread thread;
        Run(Session session) { this.session = session; }
    }
    public CaptureWorker(int frameBytes, long joinMs, Errors errors) {
        if (frameBytes <= 0 || joinMs <= 0 || errors == null) throw new IllegalArgumentException();
        this.frameBytes = frameBytes;
        this.joinMs = joinMs;
        this.errors = errors;
    }
    public synchronized boolean start(Factory factory) {
        if (active != null) return !active.stopping;
        Session session = null;
        try {
            session = factory.create();
            session.start();
            final Run run = new Run(session);
            run.thread = new Thread(() -> loop(run), "r1-audio-capture");
            active = run;
            run.thread.start();
            return true;
        } catch (RuntimeException | LinkageError failure) {
            active = null;
            if (session != null) {
                try { session.stop(); } catch (RuntimeException ignored) { }
                try { session.close(); } catch (RuntimeException ignored) { }
            }
            errors.onError(failure.toString());
            return false;
        }
    }
    private void loop(Run run) {
        byte[] frame = new byte[frameBytes];
        try {
            while (!run.stopping) {
                int offset = 0;
                while (offset < frame.length && !run.stopping) {
                    int count = run.session.read(frame, offset, frame.length - offset);
                    if (run.stopping) break;
                    if (count <= 0 || count > frame.length - offset)
                        throw new IllegalStateException("AudioRecord read=" + count);
                    offset += count;
                }
                if (!run.stopping && offset == frame.length) run.session.process(frame);
            }
        } catch (RuntimeException | LinkageError failure) {
            if (!run.stopping) errors.onError(failure.toString());
        } finally {
            run.stopping = true;
            try {
                synchronized (run) {
                    stopSource(run);
                    try { run.session.close(); } finally { run.released = true; }
                }
            } catch (RuntimeException failure) {
                errors.onError(failure.toString());
            } finally {
                synchronized (this) { if (active == run) active = null; }
                run.finished.countDown();
            }
        }
    }
    private static void stopSource(Run run) {
        if (run.stopped || run.released) return;
        run.stopped = true;
        try { run.session.stop(); } catch (RuntimeException ignored) { }
    }
    public synchronized boolean isRunning() { return active != null && !active.stopping; }
    public synchronized boolean isIdle() { return active == null; }
    @Override public void close() {
        final Run run;
        synchronized (this) {
            run = active;
            if (run == null) return;
            run.stopping = true;
        }
        synchronized (run) { stopSource(run); }
        if (Thread.currentThread() == run.thread) return; // Listener-initiated stop must not self-join.
        try { run.finished.await(joinMs, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        // A timed-out worker retains ownership. start() refuses a replacement until it exits.
    }
}
