package io.nannyu.voicesatellite.r1.transport;

/**
 * Pure heartbeat decisions against an injected clock. The interval comes from
 * the server hello.ack (heartbeat_seconds); any inbound frame proves liveness.
 *
 * check() reports HEARTBEAT_DUE once the link has been silent for intervalMs;
 * after onHeartbeatSent() it reports ALIVE while waiting for any inbound frame,
 * and DEAD once timeoutMs elapses without one.
 */
public final class HeartbeatMonitor {
    public interface Clock { long nowMs(); }
    public enum Verdict { ALIVE, HEARTBEAT_DUE, DEAD }

    private final long intervalMs,timeoutMs;
    private final Clock clock;
    private long lastInboundMs;
    private long heartbeatSentMs=-1;

    public HeartbeatMonitor(long intervalMs,long timeoutMs,Clock clock){
        if(intervalMs<=0||timeoutMs<=0)throw new IllegalArgumentException("interval and timeout must be positive");
        if(clock==null)throw new IllegalArgumentException("clock is required");
        this.intervalMs=intervalMs;this.timeoutMs=timeoutMs;this.clock=clock;
        this.lastInboundMs=clock.nowMs();
    }

    /** Any inbound frame (JSON or binary) resets liveness and clears a pending heartbeat. */
    public void onInbound(){lastInboundMs=clock.nowMs();heartbeatSentMs=-1;}

    public void onHeartbeatSent(){heartbeatSentMs=clock.nowMs();}

    public Verdict check(){
        long now=clock.nowMs();
        if(heartbeatSentMs>=0)return now-heartbeatSentMs>=timeoutMs?Verdict.DEAD:Verdict.ALIVE;
        return now-lastInboundMs>=intervalMs?Verdict.HEARTBEAT_DUE:Verdict.ALIVE;
    }
}
