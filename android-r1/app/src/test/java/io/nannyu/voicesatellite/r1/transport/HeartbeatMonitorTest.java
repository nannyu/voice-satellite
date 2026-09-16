package io.nannyu.voicesatellite.r1.transport;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HeartbeatMonitorTest {
    private static final class FakeClock implements HeartbeatMonitor.Clock {
        long now;
        @Override public long nowMs() { return now; }
        void advance(long ms) { now += ms; }
    }

    @Test public void silentLinkRequestsHeartbeatAfterInterval() {
        FakeClock clock = new FakeClock();
        HeartbeatMonitor monitor = new HeartbeatMonitor(30000, 10000, clock);

        clock.advance(29999);
        assertEquals(HeartbeatMonitor.Verdict.ALIVE, monitor.check());
        clock.advance(1);
        assertEquals(HeartbeatMonitor.Verdict.HEARTBEAT_DUE, monitor.check());
    }

    @Test public void inboundFrameResetsTheInterval() {
        FakeClock clock = new FakeClock();
        HeartbeatMonitor monitor = new HeartbeatMonitor(30000, 10000, clock);

        clock.advance(25000);
        monitor.onInbound();
        clock.advance(25000);
        assertEquals(HeartbeatMonitor.Verdict.ALIVE, monitor.check());
        clock.advance(5000);
        assertEquals(HeartbeatMonitor.Verdict.HEARTBEAT_DUE, monitor.check());
    }

    @Test public void unansweredHeartbeatEventuallyDeclaresDead() {
        FakeClock clock = new FakeClock();
        HeartbeatMonitor monitor = new HeartbeatMonitor(30000, 10000, clock);

        clock.advance(30000);
        assertEquals(HeartbeatMonitor.Verdict.HEARTBEAT_DUE, monitor.check());
        monitor.onHeartbeatSent();

        clock.advance(9999);
        assertEquals(HeartbeatMonitor.Verdict.ALIVE, monitor.check());
        clock.advance(1);
        assertEquals(HeartbeatMonitor.Verdict.DEAD, monitor.check());
    }

    @Test public void inboundFrameWhileAwaitingPongKeepsLinkAlive() {
        FakeClock clock = new FakeClock();
        HeartbeatMonitor monitor = new HeartbeatMonitor(30000, 10000, clock);

        clock.advance(30000);
        monitor.onHeartbeatSent();
        clock.advance(5000);
        monitor.onInbound(); // any frame, not only an explicit pong
        clock.advance(60000);
        assertEquals(HeartbeatMonitor.Verdict.HEARTBEAT_DUE, monitor.check());
    }
}
