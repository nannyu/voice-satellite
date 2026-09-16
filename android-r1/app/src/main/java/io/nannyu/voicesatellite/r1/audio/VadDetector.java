package io.nannyu.voicesatellite.r1.audio;

/** MIT-derived adapter from kitakeyos-dev/r1-manager VadDetector. */
public final class VadDetector implements AutoCloseable {
    public static final int MODE_QUALITY=0, MODE_LOW_BITRATE=1, MODE_AGGRESSIVE=2, MODE_VERY_AGGRESSIVE=3;
    static { System.loadLibrary("voice_sat_native"); }
    private long handle;

    public synchronized boolean initialize(int mode) {
        if (handle != 0) return true;
        handle = nativeInitVad(mode);
        return handle != 0;
    }

    public boolean isSpeech(byte[] pcm, int offset, int length) {
        if (handle == 0) throw new IllegalStateException("VAD not initialized");
        if (pcm == null || offset < 0 || length < 0 || offset + length > pcm.length) throw new IllegalArgumentException("invalid PCM range");
        if (length != 320 && length != 640 && length != 960) throw new IllegalArgumentException("VAD frame must be 10/20/30 ms at 16 kHz mono PCM16");
        return nativeIsSpeechBytes(handle, pcm, offset, length) == 1;
    }

    public synchronized void reset() { if (handle != 0) nativeResetVad(handle); }
    @Override public synchronized void close() { if (handle != 0) { nativeFreeVad(handle); handle=0; } }

    private native long nativeInitVad(int mode);
    private native int nativeIsSpeechBytes(long handle, byte[] data, int offset, int length);
    private native void nativeResetVad(long handle);
    private native void nativeFreeVad(long handle);
}
