package io.nannyu.voicesatellite.r1.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Sequential, timeout-bounded hardware probe for the R1 microphone path and libfvad. */
public final class AudioProbe {
    public interface Listener { void onProgress(String text); void onComplete(String report); }

    private static final String TAG="R1AudioProbe";
    private static final int RATE=16000, FRAME_MS=30, FRAMES=100;
    private static final int PCM_BYTES=2, INPUT_CHANNELS=2;
    private static final int INPUT_FRAME_BYTES=RATE*FRAME_MS/1000*INPUT_CHANNELS*PCM_BYTES;
    private static final int MONO_FRAME_BYTES=RATE*FRAME_MS/1000*PCM_BYTES;
    private static final long SOURCE_TIMEOUT_MS=8000, STOP_GRACE_MS=1000;
    private volatile boolean cancelled;

    public void cancel(){cancelled=true;}
    public void start(Listener listener){cancelled=false;new Thread(()->run(listener),"r1-audio-probe").start();}

    private void run(Listener listener){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        int[] sources={MediaRecorder.AudioSource.MIC,MediaRecorder.AudioSource.VOICE_RECOGNITION,MediaRecorder.AudioSource.VOICE_COMMUNICATION};
        StringBuilder report=new StringBuilder("R1 AUDIO PROBE v2\n16 kHz / stereo capture / software mono / PCM16 / 30 ms\n\n");
        boolean abortRemaining=false;
        String abortReason=null;
        for(int source:sources){
            String sourceName=name(source);
            if(cancelled){abortRemaining=true;abortReason="probe cancelled";}
            Result result;
            if(abortRemaining){
                result=Result.skipped(sourceName,abortReason);
            }else{
                listener.onProgress("Testing "+sourceName+" stereo for 3 seconds...\nSpeak normally near the R1.\nHard timeout: 8 seconds.");
                result=probeWithTimeout(source);
                if(result.timedOut){
                    abortRemaining=true;
                    abortReason="skipped after "+sourceName+" timed out; audio service may require reboot";
                }
            }
            report.append(result.format()).append("\n");
        }
        if(cancelled)report.append("\nCANCELLED");
        listener.onComplete(report.toString());
    }

    private Result probeWithTimeout(int source){
        ProbeTask task=new ProbeTask(source);
        Thread worker=new Thread(task,"r1-audio-source-"+name(source));
        worker.start();
        try{worker.join(SOURCE_TIMEOUT_MS);}catch(InterruptedException e){Thread.currentThread().interrupt();cancelled=true;}
        if(!worker.isAlive())return task.result();

        Log.e(TAG,name(source)+" timed out; requesting asynchronous AudioRecord stop");
        task.requestStop();
        try{worker.join(STOP_GRACE_MS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        Result timeout=task.result();
        timeout.timedOut=true;
        timeout.error="hard timeout waiting for PCM data after "+SOURCE_TIMEOUT_MS+" ms"+(worker.isAlive()?"; capture thread still blocked":"; capture thread stopped during cleanup");
        return timeout;
    }

    private static final class ProbeTask implements Runnable {
        private final int source;
        private final Result result;
        private volatile boolean stopRequested;
        private volatile AudioRecord activeRecord;

        ProbeTask(int source){this.source=source;this.result=new Result(name(source));}
        Result result(){return result;}

        void requestStop(){
            stopRequested=true;
            final AudioRecord record=activeRecord;
            if(record==null)return;
            new Thread(()->release(record),"r1-audio-timeout-cleanup").start();
        }

        @Override public void run(){probe();}

        @SuppressLint("MissingPermission") private void probe(){
            AudioRecord record=null;VadDetector vad=null;
            try{
                int channelConfig=AudioFormat.CHANNEL_IN_STEREO;
                int min=AudioRecord.getMinBufferSize(RATE,channelConfig,AudioFormat.ENCODING_PCM_16BIT);result.minBuffer=min;
                if(min<=0){result.error="getMinBufferSize(stereo)="+min;return;}
                record=new AudioRecord(source,RATE,channelConfig,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,INPUT_FRAME_BYTES*4));
                activeRecord=record;
                result.initialized=record.getState()==AudioRecord.STATE_INITIALIZED;
                if(!result.initialized){result.error="AudioRecord stereo not initialized";return;}
                result.actualRate=record.getSampleRate();result.channelCount=record.getChannelCount();
                vad=new VadDetector();result.vadInitialized=vad.initialize(VadDetector.MODE_AGGRESSIVE);
                record.startRecording();result.recording=record.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING;
                if(!result.recording){result.error="AudioRecord did not enter RECORDSTATE_RECORDING";return;}
                Log.i(TAG,"started "+name(source)+" rate="+result.actualRate+" channels="+result.channelCount+" minBuffer="+min);

                byte[] stereoFrame=new byte[INPUT_FRAME_BYTES];byte[] monoFrame=new byte[MONO_FRAME_BYTES];
                long sumRms=0;List<Integer> rmsValues=new ArrayList<>();long started=SystemClock.elapsedRealtime();
                for(int i=0;i<FRAMES&&!stopRequested;i++){
                    int off=0;
                    while(off<INPUT_FRAME_BYTES&&!stopRequested){
                        int n=record.read(stereoFrame,off,INPUT_FRAME_BYTES-off);
                        if(n<0){result.error="read="+n;return;}
                        if(n==0){
                            if(SystemClock.elapsedRealtime()-started>SOURCE_TIMEOUT_MS){result.error="read returned no PCM data";return;}
                            Thread.yield();continue;
                        }
                        off+=n;
                    }
                    if(off!=INPUT_FRAME_BYTES)break;
                    PcmAudio.downmixStereoToMono(stereoFrame,monoFrame);
                    int rms=rms(monoFrame);rmsValues.add(rms);sumRms+=rms;result.frames++;result.peakRms=Math.max(result.peakRms,rms);
                    if(result.vadInitialized){
                        try{if(vad.isSpeech(monoFrame,0,MONO_FRAME_BYTES))result.vadSpeechFrames++;}
                        catch(RuntimeException e){result.vadError=e.toString();result.vadInitialized=false;}
                    }
                }
                result.avgRms=result.frames==0?0:(int)(sumRms/result.frames);
                for(int value:rmsValues)if(value>20)result.nonSilentFrames++;
                Log.i(TAG,"completed "+name(source)+" frames="+result.frames+" avgRms="+result.avgRms);
            }catch(Throwable t){result.error=t.toString();Log.e(TAG,"probe failed for "+name(source),t);}
            finally{
                activeRecord=null;
                if(record!=null)release(record);
                if(vad!=null)vad.close();
            }
        }
    }

    private static void release(AudioRecord record){
        try{if(record.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)record.stop();}catch(Throwable ignored){}
        try{record.release();}catch(Throwable ignored){}
    }

    private static int rms(byte[] pcm){long sum=0;int count=pcm.length/2;for(int i=0;i+1<pcm.length;i+=2){short sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));sum+=(long)sample*sample;}return count==0?0:(int)Math.sqrt(sum/count);}
    private static String name(int source){if(source==MediaRecorder.AudioSource.MIC)return "MIC";if(source==MediaRecorder.AudioSource.VOICE_RECOGNITION)return "VOICE_RECOGNITION";if(source==MediaRecorder.AudioSource.VOICE_COMMUNICATION)return "VOICE_COMMUNICATION";return String.valueOf(source);}

    private static final class Result {
        final String source;boolean initialized,recording,vadInitialized,timedOut,skipped;int minBuffer,actualRate,channelCount,frames,avgRms,peakRms,nonSilentFrames,vadSpeechFrames;String error,vadError;
        Result(String source){this.source=source;}
        static Result skipped(String source,String reason){Result result=new Result(source);result.skipped=true;result.error=reason;return result;}
        String format(){return String.format(Locale.US,"[%s]\ninit=%s recording=%s rate=%d channels=%d capture=stereo softwareMono=true minBuffer=%d\nframes=%d avgRms=%d peakRms=%d active=%d/%d\nVAD=%s speech=%d/%d timeout=%s skipped=%s%s%s\n",source,initialized,recording,actualRate,channelCount,minBuffer,frames,avgRms,peakRms,nonSilentFrames,frames,vadInitialized?"libfvad":"unavailable",vadSpeechFrames,frames,timedOut,skipped,error==null?"":"\nERROR: "+error,vadError==null?"":"\nVAD ERROR: "+vadError);}
    }
}
