package io.nannyu.voicesatellite.r1.protocol;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class OpusFormatTest {
    @Test public void negotiatedFormatMatchesOnlySupportedProfile() throws Exception {
        assertTrue(OpusFormat.matches(OpusFormat.json()));
        assertFalse(OpusFormat.matches(null));
        assertFalse(OpusFormat.matches(OpusFormat.json().put("codec", "pcm_s16le")));
        assertFalse(OpusFormat.matches(OpusFormat.json().put("frame_ms", 30)));
    }
    @Test public void numbersAreNotCoercedFromStringsFloatsOrBooleans() throws Exception {
        assertFalse(OpusFormat.matches(OpusFormat.json().put("sample_rate", "16000")));
        assertFalse(OpusFormat.matches(OpusFormat.json().put("sample_rate", 16000.0)));
        assertFalse(OpusFormat.matches(OpusFormat.json().put("channels", true)));
    }
    @Test public void trimsPreskipAndTailPaddingExactly() {
        byte[] decoded = new byte[1920]; decoded[208] = 9;
        byte[] trimmed = OpusFormat.trim(decoded, 104, 480, 480);
        assertEquals(960, trimmed.length); assertEquals(9, trimmed[0]);
    }
    @Test(expected=IllegalArgumentException.class) public void missingSamplesFails() {
        OpusFormat.integer(new JSONObject(), "samples", 100);
    }
    @Test(expected=IllegalArgumentException.class) public void truncatedAudioFails() {
        OpusFormat.trim(new byte[1920], 104, 960, 1000);
    }
    @Test(expected=IllegalArgumentException.class) public void extraFullFrameFails() {
        OpusFormat.trim(new byte[3840], 0, 960, 1000);
    }
    @Test public void emptyResponseIsSupported() {
        assertEquals(0, OpusFormat.trim(new byte[0], 0, 0, 1000).length);
    }
}
