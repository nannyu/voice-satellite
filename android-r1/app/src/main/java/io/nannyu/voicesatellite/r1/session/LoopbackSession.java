package io.nannyu.voicesatellite.r1.session;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONObject;
import io.nannyu.voicesatellite.r1.protocol.Protocol;

/** Button-triggered PCM diagnostic loop, not an HA adapter. All methods use one event queue. */
public final class LoopbackSession implements AutoCloseable {
    public interface Link {
        boolean send(JSONObject message);
        boolean audio(byte[] pcm);
        void reset();
    }
    public interface Capture {
        boolean start(String turnId);
        void stop();
    }
    public interface Playback {
        void play(String turnId, byte[] pcm);
        void stop();
    }
    public interface Status { void update(String message); }
    private static final int MAX_RESPONSE_BYTES = 16000 * 2 * 30;
    private final Link link;
    private final Capture capture;
    private final Playback playback;
    private final Status status;
    private final SessionController.Scheduler scheduler;
    private final SessionController state;
    private final String deviceId;
    private final ByteArrayOutputStream response = new ByteArrayOutputStream();
    private String turnId;
    private boolean ready, closed, receiving, playing;
    private Object deadline;

    public LoopbackSession(String deviceId, Link link, Capture capture, Playback playback,
            Status status, SessionController.Scheduler scheduler) {
        this.deviceId = deviceId;
        this.link = link;
        this.capture = capture;
        this.playback = playback;
        this.status = status;
        this.scheduler = scheduler;
        state = new SessionController(new SessionController.Listener() {
            @Override public void onTransition(SessionController.State from, SessionController.State to,
                    SessionController.Event event) {
                status.update(to.name());
                if (event == SessionController.Event.LISTEN_TIMEOUT
                        || event == SessionController.Event.RESPONSE_TIMEOUT) fail("session timeout");
            }
            @Override public void onIllegalTransition(SessionController.State from, SessionController.Event event) {
                status.update("Ignored " + event + " in " + from);
            }
        }, scheduler, 5000, 15000);
    }
    public SessionController.State state() { return state.state(); }
    public String turnId() { return turnId; }
    public boolean isReady() { return ready && !closed; }
    public void connected() {
        if (closed) return;
        ready = false;
        if (!link.send(Protocol.hello(deviceId, "Phicomm R1", "0.2.0-dev", "android-22",
                Arrays.asList("pcm_s16le", "button", "audio_playback")))) fail("hello send failed");
    }
    public void trigger() {
        if (!isReady() || state.state() != SessionController.State.IDLE) {
            status.update("Not ready for a new utterance");
            return;
        }
        turnId = UUID.randomUUID().toString();
        response.reset();
        receiving = playing = false;
        state.onEvent(SessionController.Event.BUTTON_PRESSED);
        if (!link.send(Protocol.voiceStart(turnId, Protocol.TRIGGER_BUTTON))) {
            fail("voice.start send failed");
            return;
        }
        if (!capture.start(turnId)) { fail("microphone start failed"); return; }
        arm(15000, "maximum capture duration reached");
    }
    public void speechStarted(String id) {
        if (matches(id) && state.state() == SessionController.State.LISTENING)
            state.onEvent(SessionController.Event.SPEECH_STARTED);
    }
    public void captured(String id, byte[] pcm) {
        if (!matches(id) || state.state() != SessionController.State.LISTENING) return;
        if (!link.audio(pcm)) fail("audio send failed");
    }
    public void speechEnded(String id) {
        if (!matches(id) || state.state() != SessionController.State.LISTENING) return;
        capture.stop();
        cancelDeadline();
        state.onEvent(SessionController.Event.SPEECH_ENDED);
        if (!link.send(Protocol.voiceEnd(id, Protocol.REASON_VAD_END))) {
            fail("voice.end send failed");
            return;
        }
        // This deadline survives response.start, which changes the state to SPEAKING.
        arm(15000, "incomplete response");
    }
    public void captureFailed(String id, String message) { if (matches(id)) fail(message); }
    public void message(Protocol.Message message) {
        if (closed) return;
        try {
            switch (message.type) {
                case Protocol.TYPE_HELLO_ACK:
                    Protocol.HelloAck ack = Protocol.helloAck(message);
                    if (ack.protocol != Protocol.VERSION || ack.sessionId.trim().isEmpty()) {
                        fail("invalid hello.ack"); return;
                    }
                    ready = true;
                    status.update("Ready: PCM loopback");
                    break;
                case Protocol.TYPE_RESPONSE_START:
                    if (!matches(message.body.optString("session_id", null))
                            || state.state() != SessionController.State.PROCESSING) return;
                    receiving = true;
                    response.reset();
                    state.onEvent(SessionController.Event.SERVER_RESPONDING);
                    break;
                case Protocol.TYPE_RESPONSE_END:
                    if (!matches(message.body.optString("session_id", null)) || !receiving) return;
                    receiving = false;
                    playing = true;
                    byte[] pcm = response.toByteArray();
                    response.reset();
                    arm(pcm.length * 1000L / 32000 + 5000, "playback timeout");
                    if (pcm.length == 0) playbackFinished(turnId, null);
                    else playback.play(turnId, pcm);
                    break;
                case Protocol.TYPE_STATE:
                    // The reference server sends idle immediately after response.end.
                    // Only local playback completion may leave SPEAKING.
                    if ("processing".equals(Protocol.stateValue(message))
                            && state.state() == SessionController.State.PROCESSING)
                        state.onEvent(SessionController.Event.SERVER_PROCESSING);
                    break;
                case Protocol.TYPE_ERROR:
                    fail(Protocol.errorValue(message).message);
                    break;
                default: break;
            }
        } catch (Protocol.ProtocolException ignored) { /* Keep the existing deadline. */ }
    }
    public void responseAudio(byte[] pcm) {
        if (closed || !receiving) return;
        if ((pcm.length & 1) != 0 || pcm.length > MAX_RESPONSE_BYTES - response.size()) {
            fail("invalid or oversized PCM response");
            return;
        }
        response.write(pcm, 0, pcm.length);
    }
    public void playbackFinished(String id, String error) {
        if (!matches(id) || !playing) return;
        if (error != null) { fail(error); return; }
        playing = false;
        cancelDeadline();
        turnId = null;
        state.onEvent(SessionController.Event.PLAYBACK_FINISHED);
    }
    public void disconnected() {
        if (closed) return;
        ready = false;
        resetLocal();
        status.update("Disconnected; waiting for handshake");
    }
    public void cancel() { if (!closed) fail("cancelled"); }
    private boolean matches(String id) { return !closed && turnId != null && turnId.equals(id); }
    private void arm(long delayMs, String message) {
        cancelDeadline();
        final String id = turnId;
        deadline = scheduler.schedule(delayMs, () -> { if (matches(id)) fail(message); });
    }
    private void cancelDeadline() {
        if (deadline != null) scheduler.cancel(deadline);
        deadline = null;
    }
    private void resetLocal() {
        turnId = null; // Invalidate queued capture/playback callbacks before touching resources.
        receiving = playing = false;
        cancelDeadline();
        response.reset();
        capture.stop();
        playback.stop();
        if (state.state() != SessionController.State.IDLE)
            state.onEvent(SessionController.Event.NETWORK_LOST);
    }
    private void fail(String message) {
        ready = false;
        resetLocal();
        status.update("Loopback stopped: " + message);
        link.reset(); // Drop incomplete voice state on both sides before the next turn.
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        ready = false;
        resetLocal();
    }
}
