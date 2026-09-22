package io.nannyu.voicesatellite.r1.wakeword;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.File;
import java.util.Locale;

import ai.kitt.snowboy.SnowboyDetect;

/**
 * Fully offline Snowboy wake-word engine for API 22 / armeabi-v7a.
 * No account, no AccessKey, no network.
 *
 * Owns AudioRecord while running; callers must {@link #stop()} before opening
 * conversational capture on R1 (mic cannot be shared cleanly).
 */
public final class SnowboyWakeWordEngine implements WakeWordEngine {
    private static final String NATIVE_LIB = "snowboy-detect-android";
    /** ~100 ms at 16 kHz mono 16-bit — matches official Android demo chunk size. */
    private static final int DEFAULT_CHUNK_SAMPLES = 1600;

    private final Context appContext;
    private final File commonRes;
    private final File model;
    private final boolean personalModel;
    private final String sensitivity;
    private final String modelLabel;

    private final Object lock = new Object();
    private volatile boolean running;
    private Thread thread;
    private AudioRecord record;
    private SnowboyDetect detector;
    private Listener listener;

    static {
        System.loadLibrary(NATIVE_LIB);
    }

    public SnowboyWakeWordEngine(Context context, SnowboyAssets.Bundle bundle) {
        this(context, bundle, null);
    }

    public SnowboyWakeWordEngine(
            Context context, SnowboyAssets.Bundle bundle, String sensitivity) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (bundle == null) throw new IllegalArgumentException("bundle is required");
        if (bundle.commonRes == null || !bundle.commonRes.isFile()) {
            throw new IllegalArgumentException("common.res missing");
        }
        if (bundle.model == null || !bundle.model.isFile()) {
            throw new IllegalArgumentException("wake model missing");
        }
        this.appContext = context.getApplicationContext();
        this.commonRes = bundle.commonRes;
        this.model = bundle.model;
        this.personalModel = bundle.personalModel;
        this.sensitivity = (sensitivity == null || sensitivity.trim().isEmpty())
                ? (bundle.personalModel ? "0.5" : "0.5")
                : sensitivity.trim();
        this.modelLabel = stripExt(bundle.model.getName()).toLowerCase(Locale.US);
    }

    @Override public String name() {
        return personalModel ? "snowboy:pmdl:" + modelLabel : "snowboy:umdl:" + modelLabel;
    }

    @Override public boolean isRunning() { return running; }

    @Override public void start(Listener listener) {
        synchronized (lock) {
            if (running) return;
            if (listener == null) throw new IllegalArgumentException("listener is required");
            this.listener = listener;
            running = true;
            thread = new Thread(this::loop, "r1-wake-snowboy");
            thread.start();
        }
    }

    @Override public void stop() {
        Thread toJoin;
        synchronized (lock) {
            running = false;
            toJoin = thread;
            thread = null;
            if (record != null) {
                try {
                    if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        record.stop();
                    }
                } catch (RuntimeException ignored) {}
                try { record.release(); } catch (RuntimeException ignored) {}
                record = null;
            }
            if (detector != null) {
                try { detector.delete(); } catch (RuntimeException ignored) {}
                detector = null;
            }
        }
        if (toJoin != null) {
            try { toJoin.join(1500); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        Listener sink = listener;
        SnowboyDetect engine = null;
        AudioRecord ar = null;
        try {
            engine = new SnowboyDetect(commonRes.getAbsolutePath(), model.getAbsolutePath());
            engine.SetSensitivity(sensitivity);
            // Official guidance: frontend on for personal .pmdl, off for universal .umdl.
            engine.ApplyFrontend(personalModel);
            engine.Reset();

            int sampleRate = engine.SampleRate();
            if (sampleRate <= 0) sampleRate = 16000;
            int chunk = DEFAULT_CHUNK_SAMPLES;
            int min = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                fail(sink, "unsupported AudioRecord configuration: " + min);
                return;
            }
            ar = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, chunk * 4));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                ar.release();
                ar = null;
                fail(sink, "AudioRecord initialization failed for wake word");
                return;
            }

            synchronized (lock) {
                if (!running) {
                    ar.release();
                    engine.delete();
                    return;
                }
                detector = engine;
                record = ar;
                engine = null;
                ar = null;
            }

            record.startRecording();
            short[] frame = new short[chunk];
            while (running) {
                int off = 0;
                while (off < chunk && running) {
                    int n = record.read(frame, off, chunk - off);
                    if (n < 0) {
                        fail(sink, "AudioRecord read=" + n);
                        return;
                    }
                    off += n;
                }
                if (!running || off < chunk) break;
                int result = detector.RunDetection(frame, off);
                if (result > 0 && sink != null) {
                    sink.onWakeWord(modelLabel, 1.0f, System.currentTimeMillis());
                    // One-shot: stop after detection; service restarts when back to IDLE.
                    break;
                }
                if (result == -1 && sink != null) {
                    fail(sink, "Snowboy RunDetection error");
                    return;
                }
            }
        } catch (UnsatisfiedLinkError e) {
            fail(sink, "native lib missing: " + e.getMessage());
        } catch (RuntimeException e) {
            fail(sink, e.toString());
        } finally {
            if (ar != null) {
                try { ar.release(); } catch (RuntimeException ignored) {}
            }
            if (engine != null) {
                try { engine.delete(); } catch (RuntimeException ignored) {}
            }
            stop();
        }
    }

    private void fail(Listener sink, String message) {
        running = false;
        if (sink != null) sink.onError(message);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
