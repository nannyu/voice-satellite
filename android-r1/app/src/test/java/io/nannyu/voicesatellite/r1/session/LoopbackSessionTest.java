package io.nannyu.voicesatellite.r1.session;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import io.nannyu.voicesatellite.r1.protocol.Protocol;
import io.nannyu.voicesatellite.r1.util.ManualQueue;

public class LoopbackSessionTest {
    private static final class Ports implements LoopbackSession.Link, LoopbackSession.Capture,
            LoopbackSession.Playback, LoopbackSession.Status {
        final List<JSONObject> sent = new ArrayList<>();
        final List<byte[]> audio = new ArrayList<>();
        byte[] played;
        int resets;
        boolean accept = true;
        @Override public boolean send(JSONObject message) { sent.add(message); return accept; }
        @Override public boolean audio(byte[] pcm) { audio.add(pcm); return accept; }
        @Override public void reset() { resets++; }
        @Override public boolean start(String id) { return true; }
        @Override public void stop() { }
        @Override public void play(String id, byte[] pcm) { played = pcm; }
        @Override public void update(String message) { }
    }
    private static final class Fixture {
        final ManualQueue queue = new ManualQueue();
        final Ports ports = new Ports();
        final LoopbackSession session = new LoopbackSession("r1-test", ports, ports, ports, ports, queue);
        void message(String body) throws Exception { session.message(Protocol.parse(body)); }
        void ready() throws Exception {
            session.connected();
            message("{\"type\":\"hello.ack\",\"protocol\":1,\"session_id\":\"connection\",\"heartbeat_seconds\":30}");
        }
        String beginResponse() throws Exception {
            ready(); session.trigger(); String id = session.turnId();
            session.speechStarted(id); session.captured(id, new byte[960]); session.speechEnded(id);
            message("{\"type\":\"response.start\",\"session_id\":\"" + id + "\"}");
            return id;
        }
        void end(String id) throws Exception { message("{\"type\":\"response.end\",\"session_id\":\"" + id + "\"}"); }
    }
    @Test public void cannotCaptureBeforeHandshake() {
        Fixture f = new Fixture(); f.session.trigger();
        assertNull(f.session.turnId()); assertEquals(0, f.ports.sent.size());
    }
    @Test public void pcmRoundTripStaysSpeakingUntilLocalPlaybackCompletes() throws Exception {
        Fixture f = new Fixture(); String id = f.beginResponse();
        assertEquals("hello", f.ports.sent.get(0).getString("type"));
        assertEquals("pcm_s16le", f.ports.sent.get(1).getJSONObject("audio").getString("codec"));
        assertEquals("voice.end", f.ports.sent.get(2).getString("type"));
        byte[] pcm = new byte[] {1, 2, 3, 4};
        f.session.responseAudio(pcm); f.end(id);
        assertArrayEquals(pcm, f.ports.played);
        f.message("{\"type\":\"state\",\"state\":\"idle\"}");
        assertEquals(SessionController.State.SPEAKING, f.session.state());
        f.session.trigger(); assertEquals(id, f.session.turnId());
        f.session.playbackFinished(id, null);
        assertEquals(SessionController.State.IDLE, f.session.state()); assertTrue(f.session.isReady());
    }
    @Test public void lateCaptureAndPlaybackCallbacksCannotAffectANewTurn() throws Exception {
        Fixture f = new Fixture(); String old = f.beginResponse();
        f.end(old); // Empty response immediately completes playback.
        f.session.trigger(); String current = f.session.turnId(); assertFalse(old.equals(current));
        f.session.captured(old, new byte[960]); f.session.speechEnded(old);
        f.session.captureFailed(old, "late failure"); f.session.playbackFinished(old, null);
        assertEquals(current, f.session.turnId()); assertEquals(0, f.ports.resets);
        assertEquals(1, f.ports.audio.size());
    }
    @Test public void disconnectStopsTheTurnAndRequiresANewHandshake() throws Exception {
        Fixture f = new Fixture(); String id = f.beginResponse();
        f.session.disconnected(); f.session.responseAudio(new byte[960]); f.end(id);
        f.session.trigger(); assertNull(f.ports.played); assertNull(f.session.turnId());
        assertFalse(f.session.isReady()); assertEquals(SessionController.State.IDLE, f.session.state());
    }
    @Test public void missingResponseEndTimesOutEvenInSpeakingState() throws Exception {
        Fixture f = new Fixture(); f.beginResponse(); f.session.responseAudio(new byte[960]);
        f.queue.advance(15000);
        assertEquals(1, f.ports.resets); assertEquals(SessionController.State.IDLE, f.session.state());
    }
    @Test public void mismatchedResponseIsDropped() throws Exception {
        Fixture f = new Fixture(); f.ready(); f.session.trigger(); String id = f.session.turnId();
        f.session.speechEnded(id);
        f.message("{\"type\":\"response.start\",\"session_id\":\"stale\"}");
        f.session.responseAudio(new byte[960]); f.end("stale");
        assertNull(f.ports.played); assertEquals(SessionController.State.PROCESSING, f.session.state());
    }
    @Test public void oversizedResponseIsAborted() throws Exception {
        Fixture f = new Fixture(); f.beginResponse(); f.session.responseAudio(new byte[960002]);
        assertEquals(1, f.ports.resets); assertFalse(f.session.isReady());
    }
    @Test public void sendFailureStopsCaptureAndInvalidatesTheTurn() throws Exception {
        Fixture f = new Fixture(); f.ready(); f.session.trigger(); String id = f.session.turnId();
        f.ports.accept = false; f.session.captured(id, new byte[960]);
        assertEquals(1, f.ports.resets); assertNull(f.session.turnId());
    }
    @Test public void continuousSpeechHasAHardDeadline() throws Exception {
        Fixture f = new Fixture(); f.ready(); f.session.trigger();
        f.session.speechStarted(f.session.turnId()); f.queue.advance(15000);
        assertEquals(1, f.ports.resets); assertEquals(SessionController.State.IDLE, f.session.state());
    }
    @Test public void closeCancelsTimersAndIgnoresLateEvents() throws Exception {
        Fixture f = new Fixture(); String id = f.beginResponse();
        f.session.close(); f.session.close(); f.queue.advance(60000); f.end(id);
        f.session.playbackFinished(id, null); f.session.connected();
        assertEquals(0, f.ports.resets); assertNull(f.ports.played); assertFalse(f.session.isReady());
    }
}
