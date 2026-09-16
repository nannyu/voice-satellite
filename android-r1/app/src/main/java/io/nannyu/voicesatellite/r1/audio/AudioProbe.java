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
    private static final int TONE_HZ=440, TONE_MS=1500;
    private static final double TONE_AMPLITUDE=0.5;
    private static final long BASELINE_MS=500, TRAILING_MS=500;
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
        if(!cancelled){
            listener.onProgress("Playing "+TONE_HZ+" Hz tone for "+(TONE_MS/1000)+" s while recording on MIC...\nHard timeout: 8 seconds.");
            report.append(probePlaybackWithTimeout().format()).append("\n");
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

    private PlaybackResult probePlaybackWithTimeout(){
        PlaybackTask task=new PlaybackTask();
        Thread worker=new Thread(task,"r1-audio-playback");
        worker.start();
        try{worker.join(SOURCE_TIMEOUT_MS);}catch(InterruptedException e){Thread.currentThread().interrupt();cancelled=true;}
        if(!worker.isAlive())return task.result();

        Log.e(TAG,"playback stage timed out; requesting asynchronous AudioRecord stop");
        task.requestStop();
        try{worker.join(STOP_GRACE_MS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        PlaybackResult timeout=task.result();
        timeout.timedOut=true;
        timeout.error="hard timeout after "+SOURCE_TIMEOUT_MS+" ms"+(worker.isAlive()?"; playback thread still blocked":"; playback thread stopped during cleanup");
        return timeout;
    }

    private static final class PlaybackTask implements Runnable {
        private final PlaybackResult result=new PlaybackResult();
        private volatile boolean stopRequested;
        private volatile AudioRecord activeRecord;

        PlaybackResult result(){return result;}

        void requestStop(){
            stopRequested=true;
            final AudioRecord record=activeRecord;
            if(record==null)return;
            new Thread(()->release(record),"r1-playback-timeout-cleanup").start();
        }

        @Override public void run(){probePlayback();}

        @SuppressLint("MissingPermission") private void probePlayback(){
            AudioRecord record=null;
            try{
                int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT);result.minBuffer=min;
                if(min<=0){result.error="getMinBufferSize(stereo)="+min;return;}
                record=new AudioRecord(MediaRecorder.AudioSource.MIC,RATE,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min*2,INPUT_FRAME_BYTES*4));
                activeRecord=record;
                result.captureInitialized=record.getState()==AudioRecord.STATE_INITIALIZED;
                if(!result.captureInitialized){result.error="AudioRecord stereo not initialized";return;}
                record.startRecording();result.recording=record.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING;
                if(!result.recording){result.error="AudioRecord did not enter RECORDSTATE_RECORDING";return;}

                final AudioRecord capture=record;
                Thread reader=new Thread(()->readPhases(capture),"r1-playback-capture");
                reader.start();
                SystemClock.sleep(BASELINE_MS);
                result.phase=1;
                result.track=new AudioPlayer().playBlocking(PcmAudio.sineTone(RATE,TONE_HZ,TONE_MS,TONE_AMPLITUDE));
                result.phase=2;
                SystemClock.sleep(TRAILING_MS);
                result.phase=3;
                stopRequested=true;
                try{reader.join(STOP_GRACE_MS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                Log.i(TAG,"playback done played="+(result.track!=null&&result.track.played)+" duringFrames="+result.frames[1]);
            }catch(Throwable t){result.error=t.toString();Log.e(TAG,"playback probe failed",t);}
            finally{
                activeRecord=null;
                if(record!=null)release(record);
            }
        }

        private void readPhases(AudioRecord record){
            byte[] stereoFrame=new byte[INPUT_FRAME_BYTES];byte[] monoFrame=new byte[MONO_FRAME_BYTES];
            long started=SystemClock.elapsedRealtime();
            while(!stopRequested&&result.phase<3){
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
                result.addFrame(result.phase,rms(monoFrame));
            }
        }
    }

    private static final class PlaybackResult {
        boolean captureInitialized,recording,timedOut;int minBuffer;String error;AudioPlayer.Result track;
        volatile int phase;
        final int[] frames=new int[3];final long[] sumRms=new long[3];final int[] peakRms=new int[3];

        synchronized void addFrame(int which,int value){if(which<0||which>2)return;frames[which]++;sumRms[which]+=value;peakRms[which]=Math.max(peakRms[which],value);}
        private int avgRms(int which){return frames[which]==0?0:(int)(sumRms[which]/frames[which]);}
        boolean simultaneous(){return track!=null&&track.played&&frames[1]>0;}
        String format(){
            AudioPlayer.Result t=track;
            return String.format(Locale.US,"[PLAYBACK]\ntone=%d Hz/%d ms/amplitude=%.2f trackInit=%s played=%s written=%d/%d minBuffer=%d\ncapture=stereo init=%s recording=%s baseline=%df/avgRms=%d during=%df/avgRms=%d/peak=%d after=%df/avgRms=%d\nsimultaneous=%s timeout=%s%s%s\n",
                TONE_HZ,TONE_MS,TONE_AMPLITUDE,
                t!=null&&t.initialized,t!=null&&t.played,t==null?0:t.bytesWritten,t==null?0:t.bytesExpected,t==null?0:t.minBuffer,
                captureInitialized,recording,frames[0],avgRms(0),frames[1],avgRms(1),peakRms[1],frames[2],avgRms(2),
                simultaneous(),timedOut,
                error==null?"":"\nERROR: "+error,
                t!=null&&t.error!=null?"\nTRACK ERROR: "+t.error:"");
        }
    }

    private static final class Result {
        final String source;boolean initialized,recording,vadInitialized,timedOut,skipped;int minBuffer,actualRate,channelCount,frames,avgRms,peakRms,nonSilentFrames,vadSpeechFrames;String error,vadError;
        Result(String source){this.source=source;}
        static Result skipped(String source,String reason){Result result=new Result(source);result.skipped=true;result.error=reason;return result;}
        String format(){return String.format(Locale.US,"[%s]\ninit=%s recording=%s rate=%d channels=%d capture=stereo softwareMono=true minBuffer=%d\nframes=%d avgRms=%d peakRms=%d active=%d/%d\nVAD=%s speech=%d/%d timeout=%s skipped=%s%s%s\n",source,initialized,recording,actualRate,channelCount,minBuffer,frames,avgRms,peakRms,nonSilentFrames,frames,vadInitialized?"libfvad":"unavailable",vadSpeechFrames,frames,timedOut,skipped,error==null?"":"\nERROR: "+error,vadError==null?"":"\nVAD ERROR: "+vadError);}
    }
}
