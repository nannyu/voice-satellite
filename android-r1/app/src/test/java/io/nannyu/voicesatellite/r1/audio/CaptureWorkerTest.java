package io.nannyu.voicesatellite.r1.audio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

public class CaptureWorkerTest {
    private static void await(CountDownLatch latch) {
        try { assertTrue("latch deadline", latch.await(2, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
    private static class Source implements CaptureWorker.Session {
        final CountDownLatch reading = new CountDownLatch(1), unblock = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        boolean unblockOnStop = true;
        int closes;
        @Override public void start() { }
        @Override public int read(byte[] frame, int offset, int length) {
            order.add("read"); reading.countDown(); await(unblock); order.add("read-exit"); return length;
        }
        @Override public void process(byte[] frame) { }
        @Override public void stop() { order.add("stop"); if (unblockOnStop) unblock.countDown(); }
        @Override public void close() { order.add("release"); closes++; released.countDown(); }
    }
    @Test(timeout = 5000) public void closeUnblocksReadBeforeReleaseAndIsIdempotent() {
        Source source = new Source(); CaptureWorker worker = new CaptureWorker(4, 1000, message -> fail(message));
        assertTrue(worker.start(() -> source)); await(source.reading);
        worker.close(); worker.close();
        assertTrue(worker.isIdle()); assertEquals(1, source.closes);
        assertTrue(source.order.indexOf("stop") < source.order.indexOf("read-exit"));
        assertTrue(source.order.indexOf("read-exit") < source.order.indexOf("release"));
    }
    @Test(timeout = 5000) public void joinTimeoutDoesNotReleaseOrAllowAnotherGeneration() {
        Source source = new Source(); source.unblockOnStop = false;
        CaptureWorker worker = new CaptureWorker(4, 20, message -> fail(message));
        assertTrue(worker.start(() -> source)); await(source.reading); worker.close();
        assertEquals(0, source.closes); assertFalse(worker.isIdle()); assertFalse(worker.isRunning());
        assertFalse(worker.start(() -> { fail("must not create another source"); return new Source(); }));
        source.unblock.countDown(); worker.close(); await(source.released);
        worker.close(); assertTrue(worker.isIdle()); assertEquals(1, source.closes);
    }
    @Test(timeout = 5000) public void listenerCanCloseFromTheCaptureThread() {
        final CaptureWorker[] worker = new CaptureWorker[1];
        Source source = new Source() { @Override public void process(byte[] frame) { worker[0].close(); } };
        source.unblock.countDown();
        worker[0] = new CaptureWorker(4, 1000, message -> fail(message));
        assertTrue(worker[0].start(() -> source)); await(source.released); worker[0].close();
        assertTrue(worker[0].isIdle()); assertEquals(1, source.closes);
    }
    @Test(timeout = 5000) public void startFailureReleasesResources() {
        List<String> errors = new ArrayList<>();
        Source source = new Source() { @Override public void start() { throw new IllegalStateException("init"); } };
        CaptureWorker worker = new CaptureWorker(4, 1000, errors::add);
        assertFalse(worker.start(() -> source)); assertTrue(worker.isIdle());
        assertEquals(1, source.closes); assertEquals(1, errors.size());
    }
    @Test(timeout = 5000) public void zeroReadFailsInsteadOfSpinning() {
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        Source source = new Source() { @Override public int read(byte[] b, int o, int n) { return 0; } };
        CaptureWorker worker = new CaptureWorker(4, 1000, errors::add);
        assertTrue(worker.start(() -> source)); await(source.released); worker.close();
        assertTrue(worker.isIdle()); assertEquals(1, errors.size());
    }
    @Test(timeout = 5000) public void releaseFailureStillClearsTheWorkerHandle() {
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        Source source = new Source() { @Override public void close() { super.close(); throw new IllegalStateException("release"); } };
        CaptureWorker worker = new CaptureWorker(4, 1000, errors::add);
        assertTrue(worker.start(() -> source)); await(source.reading); worker.close();
        assertTrue(worker.isIdle()); assertEquals(1, errors.size());
    }
}
