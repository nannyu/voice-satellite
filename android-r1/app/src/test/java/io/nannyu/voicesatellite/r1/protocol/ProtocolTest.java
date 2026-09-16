package io.nannyu.voicesatellite.r1.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;

import org.json.JSONObject;
import org.junit.Test;

public final class ProtocolTest {
    @Test public void helloMatchesDraftShape() throws Exception {
        JSONObject hello = Protocol.hello("r1-living-room", "phicomm-r1", "0.2.0-dev", "android-5.1",
                Arrays.asList("audio.pcm16", "wake.local"));

        assertEquals("hello", hello.getString("type"));
        assertEquals(1, hello.getInt("protocol"));
        assertEquals("r1-living-room", hello.getString("device_id"));
        assertEquals("phicomm-r1", hello.getJSONObject("device").getString("model"));
        assertEquals("0.2.0-dev", hello.getJSONObject("device").getString("client_version"));
        assertEquals("android-5.1", hello.getJSONObject("device").getString("platform"));
        assertEquals("wake.local", hello.getJSONArray("capabilities").getString(1));
    }

    @Test public void voiceStartCarriesFixedPcm16Format() throws Exception {
        JSONObject start = Protocol.voiceStart("uuid-1", Protocol.TRIGGER_WAKE_WORD);

        assertEquals("voice.start", start.getString("type"));
        assertEquals("uuid-1", start.getString("session_id"));
        assertEquals("wake_word", start.getString("trigger"));
        JSONObject audio = start.getJSONObject("audio");
        assertEquals("pcm_s16le", audio.getString("codec"));
        assertEquals(16000, audio.getInt("sample_rate"));
        assertEquals(1, audio.getInt("channels"));
    }

    @Test public void voiceEndAndErrorRoundTrip() throws Exception {
        JSONObject end = Protocol.voiceEnd("uuid-1", Protocol.REASON_VAD_END);
        assertEquals("voice.end", end.getString("type"));
        assertEquals("vad_end", end.getString("reason"));

        Protocol.Message parsed = Protocol.parse(Protocol.error("AUDIO_CAPTURE_FAILED", "boom", true).toString());
        Protocol.ErrorMessage error = Protocol.errorValue(parsed);
        assertEquals("AUDIO_CAPTURE_FAILED", error.code);
        assertEquals("boom", error.message);
        assertTrue(error.recoverable);
    }

    @Test public void parsesHelloAck() throws Exception {
        Protocol.Message message = Protocol.parse(
                "{\"type\":\"hello.ack\",\"protocol\":1,\"session_id\":\"s-1\",\"heartbeat_seconds\":30}");

        Protocol.HelloAck ack = Protocol.helloAck(message);
        assertEquals(1, ack.protocol);
        assertEquals("s-1", ack.sessionId);
        assertEquals(30, ack.heartbeatSeconds);
    }

    @Test public void helloAckRequiresAllHandshakeFields() throws Exception {
        assertProtocolError("{\"type\":\"hello.ack\",\"session_id\":\"s-1\",\"heartbeat_seconds\":30}");
        assertProtocolError("{\"type\":\"hello.ack\",\"protocol\":1,\"heartbeat_seconds\":30}");
        assertProtocolError("{\"type\":\"hello.ack\",\"protocol\":1,\"session_id\":\"s-1\"}");
        assertProtocolError("{\"type\":\"hello.ack\",\"protocol\":1,\"session_id\":\"s-1\",\"heartbeat_seconds\":0}");
    }

    @Test public void parsesStateAndResponseTypes() throws Exception {
        Protocol.Message state = Protocol.parse("{\"type\":\"state\",\"state\":\"processing\"}");
        assertEquals("processing", Protocol.stateValue(state));
        assertTrue(Protocol.parse("{\"type\":\"response.start\"}").isKnown());
        assertTrue(Protocol.parse("{\"type\":\"response.end\"}").isKnown());
        assertTrue(Protocol.parse("{\"type\":\"pong\"}").isKnown());
    }

    @Test public void unknownTypeParsesButIsMarkedUnknown() throws Exception {
        Protocol.Message message = Protocol.parse("{\"type\":\"media.play\",\"request_id\":\"u\",\"media\":{}}");

        assertEquals("media.play", message.type);
        assertFalse(message.isKnown());
    }

    @Test public void malformedFramesAreRejected() throws Exception {
        assertProtocolError("not json");
        assertProtocolError("[1,2,3]");
        assertProtocolError("{\"state\":\"idle\"}");
        assertProtocolError("{\"type\":\"\"}");
        assertProtocolError(null);
    }

    @Test public void wrongTypedDecoderIsRejected() throws Exception {
        Protocol.Message state = Protocol.parse("{\"type\":\"state\",\"state\":\"idle\"}");
        try {
            Protocol.helloAck(state);
            fail("expected ProtocolException");
        } catch (Protocol.ProtocolException expected) {
            assertTrue(expected.getMessage().contains("hello.ack"));
        }
    }

    private static void assertProtocolError(String text) throws Exception {
        try {
            Protocol.Message message = Protocol.parse(text);
            Protocol.helloAck(message); // covers both parse-time and decode-time failures
            fail("expected ProtocolException for: " + text);
        } catch (Protocol.ProtocolException expected) {
        }
    }
}
