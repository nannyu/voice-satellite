package io.nannyu.voicesatellite.r1.audio;

/** MIT-derived adapter from kitakeyos-dev/r1-manager; synchronized JNI lifetime and bounded output. */
public final class OpusDecoder implements AutoCloseable {
    static { System.loadLibrary("voice_sat_native"); }
    private long handle;
    private final int maxPcmBytes;
    public OpusDecoder(int sampleRate, int channels, int maxFrameMs) {
        if (!OpusEncoder.validRate(sampleRate) || (channels != 1 && channels != 2)
                || maxFrameMs < 10 || maxFrameMs > 120)
            throw new IllegalArgumentException("unsupported Opus decoder configuration");
        maxPcmBytes = sampleRate / 1000 * maxFrameMs * channels * 2;
        handle = nativeInitDecoder(sampleRate, channels);
        if (handle == 0) throw new IllegalStateException("Opus decoder init failed");
    }
    public synchronized byte[] decode(byte[] packet) {
        if (handle == 0) throw new IllegalStateException("decoder closed");
        if (packet == null || packet.length == 0 || packet.length > 4000)
            throw new IllegalArgumentException("invalid Opus packet size");
        byte[] pcm = new byte[maxPcmBytes];
        int count = nativeDecodeBytes(handle, packet, packet.length, pcm, pcm.length);
        if (count <= 0) throw new IllegalStateException("Opus decode failed: " + count);
        return java.util.Arrays.copyOf(pcm, count);
    }
    @Override public synchronized void close() {
        if (handle != 0) { nativeReleaseDecoder(handle); handle = 0; }
    }
    private native long nativeInitDecoder(int sampleRate, int channels);
    private native int nativeDecodeBytes(long handle, byte[] input, int inputSize, byte[] output, int maxOutputSize);
    private native void nativeReleaseDecoder(long handle);
}
