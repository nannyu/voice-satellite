package io.nannyu.voicesatellite.r1;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.UUID;
import io.nannyu.voicesatellite.r1.audio.AudioProbe;
import io.nannyu.voicesatellite.r1.audio.AudioRecorder;
import io.nannyu.voicesatellite.r1.audio.OpusDecoder;
import io.nannyu.voicesatellite.r1.audio.OpusEncoder;
import io.nannyu.voicesatellite.r1.session.AndroidLoopback;

/** Foreground diagnostics, including an explicit, button/ADB-triggered PCM echo test. */
public final class MainActivity extends Activity {
    private TextView status;
    private EditText endpoint;
    private AudioProbe probe;
    private AudioRecorder recorder;
    private AndroidLoopback loopback;
    private long frames;
    private boolean stopped;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        endpoint = new EditText(this);
        endpoint.setSingleLine(true);
        endpoint.setHint("ws://<LAN server>:8765");
        String url = getIntent().getStringExtra("gateway_url");
        endpoint.setText(url == null ? getPreferences(0).getString("gateway_url", "") : url);
        root.addView(endpoint);
        addButton(root, "Connect / Disconnect PCM loopback", () -> {
            if (loopback == null) connect(false); else disconnect();
        });
        addButton(root, "Speak one request", () -> { if (loopback != null) loopback.trigger(); });
        addButton(root, "Cancel request", () -> { if (loopback != null) loopback.cancel(); });
        addButton(root, "Run / Cancel Audio Probe", this::runProbe);
        addButton(root, "Test VAD + Opus JNI", this::testNative);
        addButton(root, "Start / Stop microphone diagnostics", this::toggleCapture);
        status = new TextView(this);
        status.setText("Voice Satellite R1 / API 22\nPCM echo diagnostics; HA is not connected.");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (getIntent().getBooleanExtra("loopback_once", false)) connect(true);
    }
    private void addButton(LinearLayout root, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        root.addView(button);
    }
    @Override protected void onStart() { super.onStart(); stopped = false; }
    private void show(String message) {
        runOnUiThread(() -> { if (!stopped && !isFinishing()) status.setText(message); });
    }
    private void connect(boolean oneShot) {
        if (probe != null || recorder != null) {
            show("Stop local microphone/probe diagnostics before connecting.");
            return;
        }
        String url = endpoint.getText().toString().trim();
        String id = getPreferences(0).getString("device_id", null);
        if (id == null) {
            id = "r1-" + UUID.randomUUID().toString();
            getPreferences(0).edit().putString("device_id", id).apply();
        }
        try {
            loopback = new AndroidLoopback(id, this::show);
            loopback.connect(url, oneShot);
            getPreferences(0).edit().putString("gateway_url", url).apply();
        } catch (RuntimeException failure) {
            disconnect();
            show("Connection failed: " + failure);
        }
    }
    private void disconnect() {
        if (loopback != null) { loopback.close(); loopback = null; }
        show("Loopback disconnected");
    }
    private void runProbe() {
        if (loopback != null || recorder != null) { show("Stop other audio diagnostics first."); return; }
        if (probe != null) { probe.cancel(); return; }
        final AudioProbe current = new AudioProbe();
        probe = current;
        current.start(new AudioProbe.Listener() {
            @Override public void onProgress(String message) { show(message); }
            @Override public void onComplete(String report) {
                runOnUiThread(() -> { if (probe == current) { probe = null; show(report); } });
            }
        });
    }
    private void testNative() {
        try (OpusEncoder encoder = new OpusEncoder(16000, 1, 60);
                OpusDecoder decoder = new OpusDecoder(16000, 1, 120)) {
            byte[] packet = encoder.encode(new byte[1920]);
            byte[] decoded = packet == null ? null : decoder.decode(packet);
            show("JNI: packet=" + (packet == null ? -1 : packet.length)
                    + ", decoded=" + (decoded == null ? -1 : decoded.length));
        } catch (Throwable failure) { show("JNI failed: " + failure); }
    }
    private void toggleCapture() {
        if (loopback != null || probe != null) { show("Stop other audio diagnostics first."); return; }
        if (recorder != null) { recorder.close(); recorder = null; show("Capture stopped"); return; }
        frames = 0;
        final AudioRecorder current = new AudioRecorder();
        recorder = current;
        current.setListener(new AudioRecorder.Listener() {
            @Override public void onSpeechStart() { show("Speech started; VAD=" + current.getVadMode()); }
            @Override public void onAudioFrame(byte[] frame) { frames++; }
            @Override public void onSpeechEnd() { show("Speech ended; frames=" + frames); }
            @Override public void onError(String message) { show("Capture error: " + message); }
        });
        show(current.start() ? "Capture running" : "Capture failed");
    }
    private void stopAudio() {
        if (loopback != null) { loopback.close(); loopback = null; }
        if (probe != null) probe.cancel();
        if (recorder != null) { recorder.close(); recorder = null; }
    }
    @Override protected void onStop() {
        stopped = true;
        stopAudio(); // No microphone capture while this diagnostics screen is hidden.
        super.onStop();
    }
    @Override protected void onDestroy() { stopAudio(); super.onDestroy(); }
}
