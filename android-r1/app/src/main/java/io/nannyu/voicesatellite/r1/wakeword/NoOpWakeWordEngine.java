package io.nannyu.voicesatellite.r1.wakeword;

/**
 * Placeholder when Snowboy assets / model are unavailable.
 * Wake path still works via SatelliteActivity "Simulate Wake".
 */
public final class NoOpWakeWordEngine implements WakeWordEngine {
    private volatile boolean running;

    @Override public void start(Listener listener) {
        running = true;
        // Deliberately never fires onWakeWord.
    }

    @Override public void stop() { running = false; }

    @Override public boolean isRunning() { return running; }

    @Override public String name() { return "none"; }
}
