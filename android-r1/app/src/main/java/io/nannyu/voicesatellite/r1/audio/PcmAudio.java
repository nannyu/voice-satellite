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

    /** Generates a little-endian mono PCM16 sine wave; amplitude is a 0..1 fraction of full scale. */
    public static byte[] sineTone(int sampleRate,double frequencyHz,int durationMs,double amplitude){
        if(sampleRate<=0)throw new IllegalArgumentException("sampleRate must be positive: "+sampleRate);
        if(frequencyHz<=0)throw new IllegalArgumentException("frequencyHz must be positive: "+frequencyHz);
        if(durationMs<=0)throw new IllegalArgumentException("durationMs must be positive: "+durationMs);
        if(amplitude<0||amplitude>1)throw new IllegalArgumentException("amplitude must be within 0..1: "+amplitude);
        int samples=(int)((long)sampleRate*durationMs/1000);
        byte[] pcm=new byte[samples*2];
        double fullScale=amplitude*Short.MAX_VALUE;
        for(int i=0;i<samples;i++){
            short value=(short)(Math.sin(2*Math.PI*frequencyHz*i/sampleRate)*fullScale);
            pcm[i*2]=(byte)(value&255);pcm[i*2+1]=(byte)((value>>8)&255);
        }
        return pcm;
    }
}
