package io.nannyu.voicesatellite.r1;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.nannyu.voicesatellite.r1.audio.AudioProbe;
import io.nannyu.voicesatellite.r1.audio.AudioRecorder;
import io.nannyu.voicesatellite.r1.audio.OpusDecoder;
import io.nannyu.voicesatellite.r1.audio.OpusEncoder;
import io.nannyu.voicesatellite.r1.kws.KwsPerfProbe;

public final class MainActivity extends Activity {
    private TextView status;
    private AudioRecorder recorder;
    private AudioProbe probe;
    private KwsPerfProbe kwsProbe;
    private long frames;
    private Button probeButton;
    private Button kwsButton;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("Voice Satellite R1\nAPI 22 / armeabi-v7a diagnostics");

        probeButton = new Button(this);
        probeButton.setText("Run automatic R1 Audio Probe");
        probeButton.setOnClickListener(v -> runProbe());
        root.addView(probeButton);

        kwsButton = new Button(this);
        kwsButton.setText("Run KWS Perf Probe (INT8/FP32 × 1/2t)");
        kwsButton.setOnClickListener(v -> runKwsProbe());
        root.addView(kwsButton);

        Button nativeTest = new Button(this);
        nativeTest.setText("Test VAD + Opus JNI");
        nativeTest.setOnClickListener(v -> testNative());
        root.addView(nativeTest);

        Button capture = new Button(this);
        capture.setText("Start / Stop microphone");
        capture.setOnClickListener(v -> toggleCapture());
        root.addView(capture);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void runProbe() {
        if (probe != null) { probe.cancel(); return; }
        if (kwsProbe != null) return;
        if (recorder != null && recorder.isRunning()) { recorder.close(); recorder = null; }
        probe = new AudioProbe();
        probeButton.setText("Cancel Audio Probe");
        status.setText("Starting probe...");
        probe.start(new AudioProbe.Listener() {
            public void onProgress(String text) { runOnUiThread(() -> status.setText(text)); }
            public void onComplete(String report) {
                runOnUiThread(() -> {
                    status.setText(report);
                    probe = null;
                    probeButton.setText("Run automatic R1 Audio Probe");
                });
            }
        });
    }

    private void runKwsProbe() {
        if (kwsProbe != null) { kwsProbe.cancel(); return; }
        if (probe != null) return;
        if (recorder != null && recorder.isRunning()) { recorder.close(); recorder = null; }
        kwsProbe = new KwsPerfProbe();
        kwsButton.setText("Cancel KWS Perf Probe");
        status.setText("Starting KWS perf probe...\nNeed /sdcard/kws-probe models (see docs/10).");
        kwsProbe.start(new KwsPerfProbe.Listener() {
            public void onProgress(String text) { runOnUiThread(() -> status.setText(text)); }
            public void onComplete(String report) {
                runOnUiThread(() -> {
                    status.setText(report);
                    kwsProbe = null;
                    kwsButton.setText("Run KWS Perf Probe (INT8/FP32 × 1/2t)");
                });
            }
        });
    }

    private void testNative() {
        try {
            byte[] pcm = new byte[1920];
            try (OpusEncoder e = new OpusEncoder(16000, 1, 60);
                 OpusDecoder d = new OpusDecoder(16000, 1, 120)) {
                byte[] packet = e.encode(pcm);
                byte[] decoded = packet == null ? null : d.decode(packet);
                status.setText("JNI OK\nOpus packet=" + (packet == null ? -1 : packet.length)
                        + " bytes\nDecoded=" + (decoded == null ? -1 : decoded.length) + " bytes");
            }
        } catch (Throwable t) {
            status.setText("JNI FAILED\n" + t);
        }
    }

    private void toggleCapture() {
        if (probe != null || kwsProbe != null) return;
        if (recorder != null && recorder.isRunning()) {
            recorder.close();
            recorder = null;
            status.setText("Capture stopped. frames=" + frames);
            return;
        }
        frames = 0;
        recorder = new AudioRecorder();
        recorder.setListener(new AudioRecorder.Listener() {
            public void onSpeechStart() {
                runOnUiThread(() -> status.setText("Speech started; VAD=" + recorder.getVadMode()));
            }
            public void onAudioFrame(byte[] pcm) { frames++; }
            public void onSpeechEnd() {
                runOnUiThread(() -> status.setText(
                        "Speech ended. frames=" + frames + "; VAD=" + recorder.getVadMode()));
            }
            public void onError(String m) {
                runOnUiThread(() -> status.setText("Capture error: " + m));
            }
        });
        boolean ok = recorder.start();
        status.setText(ok ? "Capture running..." : "Capture failed");
    }

    @Override protected void onDestroy() {
        if (probe != null) probe.cancel();
        if (kwsProbe != null) kwsProbe.cancel();
        if (recorder != null) recorder.close();
        super.onDestroy();
    }
}
