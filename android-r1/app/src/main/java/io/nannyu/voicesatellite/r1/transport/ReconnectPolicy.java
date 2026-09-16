package io.nannyu.voicesatellite.r1.transport;

import java.util.Random;

/**
 * Pure reconnect backoff decisions: exponential growth from baseMs, capped at
 * maxMs, with symmetric jitter. The injected Random keeps JVM tests deterministic.
 */
public final class ReconnectPolicy {
    private final long baseMs,maxMs;
    private final double jitter;
    private final Random random;
    private int attempt;

    public ReconnectPolicy(long baseMs,long maxMs,double jitter,Random random){
        if(baseMs<=0||maxMs<baseMs)throw new IllegalArgumentException("require 0 < baseMs <= maxMs");
        if(jitter<0||jitter>1)throw new IllegalArgumentException("jitter must be within 0..1: "+jitter);
        if(random==null)throw new IllegalArgumentException("random is required");
        this.baseMs=baseMs;this.maxMs=maxMs;this.jitter=jitter;this.random=random;
    }

    /** Delay before the next reconnect attempt; each call advances the attempt counter. */
    public long nextDelayMs(){
        long capped=(long)Math.min(maxMs,baseMs*Math.pow(2,attempt));
        attempt++;
        if(jitter==0)return capped;
        double spread=capped*jitter;
        return Math.max(0,(long)(capped-spread+2*spread*random.nextDouble()));
    }

    public int attempt(){return attempt;}
    public void reset(){attempt=0;}
}
