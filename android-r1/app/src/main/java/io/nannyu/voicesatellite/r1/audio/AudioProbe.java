package io.nannyu.voicesatellite.r1.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Sequential hardware probe for the R1 microphone path and libfvad. */
public final class AudioProbe {
    public interface Listener { void onProgress(String text); void onComplete(String report); }
    private static final int RATE=16000, FRAME_BYTES=960, FRAMES=100; // 3 seconds/source
    private volatile boolean cancelled;

    public void cancel(){cancelled=true;}
    public void start(Listener listener){cancelled=false;new Thread(()->run(listener),"r1-audio-probe").start();}

    private void run(Listener listener){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        int[] sources={MediaRecorder.AudioSource.MIC,MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.VOICE_COMMUNICATION};
        StringBuilder report=new StringBuilder("R1 AUDIO PROBE\n16 kHz / mono / PCM16 / 30 ms\n\n");
        for(int source:sources){if(cancelled)break;String name=name(source);listener.onProgress("Testing "+name+" for 3 seconds...\nSpeak normally near the R1.");Result r=probe(source);report.append(r.format()).append("\n");}
        listener.onComplete(cancelled?report.append("\nCANCELLED").toString():report.toString());
    }

    @SuppressLint("MissingPermission") private Result probe(int source){
        Result out=new Result(name(source)); AudioRecord record=null; VadDetector vad=null;
        try{
            int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);out.minBuffer=min;
            if(min<=0){out.error="getMinBufferSize="+min;return out;}
            record=new AudioRecord(source,RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,FRAME_BYTES*4));
            out.initialized=record.getState()==AudioRecord.STATE_INITIALIZED;if(!out.initialized){out.error="AudioRecord not initialized";return out;}
            out.actualRate=record.getSampleRate();out.channelCount=record.getChannelCount();
            vad=new VadDetector();out.vadInitialized=vad.initialize(VadDetector.MODE_AGGRESSIVE);
            record.startRecording();out.recording=record.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING;
            byte[] frame=new byte[FRAME_BYTES];long sumRms=0;List<Integer> rmsValues=new ArrayList<>();
            for(int i=0;i<FRAMES&&!cancelled;i++){
                int off=0;while(off<FRAME_BYTES&&!cancelled){int n=record.read(frame,off,FRAME_BYTES-off);if(n<0){out.error="read="+n;return out;}off+=n;}
                if(off!=FRAME_BYTES)break;int rms=rms(frame);rmsValues.add(rms);sumRms+=rms;out.frames++;out.peakRms=Math.max(out.peakRms,rms);
                if(out.vadInitialized){try{if(vad.isSpeech(frame,0,FRAME_BYTES))out.vadSpeechFrames++;}catch(RuntimeException e){out.vadError=e.toString();out.vadInitialized=false;}}
            }
            out.avgRms=out.frames==0?0:(int)(sumRms/out.frames);out.nonSilentFrames=0;for(int v:rmsValues)if(v>20)out.nonSilentFrames++;
        }catch(Throwable t){out.error=t.toString();}
        finally{if(record!=null){try{if(record.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)record.stop();}catch(Exception ignored){}record.release();}if(vad!=null)vad.close();}
        return out;
    }

    private static int rms(byte[] p){long sum=0;int count=p.length/2;for(int i=0;i+1<p.length;i+=2){short s=(short)((p[i]&255)|(p[i+1]<<8));sum+=(long)s*s;}return count==0?0:(int)Math.sqrt(sum/count);}
    private static String name(int s){if(s==MediaRecorder.AudioSource.MIC)return "MIC";if(s==MediaRecorder.AudioSource.VOICE_RECOGNITION)return "VOICE_RECOGNITION";if(s==MediaRecorder.AudioSource.VOICE_COMMUNICATION)return "VOICE_COMMUNICATION";return String.valueOf(s);}

    private static final class Result {
        final String source;boolean initialized,recording,vadInitialized;int minBuffer,actualRate,channelCount,frames,avgRms,peakRms,nonSilentFrames,vadSpeechFrames;String error,vadError;
        Result(String source){this.source=source;}
        String format(){return String.format(Locale.US,"[%s]\ninit=%s recording=%s rate=%d channels=%d minBuffer=%d\nframes=%d avgRms=%d peakRms=%d active=%d/%d\nVAD=%s speech=%d/%d%s%s\n",source,initialized,recording,actualRate,channelCount,minBuffer,frames,avgRms,peakRms,nonSilentFrames,frames,vadInitialized?"libfvad":"unavailable",vadSpeechFrames,frames,error==null?"":"\nERROR: "+error,vadError==null?"":"\nVAD ERROR: "+vadError);}
    }
}
