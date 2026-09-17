package io.nannyu.voicesatellite.r1.audio;

/** MIT-derived adapter from kitakeyos-dev/r1-manager; synchronized JNI lifetime and strict frames. */
public final class OpusEncoder implements AutoCloseable {
    static { System.loadLibrary("voice_sat_native"); }
    private long handle;
    private final int frameBytes;
    public OpusEncoder(int sampleRate, int channels, int frameMs) {
        if (!validRate(sampleRate) || (channels != 1 && channels != 2)
                || (frameMs != 10 && frameMs != 20 && frameMs != 40 && frameMs != 60))
            throw new IllegalArgumentException("unsupported Opus configuration");
        frameBytes = sampleRate / 1000 * frameMs * channels * 2;
        handle = nativeInitEncoder(sampleRate, channels, 2048);
        if (handle == 0) throw new IllegalStateException("Opus encoder init failed");
    }
    static boolean validRate(int rate) {
        return rate == 8000 || rate == 12000 || rate == 16000 || rate == 24000 || rate == 48000;
    }
    public synchronized int preSkipSamples() {
        requireOpen();
        int samples = nativeLookahead(handle);
        if (samples < 0) throw new IllegalStateException("Opus lookahead failed: " + samples);
        return samples;
    }
    public synchronized byte[] encode(byte[] pcm) {
        requireOpen();
        if (pcm == null || pcm.length != frameBytes)
            throw new IllegalArgumentException("expected " + frameBytes + " PCM bytes");
        byte[] out = new byte[4000];
        int count = nativeEncodeBytes(handle, pcm, pcm.length, out, out.length);
        if (count <= 0) throw new IllegalStateException("Opus encode failed: " + count);
        return java.util.Arrays.copyOf(out, count);
    }
    private void requireOpen() { if (handle == 0) throw new IllegalStateException("encoder closed"); }
    @Override public synchronized void close() {
        if (handle != 0) { nativeReleaseEncoder(handle); handle = 0; }
    }
    private native long nativeInitEncoder(int sampleRate, int channels, int application);
    private native int nativeLookahead(long handle);
    private native int nativeEncodeBytes(long handle, byte[] input, int inputSize, byte[] output, int maxOutputSize);
    private native void nativeReleaseEncoder(long handle);
}
