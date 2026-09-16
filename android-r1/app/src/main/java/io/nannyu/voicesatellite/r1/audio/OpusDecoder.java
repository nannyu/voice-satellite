package io.nannyu.voicesatellite.r1.audio;

/** MIT-derived adapter from kitakeyos-dev/r1-manager OpusDecoder. */
public final class OpusDecoder implements AutoCloseable {
    static { System.loadLibrary("voice_sat_native"); }
    private long handle; private final int maxPcmBytes;
    public OpusDecoder(int sampleRate,int channels,int maxFrameMs){
        maxPcmBytes=(sampleRate*maxFrameMs/1000)*channels*2;
        handle=nativeInitDecoder(sampleRate,channels);
        if(handle==0) throw new IllegalStateException("Opus decoder init failed");
    }
    public byte[] decode(byte[] opus){
        if(handle==0) throw new IllegalStateException("decoder closed");
        byte[] pcm=new byte[maxPcmBytes];
        int n=nativeDecodeBytes(handle,opus,opus.length,pcm,pcm.length);
        if(n<=0) return null;
        byte[] result=new byte[n]; System.arraycopy(pcm,0,result,0,n); return result;
    }
    @Override public synchronized void close(){ if(handle!=0){nativeReleaseDecoder(handle);handle=0;} }
    private native long nativeInitDecoder(int sampleRate,int channels);
    private native int nativeDecodeBytes(long handle,byte[] input,int inputSize,byte[] output,int maxOutputSize);
    private native void nativeReleaseDecoder(long handle);
}
