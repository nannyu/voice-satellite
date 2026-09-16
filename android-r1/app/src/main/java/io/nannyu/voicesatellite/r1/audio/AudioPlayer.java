package io.nannyu.voicesatellite.r1.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

/** Blocking 16 kHz mono PCM16 AudioTrack playback for the diagnostics build. */
public final class AudioPlayer {
    private static final int RATE=16000;

    public static final class Result {
        public boolean initialized,played;public int minBuffer,bytesExpected,bytesWritten;public String error;
    }

    /** Writes the whole buffer and returns after it has finished playing; call from a worker thread. */
    public Result playBlocking(byte[] pcm){
        Result result=new Result();AudioTrack track=null;
        try{
            int min=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);result.minBuffer=min;
            if(min<=0){result.error="getMinBufferSize="+min;return result;}
            track=new AudioTrack(AudioManager.STREAM_MUSIC,RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,pcm.length),AudioTrack.MODE_STREAM);
            result.initialized=track.getState()==AudioTrack.STATE_INITIALIZED;
            if(!result.initialized){result.error="AudioTrack not initialized";return result;}
            result.bytesExpected=pcm.length;
            track.play();
            long started=SystemClock.elapsedRealtime();
            int off=0;
            while(off<pcm.length){
                int n=track.write(pcm,off,pcm.length-off);
                if(n<0){result.error="write="+n;return result;}
                off+=n;
            }
            result.bytesWritten=off;
            long durationMs=(long)pcm.length/2*1000/RATE;
            long remaining=durationMs-(SystemClock.elapsedRealtime()-started);
            if(remaining>0)SystemClock.sleep(remaining);
            result.played=true;
        }catch(Throwable t){result.error=t.toString();}
        finally{if(track!=null){try{track.stop();}catch(Throwable ignored){}try{track.release();}catch(Throwable ignored){}}}
        return result;
    }
}
