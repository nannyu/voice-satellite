package io.nannyu.voicesatellite.r1.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import io.nannyu.voicesatellite.r1.SatelliteActivity;
import io.nannyu.voicesatellite.r1.audio.AudioPlayer;
import io.nannyu.voicesatellite.r1.audio.AudioRecorder;
import io.nannyu.voicesatellite.r1.protocol.Protocol;
import io.nannyu.voicesatellite.r1.session.HandlerScheduler;
import io.nannyu.voicesatellite.r1.session.SessionController;
import io.nannyu.voicesatellite.r1.transport.ConnectionSupervisor;
import io.nannyu.voicesatellite.r1.transport.WebSocketTransport;
import io.nannyu.voicesatellite.r1.wakeword.WakeWordEngine;
import io.nannyu.voicesatellite.r1.wakeword.WakeWordEngines;

/**
 * Owns the voice-session wiring for Checklist B: ConnectionSupervisor +
 * SessionController + AudioRecorder/AudioPlayer. UI binds via LocalBinder.
 * ConnectionSupervisor.stop() permanently shuts its executor, so each
 * connect() allocates a fresh supervisor.
 */
public final class VoiceSatelliteService extends Service {
    public interface Callback {
        void onConnectionChanged(boolean connected, boolean ready);
        void onSessionState(SessionController.State state);
        void onWakeListening(boolean listening, String engineName);
        void onError(String message);
        void onLog(String line);
    }

    public final class LocalBinder extends Binder {
        public VoiceSatelliteService getService() { return VoiceSatelliteService.this; }
    }

    private static final long NO_SPEECH_TIMEOUT_MS = 8000;
    private static final long RESPONSE_TIMEOUT_MS = 20000;
    private static final long MAX_UTTERANCE_MS = 10000;
    private static final long RECONNECT_BASE_MS = 1000;
    private static final long RECONNECT_MAX_MS = 30000;
    private static final double RECONNECT_JITTER = 0.2;
    private static final long HEARTBEAT_TIMEOUT_MS = 10000;
    private static final int NOTIFICATION_ID = 1001;

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AudioPlayer audioPlayer = new AudioPlayer();
    private final AtomicBoolean playbackActive = new AtomicBoolean(false);

    private Callback callback;
    private SessionController session;
    private ConnectionSupervisor supervisor;
    private WebSocketTransport transport;
    private AudioRecorder recorder;
    private Thread playerThread;
    private WakeWordEngine wakeEngine;
    private String voiceTrigger = Protocol.TRIGGER_BUTTON;
    private volatile boolean wakeArmed;

    private volatile boolean connected;
    private volatile boolean ready;
    private byte[] responsePcm = new byte[0];
    private int responseLength;
    private boolean collectingResponse;
    private Object maxUtteranceHandle;

    @Override public void onCreate() {
        super.onCreate();
        session = new SessionController(sessionListener, new HandlerScheduler(mainHandler),
                NO_SPEECH_TIMEOUT_MS, RESPONSE_TIMEOUT_MS);
        wakeEngine = WakeWordEngines.create(this);
        emitLog("Wake engine: " + wakeEngine.name());
        startForeground(NOTIFICATION_ID, buildNotification("Idle"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onDestroy() {
        stopWakeListening();
        disconnect();
        if (transport != null) { transport.shutdown(); transport = null; }
        super.onDestroy();
    }

    public void setCallback(Callback callback) { this.callback = callback; }

    public SessionController.State sessionState() { return session.state(); }
    public boolean isConnected() { return connected; }
    public boolean isReady() { return ready; }
    public boolean isWakeArmed() { return wakeArmed; }
    public String wakeEngineName() { return wakeEngine == null ? "none" : wakeEngine.name(); }

    public synchronized void connect(String url) {
        if (url == null || url.trim().isEmpty()) {
            emitError("WebSocket URL is required");
            return;
        }
        disconnectInternal(false);
        transport = new WebSocketTransport();
        supervisor = new ConnectionSupervisor(transport, supervisorListener,
                RECONNECT_BASE_MS, RECONNECT_MAX_MS, RECONNECT_JITTER, HEARTBEAT_TIMEOUT_MS);
        try {
            supervisor.start(url.trim());
            emitLog("Connecting to " + url.trim());
            updateNotification("Connecting…");
        } catch (RuntimeException e) {
            emitError("Connect failed: " + e.getMessage());
            disconnectInternal(false);
        }
    }

    public synchronized void disconnect() { disconnectInternal(true); }

    public void onButtonPressed() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                beginListening(Protocol.TRIGGER_BUTTON, SessionController.Event.BUTTON_PRESSED);
            }
        });
    }

    /** Test hook for the wake path without a spoken keyword / model. */
    public void onSimulateWake() {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                beginListening(Protocol.TRIGGER_WAKE_WORD, SessionController.Event.WAKE_DETECTED);
            }
        });
    }

    private void beginListening(String trigger, SessionController.Event event) {
        if (!ready) { emitError("Not ready (waiting for hello.ack)"); return; }
        if (session.state() != SessionController.State.IDLE) {
            emitError("Busy: " + session.state());
            return;
        }
        voiceTrigger = trigger;
        stopWakeListening();
        session.onEvent(event);
    }

    private void disconnectInternal(boolean notify) {
        ready = false;
        connected = false;
        cancelMaxUtterance();
        stopWakeListening();
        stopRecorder();
        stopPlayback();
        collectingResponse = false;
        responsePcm = new byte[0];
        responseLength = 0;
        if (supervisor != null) {
            try { supervisor.stop(); } catch (RuntimeException ignored) {}
            supervisor = null;
        }
        if (transport != null) {
            try { transport.shutdown(); } catch (RuntimeException ignored) {}
            transport = null;
        }
        if (session.state() != SessionController.State.IDLE) {
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (session.state() != SessionController.State.IDLE) {
                        session.onEvent(SessionController.Event.NETWORK_LOST);
                    }
                }
            });
        }
        if (notify) {
            emitConnection(false, false);
            emitLog("Disconnected");
            updateNotification("Disconnected");
        }
    }

    private final SessionController.Listener sessionListener = new SessionController.Listener() {
        @Override public void onTransition(SessionController.State from, SessionController.State to,
                                           SessionController.Event event) {
            emitLog("Session " + from + " → " + to + " (" + event + ")");
            emitSession(to);
            updateNotification(to.name());
            if (to == SessionController.State.LISTENING) {
                startRecorder();
            } else if (from == SessionController.State.LISTENING) {
                cancelMaxUtterance();
                stopRecorder();
            }
            if (to == SessionController.State.IDLE && ready) {
                startWakeListening();
            } else if (from == SessionController.State.IDLE) {
                stopWakeListening();
            }
        }

        @Override public void onIllegalTransition(SessionController.State state,
                                                   SessionController.Event event) {
            // Expected for late PLAYBACK_FINISHED after SERVER_IDLE; keep quiet unless surprising.
            if (event != SessionController.Event.PLAYBACK_FINISHED) {
                emitLog("Ignored " + event + " in " + state);
            }
        }
    };

    private final ConnectionSupervisor.Listener supervisorListener = new ConnectionSupervisor.Listener() {
        @Override public void onConnected() {
            connected = true;
            ready = false;
            emitConnection(true, false);
            emitLog("Socket open; sending hello");
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (deviceId == null || deviceId.trim().isEmpty()) deviceId = "r1-unknown";
            boolean sent = supervisor != null && supervisor.send(Protocol.hello(
                    "r1-" + deviceId,
                    "phicomm-r1",
                    "0.2.0-dev",
                    "android-5.1",
                    Arrays.asList("audio.pcm16", "wake.local")));
            if (!sent) emitError("Failed to send hello");
            updateNotification("Handshaking…");
        }

        @Override public void onMessage(Protocol.Message message) {
            handleMessage(message);
        }

        @Override public void onAudio(byte[] frame) {
            if (!collectingResponse || frame == null || frame.length == 0) return;
            ensureResponseCapacity(responseLength + frame.length);
            System.arraycopy(frame, 0, responsePcm, responseLength, frame.length);
            responseLength += frame.length;
        }

        @Override public void onDisconnected(boolean willReconnect) {
            connected = false;
            ready = false;
            collectingResponse = false;
            stopWakeListening();
            stopRecorder();
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (session.state() != SessionController.State.IDLE) {
                        session.onEvent(SessionController.Event.NETWORK_LOST);
                    }
                }
            });
            emitConnection(false, false);
            emitLog(willReconnect ? "Disconnected; reconnecting…" : "Disconnected");
            updateNotification(willReconnect ? "Reconnecting…" : "Disconnected");
        }
    };

    private void handleMessage(Protocol.Message message) {
        if (Protocol.TYPE_HELLO_ACK.equals(message.type)) {
            ready = true;
            emitConnection(true, true);
            emitLog("Ready session_id=" + (supervisor == null ? "?" : supervisor.sessionId()));
            updateNotification("Ready");
            mainHandler.post(new Runnable() {
                @Override public void run() {
                    if (ready && session.state() == SessionController.State.IDLE) {
                        startWakeListening();
                    }
                }
            });
            return;
        }
        if (Protocol.TYPE_STATE.equals(message.type)) {
            try {
                String value = Protocol.stateValue(message);
                if ("processing".equals(value)) {
                    postEvent(SessionController.Event.SERVER_PROCESSING);
                } else if ("idle".equals(value)) {
                    // While SPEAKING, wait for PLAYBACK_FINISHED so audio can finish.
                    if (session.state() != SessionController.State.SPEAKING) {
                        postEvent(SessionController.Event.SERVER_IDLE);
                    }
                }
            } catch (Protocol.ProtocolException e) {
                emitError(e.getMessage());
            }
            return;
        }
        if (Protocol.TYPE_RESPONSE_START.equals(message.type)) {
            collectingResponse = true;
            responseLength = 0;
            responsePcm = new byte[16 * 1024];
            postEvent(SessionController.Event.SERVER_RESPONDING);
            return;
        }
        if (Protocol.TYPE_RESPONSE_END.equals(message.type)) {
            collectingResponse = false;
            final byte[] pcm = Arrays.copyOf(responsePcm, responseLength);
            responseLength = 0;
            startPlayback(pcm);
            return;
        }
        if (Protocol.TYPE_ERROR.equals(message.type)) {
            try {
                Protocol.ErrorMessage err = Protocol.errorValue(message);
                emitError(err.code + ": " + err.message);
                if (!err.recoverable) postEvent(SessionController.Event.ERROR);
            } catch (Protocol.ProtocolException e) {
                emitError(e.getMessage());
            }
        }
    }

    private final AudioRecorder.Listener recorderListener = new AudioRecorder.Listener() {
        @Override public void onSpeechStart() {
            ConnectionSupervisor s = supervisor;
            String sid = s == null ? null : s.sessionId();
            if (s == null || sid == null || session.state() != SessionController.State.LISTENING) return;
            if (!s.send(Protocol.voiceStart(sid, voiceTrigger))) {
                emitError("Failed to send voice.start");
                postEvent(SessionController.Event.ERROR);
                return;
            }
            postEvent(SessionController.Event.SPEECH_STARTED);
            armMaxUtterance();
            emitLog("voice.start trigger=" + voiceTrigger + " (VAD=" + (recorder == null ? "?" : recorder.getVadMode()) + ")");
        }

        @Override public void onAudioFrame(byte[] pcm30ms) {
            if (session.state() != SessionController.State.LISTENING) return;
            ConnectionSupervisor s = supervisor;
            if (s != null) s.sendAudio(pcm30ms);
        }

        @Override public void onSpeechEnd() {
            if (session.state() != SessionController.State.LISTENING) return;
            cancelMaxUtterance();
            ConnectionSupervisor s = supervisor;
            String sid = s == null ? null : s.sessionId();
            if (s != null && sid != null) {
                s.send(Protocol.voiceEnd(sid, Protocol.REASON_VAD_END));
            }
            postEvent(SessionController.Event.SPEECH_ENDED);
            emitLog("voice.end");
        }

        @Override public void onError(String message) {
            emitError("Capture: " + message);
            postEvent(SessionController.Event.ERROR);
        }
    };

    private void armMaxUtterance() {
        cancelMaxUtterance();
        maxUtteranceHandle = new Runnable() {
            @Override public void run() {
                maxUtteranceHandle = null;
                if (session.state() != SessionController.State.LISTENING) return;
                emitLog("Max utterance timeout");
                ConnectionSupervisor s = supervisor;
                String sid = s == null ? null : s.sessionId();
                if (s != null && sid != null) {
                    s.send(Protocol.voiceEnd(sid, Protocol.REASON_TIMEOUT));
                }
                session.onEvent(SessionController.Event.SPEECH_ENDED);
            }
        };
        mainHandler.postDelayed((Runnable) maxUtteranceHandle, MAX_UTTERANCE_MS);
    }

    private void cancelMaxUtterance() {
        if (maxUtteranceHandle instanceof Runnable) {
            mainHandler.removeCallbacks((Runnable) maxUtteranceHandle);
        }
        maxUtteranceHandle = null;
    }

    private synchronized void startRecorder() {
        stopWakeListening();
        stopRecorder();
        recorder = new AudioRecorder();
        recorder.setListener(recorderListener);
        if (!recorder.start()) {
            emitError("AudioRecorder failed to start (is Unisound hidden?)");
            postEvent(SessionController.Event.ERROR);
        } else {
            emitLog("Mic listening (" + recorder.getVadMode() + ")");
        }
    }

    private synchronized void stopRecorder() {
        if (recorder != null) {
            recorder.close();
            recorder = null;
        }
    }

    private synchronized void startWakeListening() {
        if (!ready || session.state() != SessionController.State.IDLE) return;
        if (wakeEngine == null) wakeEngine = WakeWordEngines.create(this);
        if (wakeEngine.isRunning()) return;
        if ("none".equals(wakeEngine.name())) {
            wakeArmed = false;
            emitWake(false);
            emitLog("Wake armed=false (engine=none; use Simulate Wake or add assets/snowboy/*.pmdl)");
            return;
        }
        stopRecorder();
        wakeEngine.start(new WakeWordEngine.Listener() {
            @Override public void onWakeWord(String id, float confidence, long timestampMs) {
                emitLog("Wake word: " + id + " conf=" + confidence);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        beginListening(Protocol.TRIGGER_WAKE_WORD, SessionController.Event.WAKE_DETECTED);
                    }
                });
            }

            @Override public void onError(String message) {
                emitError("Wake: " + message);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        wakeArmed = false;
                        emitWake(false);
                    }
                });
            }
        });
        wakeArmed = true;
        emitWake(true);
        emitLog("Wake listening (" + wakeEngine.name() + ")");
        updateNotification("Wake: " + wakeEngine.name());
    }

    private synchronized void stopWakeListening() {
        wakeArmed = false;
        if (wakeEngine != null && wakeEngine.isRunning()) {
            wakeEngine.stop();
        }
        emitWake(false);
    }

    private void startPlayback(final byte[] pcm) {
        stopPlayback();
        if (pcm.length == 0) {
            postEvent(SessionController.Event.PLAYBACK_FINISHED);
            // Also settle to idle if still speaking with empty response.
            postEvent(SessionController.Event.SERVER_IDLE);
            return;
        }
        playbackActive.set(true);
        playerThread = new Thread(new Runnable() {
            @Override public void run() {
                emitLog("Playing " + pcm.length + " bytes");
                AudioPlayer.Result result = audioPlayer.playBlocking(pcm);
                playbackActive.set(false);
                if (result.error != null) emitError("Playback: " + result.error);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (session.state() == SessionController.State.SPEAKING) {
                            session.onEvent(SessionController.Event.PLAYBACK_FINISHED);
                        } else {
                            // Already idle via other path; still report completion.
                            session.onEvent(SessionController.Event.PLAYBACK_FINISHED);
                        }
                    }
                });
            }
        }, "r1-audio-player");
        playerThread.start();
    }

    private void stopPlayback() {
        playbackActive.set(false);
        Thread t = playerThread;
        playerThread = null;
        if (t != null) t.interrupt();
    }

    private void ensureResponseCapacity(int needed) {
        if (responsePcm.length >= needed) return;
        int next = Math.max(needed, responsePcm.length * 2);
        responsePcm = Arrays.copyOf(responsePcm, next);
    }

    private void postEvent(final SessionController.Event event) {
        mainHandler.post(new Runnable() {
            @Override public void run() { session.onEvent(event); }
        });
    }

    private void emitConnection(final boolean c, final boolean r) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onConnectionChanged(c, r);
            }
        });
    }

    private void emitSession(final SessionController.State state) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onSessionState(state);
            }
        });
    }

    private void emitWake(final boolean listening) {
        final String name = wakeEngine == null ? "none" : wakeEngine.name();
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onWakeListening(listening, name);
            }
        });
    }

    private void emitError(final String message) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onError(message);
            }
        });
    }

    private void emitLog(final String line) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (callback != null) callback.onLog(line);
            }
        });
    }

    private Notification buildNotification(String status) {
        Intent intent = new Intent(this, SatelliteActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);
        Notification.Builder builder = new Notification.Builder(this)
                .setContentTitle("Voice Satellite")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true);
        return builder.getNotification();
    }

    private void updateNotification(String status) {
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(status));
        } catch (RuntimeException ignored) {}
    }
}
