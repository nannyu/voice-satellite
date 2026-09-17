package io.nannyu.voicesatellite.r1.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

/** Blocking PCM diagnostics playback. The playback worker owns release(). */
public final class AudioPlayer {
    private static final int RATE = 16000;
    private AudioTrack active;
    private boolean busy;
    private volatile boolean cancelled;
    public static final class Result {
        public boolean initialized, played;
        public int minBuffer, bytesExpected, bytesWritten;
        public String error;
    }
    public synchronized void cancel() {
        cancelled = true;
        if (active != null) {
            try { active.stop(); active.flush(); } catch (RuntimeException ignored) { }
        }
    }
    public interface Cancellation { boolean cancelled(); }
    public Result playBlocking(byte[] pcm) { return playBlocking(pcm, () -> false); }
    public Result playBlocking(byte[] pcm, Cancellation external) {
        Result result = new Result();
        synchronized (this) {
            if (busy) { result.error = "playback already active"; return result; }
            busy = true;
            cancelled = false;
        }
        AudioTrack track = null;
        try {
            if (pcm == null || (pcm.length & 1) != 0) throw new IllegalArgumentException("PCM16 required");
            result.bytesExpected = pcm.length;
            if (pcm.length == 0) { result.played = true; return result; }
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            result.minBuffer = min;
            if (min <= 0) throw new IllegalStateException("getMinBufferSize=" + min);
            synchronized (this) {
                if (cancelled || external.cancelled()) { result.error = "cancelled"; return result; }
                track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 3840), AudioTrack.MODE_STREAM);
                active = track;
                result.initialized = track.getState() == AudioTrack.STATE_INITIALIZED;
                if (!result.initialized) throw new IllegalStateException("AudioTrack not initialized");
                track.play();
            }
            while (result.bytesWritten < pcm.length && !cancelled && !external.cancelled()) {
                int count = track.write(pcm, result.bytesWritten, Math.min(960, pcm.length - result.bytesWritten));
                if (count <= 0) throw new IllegalStateException("AudioTrack write=" + count);
                result.bytesWritten += count;
            }
            long deadline = SystemClock.elapsedRealtime() + pcm.length * 1000L / 32000 + 2000;
            while (!cancelled && !external.cancelled() && (track.getPlaybackHeadPosition() & 0xffffffffL) < result.bytesWritten / 2) {
                if (SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("playback drain timeout");
                SystemClock.sleep(10);
            }
            result.played = !cancelled && !external.cancelled();
            if (!result.played) result.error = "cancelled";
        } catch (RuntimeException failure) { result.error = failure.toString(); }
        finally {
            synchronized (this) {
                if (track != null) {
                    try { track.stop(); } catch (RuntimeException ignored) { }
                    try { track.release(); } catch (RuntimeException ignored) { }
                }
                active = null;
                busy = false;
            }
        }
        return result;
    }
}
