package io.nannyu.voicesatellite.r1.session;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import io.nannyu.voicesatellite.r1.audio.OpusPacketizer;
import io.nannyu.voicesatellite.r1.audio.TurnCodec;
import io.nannyu.voicesatellite.r1.protocol.OpusFormat;
import io.nannyu.voicesatellite.r1.protocol.Protocol;
import io.nannyu.voicesatellite.r1.util.ManualQueue;

public class OpusSessionTest {
    static class Codec implements TurnCodec {
        final OpusPacketizer packetizer = new OpusPacketizer(frame -> frame, 0);
        int closes, encoded;
        boolean broken;
        public int preSkipSamples() { return 0; }
        public List<byte[]> encode(byte[] pcm) {
            if (broken) throw new IllegalStateException("test encoder failure");
            encoded++; return packetizer.push(pcm);
        }
        public List<byte[]> finish() { return packetizer.finish(); }
        public long inputSamples() { return packetizer.inputSamples(); }
        public byte[] decode(byte[] packet) {
            if (packet.length != 1920) throw new IllegalArgumentException("test invalid packet");
            return packet.clone();
        }
        public void close() { closes++; }
    }
    static class Fixture {
        final ManualQueue queue = new ManualQueue();
        final List<JSONObject> sent = new ArrayList<>();
        final List<byte[]> packets = new ArrayList<>();
        final List<Codec> codecs = new ArrayList<>();
        int resets, rejects, starts, stops, plays;
        boolean writable = true;
        byte[] played;
        final LoopbackSession session = new LoopbackSession("test", new LoopbackSession.Link() {
            public boolean send(JSONObject value) { sent.add(value); return true; }
            public boolean audio(byte[] packet) { packets.add(packet); return writable; }
            public void reset() { resets++; }
            public void reject() { rejects++; }
        }, new LoopbackSession.Capture() {
            public boolean start(String id) { starts++; return true; }
            public void stop() { stops++; }
        }, new LoopbackSession.Playback() {
            public void play(String id, byte[] pcm) { plays++; played = pcm; }
            public void stop() {}
        }, text -> {}, queue, () -> { Codec c = new Codec(); codecs.add(c); return c; }, "test-token");
        void message(JSONObject value) throws Exception { session.message(Protocol.parse(value.toString())); }
        void ready() throws Exception {
            session.connected();
            message(new JSONObject().put("type", "hello.ack").put("protocol", 1)
                    .put("session_id", "connection").put("heartbeat_seconds", 5).put("audio", OpusFormat.json()));
        }
        String capture() throws Exception {
            ready(); session.trigger(); String id = session.turnId(); session.speechStarted(id);
            session.captured(id, new byte[960]); session.captured(id, new byte[960]);
            session.speechEnded(id); return id;
        }
        void responseStart(String id) throws Exception {
            message(new JSONObject().put("type", "response.start").put("session_id", id)
                    .put("audio", OpusFormat.json()).put("pre_skip", 0));
        }
        void responseEnd(String id, int samples) throws Exception {
            message(new JSONObject().put("type", "response.end").put("session_id", id).put("samples", samples));
        }
    }
    @Test public void helloExplicitlyOffersOpusAndOptionalToken() throws Exception {
        Fixture f = new Fixture(); f.ready();
        assertTrue(OpusFormat.matches(f.sent.get(0).getJSONObject("audio")));
        assertEquals("test-token", f.sent.get(0).getString("token"));
        assertTrue(f.session.isReady());
    }
    @Test public void legacyAckIsRejectedWithoutMicrophoneOrRetry() throws Exception {
        Fixture f = new Fixture(); f.session.connected();
        f.message(new JSONObject().put("type", "hello.ack").put("protocol", 1)
                .put("session_id", "legacy").put("heartbeat_seconds", 5));
        f.session.trigger(); assertEquals(1, f.rejects); assertEquals(0, f.resets); assertEquals(0, f.starts);
    }
    @Test public void aggregatesThirtyMsAndFlushesBeforeVoiceEnd() throws Exception {
        Fixture f = new Fixture(); f.ready(); f.session.trigger(); String id = f.session.turnId();
        f.session.speechStarted(id); f.session.captured(id, new byte[960]); assertEquals(0, f.packets.size());
        f.session.captured(id, new byte[960]); assertEquals(1, f.packets.size());
        f.session.captured(id, new byte[960]); f.session.speechEnded(id);
        assertEquals(2, f.packets.size());
        assertEquals(1440, f.sent.get(f.sent.size()-1).getInt("samples"));
        assertEquals("voice.end", f.sent.get(f.sent.size()-1).getString("type"));
    }
    @Test public void decodedPcmReachesPlaybackAndWaitsForLocalDrain() throws Exception {
        Fixture f = new Fixture(); String id = f.capture(); f.responseStart(id);
        f.session.responseAudio(new byte[1920]); f.responseEnd(id, 960);
        assertEquals(1920, f.played.length); assertEquals(1, f.plays);
        f.message(new JSONObject().put("type", "state").put("state", "idle"));
        assertEquals(SessionController.State.SPEAKING, f.session.state());
        f.session.playbackFinished(id, null);
        assertEquals(SessionController.State.IDLE, f.session.state()); assertEquals(1, f.codecs.get(0).closes);
    }
    @Test public void mismatchedResponseFormatFailsBeforePlayback() throws Exception {
        Fixture f = new Fixture(); String id = f.capture();
        f.message(new JSONObject().put("type", "response.start").put("session_id", id)
                .put("audio", OpusFormat.json().put("frame_ms", 20)).put("pre_skip", 0));
        assertEquals(1, f.resets); assertEquals(0, f.plays);
    }
    @Test public void decoderFailureClosesCodecAndResets() throws Exception {
        Fixture f = new Fixture(); String id = f.capture(); f.responseStart(id);
        f.session.responseAudio(new byte[3]);
        assertEquals(1, f.resets); assertEquals(1, f.codecs.get(0).closes); assertEquals(0, f.plays);
    }
    @Test public void responseSamplesCannotOverrunDecodedPcm() throws Exception {
        Fixture f = new Fixture(); String id = f.capture(); f.responseStart(id);
        f.session.responseAudio(new byte[1920]); f.responseEnd(id, 961);
        assertEquals(1, f.resets); assertEquals(0, f.plays);
    }
    @Test public void silentResponseCompletesWithoutAudioPlayer() throws Exception {
        Fixture f = new Fixture(); String id = f.capture(); f.responseStart(id); f.responseEnd(id, 0);
        assertEquals(SessionController.State.IDLE, f.session.state()); assertEquals(0, f.plays);
        assertEquals(1, f.codecs.get(0).closes);
    }
    @Test public void missingResponseEndTimesOutAndReleasesCodec() throws Exception {
        Fixture f = new Fixture(); String id = f.capture(); f.responseStart(id); f.queue.advance(15000);
        assertEquals(1, f.resets); assertEquals(1, f.codecs.get(0).closes);
    }
    @Test public void disconnectInvalidatesCaptureAndClosesCodec() throws Exception {
        Fixture f = new Fixture(); f.ready(); f.session.trigger(); String id = f.session.turnId();
        f.session.disconnected(); f.session.captured(id, new byte[960]);
        assertEquals(0, f.packets.size()); assertEquals(1, f.codecs.get(0).closes);
    }
    @Test public void sendBackpressureAbortsWithoutVoiceEnd() throws Exception {
        Fixture f = new Fixture(); f.ready(); f.session.trigger(); String id = f.session.turnId();
        f.writable = false; f.session.captured(id, new byte[1920]); f.session.speechEnded(id);
        assertEquals(1, f.resets); assertEquals("voice.start", f.sent.get(f.sent.size()-1).getString("type"));
    }
    @Test public void encoderFailureCannotLeaveCaptureActive() throws Exception {
        Fixture f = new Fixture(); f.ready(); f.session.trigger(); f.codecs.get(0).broken = true;
        f.session.captured(f.session.turnId(), new byte[960]);
        assertEquals(1, f.resets); assertEquals(1, f.codecs.get(0).closes); assertTrue(f.stops > 0);
    }
    @Test public void newTurnGetsFreshCodecAndOldResponseIsIgnored() throws Exception {
        Fixture f = new Fixture(); String first = f.capture(); f.responseStart(first); f.responseEnd(first, 0);
        f.session.trigger(); String second = f.session.turnId();
        f.responseStart(first); f.session.captured(first, new byte[1920]);
        assertNotEquals(first, second); assertEquals(2, f.codecs.size());
        assertEquals(0, f.codecs.get(1).encoded); assertEquals(SessionController.State.LISTENING, f.session.state());
    }
    @Test public void nonrecoverableAuthErrorDoesNotReconnectLoop() throws Exception {
        Fixture f = new Fixture(); f.ready();
        f.message(new JSONObject().put("type", "error").put("code", "UNAUTHORIZED")
                .put("message", "invalid token").put("recoverable", false));
        assertEquals(1, f.rejects); assertEquals(0, f.resets); assertFalse(f.session.isReady());
    }
}
