package io.nannyu.voicesatellite.r1;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
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

/** Explicit button/ADB audio MVP. No background or automatic microphone capture. */
public final class MainActivity extends Activity {
    private TextView status;
    private EditText endpoint, token;
    private CheckBox opus;
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
        endpoint.setHint("ws://<LAN gateway>:8765");
        String url = getIntent().getStringExtra("gateway_url");
        endpoint.setText(url == null ? getPreferences(0).getString("gateway_url", "") : url);
        root.addView(endpoint);
        token = new EditText(this);
        token.setSingleLine(true);
        token.setSaveEnabled(false);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint("Gateway token (optional; not saved)");
        token.setText(getIntent().getStringExtra("gateway_token"));
        root.addView(token);
        opus = new CheckBox(this);
        opus.setText("Opus / 16 kHz / mono / 60 ms");
        opus.setChecked(!"pcm_s16le".equals(getIntent().getStringExtra("audio_codec")));
        root.addView(opus);
        addButton(root, "Connect / Disconnect gateway", () -> {
            if (loopback == null) connect(false); else disconnect();
        });
        addButton(root, "Speak one request", () -> { if (loopback != null) loopback.trigger(); });
        addButton(root, "Cancel request", () -> { if (loopback != null) loopback.cancel(); });
        addButton(root, "Run / Cancel Audio Probe", this::runProbe);
        addButton(root, "Test Opus JNI", this::testNative);
        addButton(root, "Start / Stop microphone diagnostics", this::toggleCapture);
        status = new TextView(this);
        status.setText("Voice Satellite R1 / 0.3.0 MVP\nOpus audio gateway; HA/agent is not connected.");
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
        // State-only diagnostics for headless R1; never include endpoint credentials or PCM.
        Log.i("VoiceSatellite", message);
        runOnUiThread(() -> { if (!stopped && !isFinishing()) status.setText(message); });
    }
    private void connect(boolean oneShot) {
        if (probe != null || recorder != null) {
            show("Stop local microphone/probe diagnostics before connecting."); return;
        }
        String url = endpoint.getText().toString().trim();
        String id = getPreferences(0).getString("device_id", null);
        if (id == null) {
            id = "r1-" + UUID.randomUUID().toString();
            getPreferences(0).edit().putString("device_id", id).apply();
        }
        try {
            loopback = new AndroidLoopback(id, this::show, opus.isChecked(), token.getText().toString());
            loopback.connect(url, oneShot);
            getPreferences(0).edit().putString("gateway_url", url).apply();
        } catch (RuntimeException failure) {
            disconnect(); show("Connection failed; check the gateway address");
        }
    }
    private void disconnect() {
        if (loopback != null) { loopback.close(); loopback = null; }
        show("Gateway disconnected");
    }
    private void runProbe() {
        if (loopback != null || recorder != null) { show("Stop other audio diagnostics first."); return; }
        if (probe != null) { probe.cancel(); return; }
        final AudioProbe current = new AudioProbe(); probe = current;
        current.start(new AudioProbe.Listener() {
            @Override public void onProgress(String message) { show(message); }
            @Override public void onComplete(String report) {
                runOnUiThread(() -> { if (probe == current) { probe = null; show(report); } });
            }
        });
    }
    private void testNative() {
        try (OpusEncoder encoder = new OpusEncoder(16000, 1, 60);
                OpusDecoder decoder = new OpusDecoder(16000, 1, 60)) {
            byte[] packet = encoder.encode(new byte[1920]);
            byte[] decoded = decoder.decode(packet);
            show("JNI: packet=" + packet.length + ", decoded=" + decoded.length
                    + ", pre_skip=" + encoder.preSkipSamples());
        } catch (Throwable failure) { show("JNI failed: " + failure); }
    }
    private void toggleCapture() {
        if (loopback != null || probe != null) { show("Stop other audio diagnostics first."); return; }
        if (recorder != null) { recorder.close(); recorder = null; show("Capture stopped"); return; }
        frames = 0;
        final AudioRecorder current = new AudioRecorder(); recorder = current;
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
    @Override protected void onStop() { stopped = true; stopAudio(); super.onStop(); }
    @Override protected void onDestroy() { stopAudio(); super.onDestroy(); }
}
