package io.nannyu.voicesatellite.r1.audio;

/** Pure PCM16 helpers without Android dependencies so they run in plain JVM unit tests. */
public final class PcmAudio {
    private PcmAudio(){}

    /** Averages interleaved little-endian stereo PCM16 into mono without overflow. */
    public static void downmixStereoToMono(byte[] stereo,byte[] mono){
        int outputSamples=Math.min(mono.length/2,stereo.length/4);
        for(int i=0;i<outputSamples;i++){
            int input=i*4;
            short left=(short)((stereo[input]&255)|(stereo[input+1]<<8));
            short right=(short)((stereo[input+2]&255)|(stereo[input+3]<<8));
            short mixed=(short)(((int)left+(int)right)/2);
            mono[i*2]=(byte)(mixed&255);mono[i*2+1]=(byte)((mixed>>8)&255);
        }
    }
}
