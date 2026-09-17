package io.nannyu.voicesatellite.r1.protocol;

import java.util.Arrays;
import org.json.JSONException;
import org.json.JSONObject;

/** The explicitly negotiated MVP profile. One WebSocket binary message is one raw Opus packet. */
public final class OpusFormat {
    public static final int RATE = 16000, CHANNELS = 1, FRAME_MS = 60, FRAME_SAMPLES = 960;
    public static final int MAX_PACKET_BYTES = 4000, MAX_INPUT_SAMPLES = RATE * 15;
    public static final int MAX_RESPONSE_SAMPLES = RATE * 30;
    private OpusFormat() {}
    public static JSONObject json() {
        JSONObject value = new JSONObject();
        put(value, "codec", "opus"); put(value, "sample_rate", RATE);
        put(value, "channels", CHANNELS); put(value, "frame_ms", FRAME_MS);
        return value;
    }
    public static JSONObject put(JSONObject body, String name, Object value) {
        try { body.put(name, value); return body; }
        catch (JSONException failure) { throw new IllegalArgumentException(failure); }
    }
    public static boolean matches(JSONObject value) {
        if (value == null || !"opus".equals(value.opt("codec"))) return false;
        try {
            return integer(value, "sample_rate", RATE) == RATE
                    && integer(value, "channels", CHANNELS) == CHANNELS
                    && integer(value, "frame_ms", FRAME_MS) == FRAME_MS;
        } catch (IllegalArgumentException failure) { return false; }
    }
    public static int integer(JSONObject body, String field, int max) {
        Object value = body.opt(field);
        if (!(value instanceof Integer || value instanceof Long))
            throw new IllegalArgumentException("integer required: " + field);
        long number = ((Number) value).longValue();
        if (number < 0 || number > max) throw new IllegalArgumentException("out of range: " + field);
        return (int) number;
    }
    public static byte[] trim(byte[] decoded, int preSkip, int samples, int maxSamples) {
        if (decoded == null || (decoded.length & 1) != 0 || preSkip < 0 || preSkip > FRAME_SAMPLES
                || samples < 0 || samples > maxSamples)
            throw new IllegalArgumentException("invalid decoded audio metadata");
        if (samples == 0 && decoded.length == 0 && preSkip == 0) return decoded;
        int total = decoded.length / 2;
        long needed = (long) preSkip + samples;
        if (total < needed || total - needed >= FRAME_SAMPLES)
            throw new IllegalArgumentException("audio sample count does not match packets");
        return Arrays.copyOfRange(decoded, preSkip * 2, (int) needed * 2);
    }
}
