package io.nannyu.voicesatellite.r1.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public final class ReconnectPolicyTest {
    @Test public void growsExponentiallyWithoutJitter() {
        ReconnectPolicy policy = new ReconnectPolicy(1000, 30000, 0, new Random(1));

        assertEquals(1000, policy.nextDelayMs());
        assertEquals(2000, policy.nextDelayMs());
        assertEquals(4000, policy.nextDelayMs());
        assertEquals(8000, policy.nextDelayMs());
    }

    @Test public void capsAtMaxDelay() {
        ReconnectPolicy policy = new ReconnectPolicy(1000, 5000, 0, new Random(1));

        for (int i = 0; i < 10; i++) policy.nextDelayMs();

        assertEquals(5000, policy.nextDelayMs());
        assertEquals(5000, policy.nextDelayMs());
    }

    @Test public void jitterStaysWithinSymmetricBounds() {
        ReconnectPolicy policy = new ReconnectPolicy(1000, 30000, 0.2, new Random(42));

        for (int i = 0; i < 100; i++) {
            long delay = policy.nextDelayMs();
            long capped = Math.min(30000, (long) (1000 * Math.pow(2, i)));
            long lower = (long) (capped * 0.8);
            long upper = (long) (capped * 1.2);
            assertTrue("delay " + delay + " below " + lower, delay >= lower);
            assertTrue("delay " + delay + " above " + upper, delay <= upper);
        }
    }

    @Test public void resetRestartsTheSequence() {
        ReconnectPolicy policy = new ReconnectPolicy(1000, 30000, 0, new Random(1));

        policy.nextDelayMs();
        policy.nextDelayMs();
        policy.reset();

        assertEquals(0, policy.attempt());
        assertEquals(1000, policy.nextDelayMs());
    }

    @Test public void rejectsInvalidConfiguration() {
        assertRejected(-1, 30000, 0.2);
        assertRejected(1000, 500, 0.2);
        assertRejected(1000, 30000, 1.1);
    }

    private static void assertRejected(long baseMs, long maxMs, double jitter) {
        try {
            new ReconnectPolicy(baseMs, maxMs, jitter, new Random(1));
            org.junit.Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }
}
