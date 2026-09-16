package io.nannyu.voicesatellite.r1.audio;

/** MIT-derived adapter from kitakeyos-dev/r1-manager OpusEncoder. */
public final class OpusEncoder implements AutoCloseable {
    static { System.loadLibrary("voice_sat_native"); }
    private long handle;
    private final int frameBytes;
    public OpusEncoder(int sampleRate, int channels, int frameMs) {
        frameBytes=(sampleRate*frameMs/1000)*channels*2;
        handle=nativeInitEncoder(sampleRate,channels,2048);
        if(handle==0) throw new IllegalStateException("Opus encoder init failed");
    }
    public byte[] encode(byte[] pcm) {
        if(handle==0) throw new IllegalStateException("encoder closed");
        if(pcm.length!=frameBytes) throw new IllegalArgumentException("expected "+frameBytes+" PCM bytes");
        byte[] out=new byte[4000];
        int n=nativeEncodeBytes(handle,pcm,pcm.length,out,out.length);
        if(n<=0) return null;
        byte[] result=new byte[n]; System.arraycopy(out,0,result,0,n); return result;
    }
    @Override public synchronized void close(){ if(handle!=0){ nativeReleaseEncoder(handle); handle=0; } }
    private native long nativeInitEncoder(int sampleRate,int channels,int application);
    private native int nativeEncodeBytes(long handle,byte[] input,int inputSize,byte[] output,int maxOutputSize);
    private native void nativeReleaseEncoder(long handle);
}
