package io.nannyu.voicesatellite.r1.wakeword;

/**
 * Replaceable local wake-word detector (docs/02-r1-device.md).
 * Implementations must release the microphone in stop() before conversational
 * capture starts — R1 AudioFlinger cannot share the input path cleanly.
 */
public interface WakeWordEngine {
    interface Listener {
        void onWakeWord(String id, float confidence, long timestampMs);
        void onError(String message);
    }

    /** Start continuous listening. No-op if already running. */
    void start(Listener listener);

    /** Stop listening and release audio resources. */
    void stop();

    boolean isRunning();

    /** Human-readable engine id for diagnostics (e.g. "snowboy:pmdl:wakeword", "none"). */
    String name();
}
