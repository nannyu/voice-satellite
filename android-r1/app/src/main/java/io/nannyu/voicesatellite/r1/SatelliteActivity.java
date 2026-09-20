package io.nannyu.voicesatellite.r1;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.nannyu.voicesatellite.r1.service.VoiceSatelliteService;
import io.nannyu.voicesatellite.r1.session.SessionController;

/**
 * Satellite UI: connect, TALK, Simulate Wake, session/wake status.
 * Phase A diagnostics remain on MainActivity.
 */
public final class SatelliteActivity extends Activity {
    private TextView urlField;
    private Button connectButton;
    private Button disconnectButton;
    private Button talkButton;
    private Button simulateWakeButton;
    private TextView status;
    private TextView log;

    private VoiceSatelliteService service;
    private boolean bound;
    private boolean wakeListening;
    private String wakeEngineName = "none";

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((VoiceSatelliteService.LocalBinder) binder).getService();
            bound = true;
            service.setCallback(callback);
            wakeEngineName = service.wakeEngineName();
            wakeListening = service.isWakeArmed();
            refreshButtons();
            appendLog("Service bound; wake engine=" + wakeEngineName);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
            refreshButtons();
        }
    };

    private final VoiceSatelliteService.Callback callback = new VoiceSatelliteService.Callback() {
        @Override public void onConnectionChanged(boolean connected, boolean ready) {
            refreshButtons();
            setStatusLine();
        }

        @Override public void onSessionState(SessionController.State state) {
            setStatusLine();
            refreshButtons();
        }

        @Override public void onWakeListening(boolean listening, String engineName) {
            wakeListening = listening;
            wakeEngineName = engineName;
            setStatusLine();
            refreshButtons();
        }

        @Override public void onError(String message) {
            appendLog("ERROR: " + message);
            setStatusLine();
        }

        @Override public void onLog(String line) {
            appendLog(line);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setTextSize(20);
        title.setText("Voice Satellite R1");
        root.addView(title);

        urlField = new TextView(this);
        urlField.setTextSize(16);
        urlField.setText("ws://192.168.1.18:8765");
        urlField.setPadding(8, 12, 8, 12);
        // R1 stock ClipboardManager is null; EditText + accessibility/uiautomator
        // crashes in TextView.canPaste. Keep URL as a plain TextView for MVP.
        root.addView(urlField);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        connectButton = new Button(this);
        connectButton.setText("Connect");
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (service != null) service.connect(urlField.getText().toString());
            }
        });
        disconnectButton = new Button(this);
        disconnectButton.setText("Disconnect");
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (service != null) service.disconnect();
            }
        });
        row.addView(connectButton);
        row.addView(disconnectButton);
        root.addView(row);

        talkButton = new Button(this);
        talkButton.setText("TALK");
        talkButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (service != null) service.onButtonPressed();
            }
        });
        root.addView(talkButton);

        simulateWakeButton = new Button(this);
        simulateWakeButton.setText("Simulate Wake");
        simulateWakeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (service != null) service.onSimulateWake();
            }
        });
        root.addView(simulateWakeButton);

        status = new TextView(this);
        status.setText("State: IDLE | DISCONNECTED\nWake: —");
        root.addView(status);

        Button diagnostics = new Button(this);
        diagnostics.setText("Open Diagnostics (Phase A)");
        diagnostics.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SatelliteActivity.this, MainActivity.class));
            }
        });
        root.addView(diagnostics);

        log = new TextView(this);
        log.setText("");
        log.setTextSize(12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        Intent serviceIntent = new Intent(this, VoiceSatelliteService.class);
        startService(serviceIntent);
        bindService(serviceIntent, connection, BIND_AUTO_CREATE);
        refreshButtons();
    }

    @Override protected void onDestroy() {
        if (bound && service != null) service.setCallback(null);
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        super.onDestroy();
    }

    private void refreshButtons() {
        boolean hasService = service != null;
        boolean connected = hasService && service.isConnected();
        boolean ready = hasService && service.isReady();
        SessionController.State state = hasService ? service.sessionState() : SessionController.State.IDLE;
        boolean idleReady = hasService && ready && state == SessionController.State.IDLE;
        connectButton.setEnabled(hasService && !connected);
        disconnectButton.setEnabled(hasService && connected);
        talkButton.setEnabled(idleReady);
        simulateWakeButton.setEnabled(idleReady);
        setStatusLine();
    }

    private void setStatusLine() {
        boolean connected = service != null && service.isConnected();
        boolean ready = service != null && service.isReady();
        SessionController.State state = service == null ? SessionController.State.IDLE : service.sessionState();
        String link = !connected ? "DISCONNECTED" : (ready ? "READY" : "CONNECTING…");
        String wake = wakeListening ? ("listening/" + wakeEngineName) : ("off/" + wakeEngineName);
        status.setText("State: " + state + " | " + link + "\nWake: " + wake);
    }

    private void appendLog(String line) {
        CharSequence existing = log.getText();
        String next = existing.length() == 0 ? line : existing + "\n" + line;
        String[] parts = next.split("\n");
        if (parts.length > 40) {
            StringBuilder trimmed = new StringBuilder();
            for (int i = parts.length - 40; i < parts.length; i++) {
                if (trimmed.length() > 0) trimmed.append('\n');
                trimmed.append(parts[i]);
            }
            next = trimmed.toString();
        }
        log.setText(next);
    }
}
