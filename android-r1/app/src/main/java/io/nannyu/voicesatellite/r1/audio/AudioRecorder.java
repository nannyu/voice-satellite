package io.nannyu.voicesatellite.r1.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * R1 capture engine adapted from kitakeyos-dev/r1-manager (MIT).
 * 16 kHz mono PCM16, 30 ms frames, 300 ms pre-roll, libfvad with low-level amplitude fallback.
 */
public final class AudioRecorder implements AutoCloseable {
    public interface Listener { void onSpeechStart(); void onAudioFrame(byte[] pcm30ms); void onSpeechEnd(); void onError(String message); }
    private static final int RATE=16000, FRAME_BYTES=960, PRE_ROLL=10, END_SILENCE_FRAMES=27, MIN_SPEECH_FRAMES=3;
    private static final int RMS_SPEECH_THRESHOLD=20;
    private final AtomicBoolean running=new AtomicBoolean(false);
    private final byte[][] preRoll=new byte[PRE_ROLL][FRAME_BYTES];
    private int preIndex, speechFrames, silenceFrames, maxRms; private boolean amplitudeMode;
    private AudioRecord record; private Thread thread; private VadDetector vad; private Listener listener;
    private enum State { LISTENING, SPEAKING, SILENCE_CHECK } private State state=State.LISTENING;

    public void setListener(Listener l){listener=l;}
    @SuppressLint("MissingPermission") public synchronized boolean start(){
        if(running.get()) return true;
        vad=new VadDetector(); if(!vad.initialize(VadDetector.MODE_AGGRESSIVE)) amplitudeMode=true;
        int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0){error("unsupported AudioRecord configuration: "+min);return false;}
        try{
            record=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,FRAME_BYTES*4));
            if(record.getState()!=AudioRecord.STATE_INITIALIZED){error("AudioRecord initialization failed");close();return false;}
            reset(); record.startRecording(); running.set(true);
            thread=new Thread(this::loop,"r1-audio-capture"); thread.start(); return true;
        }catch(RuntimeException e){error(e.toString());close();return false;}
    }
    private void loop(){ Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); byte[] frame=new byte[FRAME_BYTES];
        while(running.get() && record!=null){ int off=0; while(off<FRAME_BYTES && running.get()){int n=record.read(frame,off,FRAME_BYTES-off);if(n<0){error("AudioRecord read="+n);running.set(false);break;}off+=n;} if(off==FRAME_BYTES) process(frame); }
    }
    private void process(byte[] frame){ int rms=rms(frame); if(rms>maxRms)maxRms=rms; boolean nativeSpeech=false;
        if(!amplitudeMode){try{nativeSpeech=vad.isSpeech(frame,0,FRAME_BYTES);}catch(RuntimeException ignored){amplitudeMode=true;}}
        boolean ampSpeech=rms>RMS_SPEECH_THRESHOLD; if(!amplitudeMode && ampSpeech && !nativeSpeech && maxRms<100) amplitudeMode=true;
        boolean speech=amplitudeMode?ampSpeech:nativeSpeech;
        switch(state){
            case LISTENING: System.arraycopy(frame,0,preRoll[preIndex],0,FRAME_BYTES);preIndex=(preIndex+1)%PRE_ROLL; if(speech){if(++speechFrames>=MIN_SPEECH_FRAMES){state=State.SPEAKING;if(listener!=null){listener.onSpeechStart();for(int i=0;i<PRE_ROLL;i++)listener.onAudioFrame(copy(preRoll[(preIndex+i)%PRE_ROLL]));listener.onAudioFrame(copy(frame));}}}else speechFrames=0; break;
            case SPEAKING: emit(frame); if(speech)silenceFrames=0;else{state=State.SILENCE_CHECK;silenceFrames=1;} break;
            case SILENCE_CHECK: emit(frame); if(speech){state=State.SPEAKING;silenceFrames=0;}else if(++silenceFrames>=END_SILENCE_FRAMES){if(listener!=null)listener.onSpeechEnd();reset();} break;
        }
    }
    private void emit(byte[] f){if(listener!=null)listener.onAudioFrame(copy(f));}
    private static byte[] copy(byte[] f){return Arrays.copyOf(f,f.length);}
    private static int rms(byte[] p){long sum=0;int count=p.length/2;for(int i=0;i+1<p.length;i+=2){short s=(short)((p[i]&255)|(p[i+1]<<8));sum+=(long)s*s;}return count==0?0:(int)Math.sqrt(sum/count);}
    private void reset(){state=State.LISTENING;preIndex=speechFrames=silenceFrames=0;for(byte[] b:preRoll)Arrays.fill(b,(byte)0);if(vad!=null)vad.reset();}
    private void error(String s){if(listener!=null)listener.onError(s);}
    public boolean isRunning(){return running.get();} public String getVadMode(){return amplitudeMode?"amplitude":"libfvad";}
    @Override public synchronized void close(){running.set(false);if(record!=null){try{if(record.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)record.stop();}catch(Exception ignored){}record.release();record=null;}if(vad!=null){vad.close();vad=null;}thread=null;}
}
