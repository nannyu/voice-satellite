package io.nannyu.voicesatellite.r1;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.nannyu.voicesatellite.r1.audio.AudioRecorder;
import io.nannyu.voicesatellite.r1.audio.OpusDecoder;
import io.nannyu.voicesatellite.r1.audio.OpusEncoder;

public final class MainActivity extends Activity {
    private TextView status; private AudioRecorder recorder; private long frames;
    @Override public void onCreate(Bundle b){super.onCreate(b); LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);status=new TextView(this);status.setText("Voice Satellite R1\nAPI 22 / armeabi-v7a diagnostics");root.addView(status);
        Button nativeTest=new Button(this);nativeTest.setText("Test VAD + Opus JNI");nativeTest.setOnClickListener(v->testNative());root.addView(nativeTest);
        Button capture=new Button(this);capture.setText("Start / Stop microphone");capture.setOnClickListener(v->toggleCapture());root.addView(capture);setContentView(root);}
    private void testNative(){try{byte[] pcm=new byte[1920];try(OpusEncoder e=new OpusEncoder(16000,1,60);OpusDecoder d=new OpusDecoder(16000,1,120)){byte[] packet=e.encode(pcm);byte[] decoded=packet==null?null:d.decode(packet);status.setText("JNI OK\nOpus packet="+(packet==null?-1:packet.length)+" bytes\nDecoded="+(decoded==null?-1:decoded.length)+" bytes");}}catch(Throwable t){status.setText("JNI FAILED\n"+t);}}
    private void toggleCapture(){if(recorder!=null&&recorder.isRunning()){recorder.close();recorder=null;status.setText("Capture stopped. frames="+frames);return;}frames=0;recorder=new AudioRecorder();recorder.setListener(new AudioRecorder.Listener(){public void onSpeechStart(){runOnUiThread(()->status.setText("Speech started; VAD="+recorder.getVadMode()));}public void onAudioFrame(byte[] pcm){frames++;}public void onSpeechEnd(){runOnUiThread(()->status.setText("Speech ended. frames="+frames+"; VAD="+recorder.getVadMode()));}public void onError(String m){runOnUiThread(()->status.setText("Capture error: "+m));}});boolean ok=recorder.start();status.setText(ok?"Capture running...":"Capture failed");}
    @Override protected void onDestroy(){if(recorder!=null)recorder.close();super.onDestroy();}
}
