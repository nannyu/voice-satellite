package io.nannyu.voicesatellite.r1.session;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import io.nannyu.voicesatellite.r1.audio.TurnCodec;
import io.nannyu.voicesatellite.r1.protocol.OpusFormat;
import io.nannyu.voicesatellite.r1.protocol.Protocol;

/** Half-duplex audio MVP. Optional Opus factory; the original constructor retains PCM diagnostics. */
public final class LoopbackSession implements AutoCloseable {
    public interface Link {
        boolean send(JSONObject message);
        boolean audio(byte[] packet);
        void reset();
        /** Fatal negotiation/auth/native failure: do not reconnect until explicitly requested. */
        default void reject() { reset(); }
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
    private final String deviceId, token;
    private final TurnCodec.Factory codecFactory;
    private final ByteArrayOutputStream response = new ByteArrayOutputStream();
    private TurnCodec codec;
    private String turnId;
    private boolean ready, closed, receiving, playing;
    private int responsePreSkip;
    private Object deadline;

    public LoopbackSession(String deviceId, Link link, Capture capture, Playback playback,
            Status status, SessionController.Scheduler scheduler) {
        this(deviceId, link, capture, playback, status, scheduler, null, "");
    }
    public LoopbackSession(String deviceId, Link link, Capture capture, Playback playback,
            Status status, SessionController.Scheduler scheduler, TurnCodec.Factory codecFactory, String token) {
        this.deviceId = deviceId; this.link = link; this.capture = capture; this.playback = playback;
        this.status = status; this.scheduler = scheduler; this.codecFactory = codecFactory;
        this.token = token == null ? "" : token;
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
    private boolean opus() { return codecFactory != null; }
    public SessionController.State state() { return state.state(); }
    public String turnId() { return turnId; }
    public boolean isReady() { return ready && !closed; }
    public void connected() {
        if (closed) return;
        ready = false;
        JSONObject hello = Protocol.hello(deviceId, "Phicomm R1", "0.3.0-mvp", "android-22",
                Arrays.asList(opus() ? "opus" : "pcm_s16le", "button", "audio_playback"));
        if (opus()) OpusFormat.put(hello, "audio", OpusFormat.json());
        if (!token.isEmpty()) OpusFormat.put(hello, "token", token);
        if (!link.send(hello)) fail("hello send failed");
    }
    public void trigger() {
        if (!isReady() || state.state() != SessionController.State.IDLE) {
            status.update("Not ready for a new utterance"); return;
        }
        turnId = UUID.randomUUID().toString();
        response.reset(); receiving = playing = false;
        state.onEvent(SessionController.Event.BUTTON_PRESSED);
        JSONObject start = Protocol.voiceStart(turnId, Protocol.TRIGGER_BUTTON);
        if (opus()) {
            try {
                codec = codecFactory.create();
                if (codec == null) throw new IllegalStateException("codec factory returned null");
                OpusFormat.put(start, "audio", OpusFormat.json());
                OpusFormat.put(start, "pre_skip", codec.preSkipSamples());
                OpusFormat.integer(start, "pre_skip", OpusFormat.FRAME_SAMPLES);
            } catch (RuntimeException | LinkageError failure) {
                reject("native Opus unavailable: " + failure.getMessage()); return;
            }
        }
        if (!link.send(start)) { fail("voice.start send failed"); return; }
        if (!capture.start(turnId)) { fail("microphone start failed"); return; }
        arm(15000, "maximum capture duration reached");
    }
    public void speechStarted(String id) {
        if (matches(id) && state.state() == SessionController.State.LISTENING)
            state.onEvent(SessionController.Event.SPEECH_STARTED);
    }
    public void captured(String id, byte[] pcm) {
        if (!matches(id) || state.state() != SessionController.State.LISTENING) return;
        try {
            if (opus()) {
                if (pcm == null || (pcm.length & 1) != 0
                        || codec.inputSamples() + pcm.length / 2 > OpusFormat.MAX_INPUT_SAMPLES)
                    throw new IllegalArgumentException("invalid or oversized capture");
                sendPackets(codec.encode(pcm));
            } else if (!link.audio(pcm)) fail("audio send failed");
        } catch (RuntimeException failure) { fail("audio encode failed: " + failure.getMessage()); }
    }
    private boolean sendPackets(List<byte[]> packets) {
        for (byte[] packet : packets) {
            if (packet == null || packet.length == 0 || packet.length > OpusFormat.MAX_PACKET_BYTES
                    || !link.audio(packet)) { fail("audio send failed"); return false; }
        }
        return true;
    }
    public void speechEnded(String id) {
        if (!matches(id) || state.state() != SessionController.State.LISTENING) return;
        capture.stop(); cancelDeadline();
        JSONObject end = Protocol.voiceEnd(id, Protocol.REASON_VAD_END);
        if (opus()) {
            try {
                if (codec.inputSamples() == 0) { fail("empty utterance"); return; }
                if (!sendPackets(codec.finish())) return;
                OpusFormat.put(end, "samples", codec.inputSamples());
            } catch (RuntimeException failure) { fail("audio flush failed: " + failure.getMessage()); return; }
        }
        state.onEvent(SessionController.Event.SPEECH_ENDED);
        if (!link.send(end)) { fail("voice.end send failed"); return; }
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
                    if (opus() && !OpusFormat.matches(message.body.optJSONObject("audio"))) {
                        reject("gateway did not negotiate Opus/16k/mono/60ms"); return;
                    }
                    ready = true;
                    status.update(opus() ? "Ready: Opus MVP" : "Ready: PCM loopback");
                    break;
                case Protocol.TYPE_RESPONSE_START:
                    if (!matches(message.body.optString("session_id", null))
                            || state.state() != SessionController.State.PROCESSING) return;
                    if (opus()) {
                        if (!OpusFormat.matches(message.body.optJSONObject("audio")))
                            throw new IllegalArgumentException("response audio format mismatch");
                        responsePreSkip = OpusFormat.integer(message.body, "pre_skip", OpusFormat.FRAME_SAMPLES);
                    }
                    receiving = true; response.reset();
                    state.onEvent(SessionController.Event.SERVER_RESPONDING);
                    break;
                case Protocol.TYPE_RESPONSE_END:
                    if (!matches(message.body.optString("session_id", null)) || !receiving) return;
                    byte[] pcm = response.toByteArray();
                    if (opus()) pcm = OpusFormat.trim(pcm, responsePreSkip,
                            OpusFormat.integer(message.body, "samples", OpusFormat.MAX_RESPONSE_SAMPLES),
                            OpusFormat.MAX_RESPONSE_SAMPLES);
                    receiving = false; playing = true; response.reset();
                    arm(pcm.length * 1000L / 32000 + 5000, "playback timeout");
                    if (pcm.length == 0) playbackFinished(turnId, null);
                    else playback.play(turnId, pcm);
                    break;
                case Protocol.TYPE_STATE:
                    // A server idle is not proof that the local AudioTrack has drained.
                    if ("processing".equals(Protocol.stateValue(message))
                            && state.state() == SessionController.State.PROCESSING)
                        state.onEvent(SessionController.Event.SERVER_PROCESSING);
                    break;
                case Protocol.TYPE_ERROR:
                    Protocol.ErrorMessage error = Protocol.errorValue(message);
                    if (opus() && !error.recoverable) reject(error.code + ": " + error.message);
                    else fail(error.message);
                    break;
                default: break;
            }
        } catch (Protocol.ProtocolException ignored) { /* Keep the existing deadline. */ }
        catch (RuntimeException failure) { fail("invalid audio response: " + failure.getMessage()); }
    }
    public void responseAudio(byte[] packet) {
        if (closed || !receiving) return;
        try {
            byte[] pcm = opus() ? codec.decode(packet) : packet;
            int limit = MAX_RESPONSE_BYTES + (opus() ? 4 * OpusFormat.FRAME_SAMPLES : 0);
            if (pcm == null || (pcm.length & 1) != 0 || pcm.length > limit - response.size()) {
                fail("invalid or oversized PCM response"); return;
            }
            response.write(pcm, 0, pcm.length);
        } catch (RuntimeException failure) { fail("audio decode failed: " + failure.getMessage()); }
    }
    public void playbackFinished(String id, String error) {
        if (!matches(id) || !playing) return;
        if (error != null) { fail(error); return; }
        playing = false; cancelDeadline(); turnId = null;
        closeCodec();
        state.onEvent(SessionController.Event.PLAYBACK_FINISHED);
    }
    public void disconnected() {
        if (closed) return;
        ready = false; resetLocal(); status.update("Disconnected; waiting for handshake");
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
    private void closeCodec() { if (codec != null) { codec.close(); codec = null; } }
    private void resetLocal() {
        turnId = null; // Invalidate queued callbacks before releasing per-turn resources.
        receiving = playing = false;
        cancelDeadline(); response.reset();
        capture.stop(); playback.stop(); closeCodec();
        if (state.state() != SessionController.State.IDLE) state.onEvent(SessionController.Event.NETWORK_LOST);
    }
    private void fail(String message) {
        ready = false; resetLocal(); status.update("Loopback stopped: " + message); link.reset();
    }
    private void reject(String message) {
        ready = false; resetLocal(); status.update("Connection rejected: " + message); link.reject();
    }
    @Override public void close() {
        if (closed) return;
        closed = true; ready = false; resetLocal();
    }
}
