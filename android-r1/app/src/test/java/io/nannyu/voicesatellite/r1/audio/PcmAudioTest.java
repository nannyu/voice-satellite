package io.nannyu.voicesatellite.r1.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class PcmAudioTest {
    @Test public void downmixesStereoPcm16WithoutOverflow() {
        byte[] stereo = pcm16(1000, 3000, 32767, 32767, -32768, -32768, -3000, 1000);
        byte[] mono = new byte[8];

        PcmAudio.downmixStereoToMono(stereo, mono);

        assertArrayEquals(pcm16(2000, 32767, -32768, -1000), mono);
    }

    @Test public void sineToneProducesExactSampleCount() {
        assertEquals(16000 * 1500 / 1000 * 2, PcmAudio.sineTone(16000, 440, 1500, 0.5).length);
        assertEquals(16000 * 2, PcmAudio.sineTone(16000, 440, 1000, 1.0).length);
    }

    @Test public void sineToneNeverExceedsRequestedAmplitude() {
        assertPeakWithin(PcmAudio.sineTone(16000, 440, 1000, 0.5), (int) (0.5 * 32767));
        assertPeakWithin(PcmAudio.sineTone(16000, 440, 1000, 1.0), 32767);
    }

    @Test public void sineToneRejectsInvalidArguments() {
        assertRejected(-1, 440, 1000, 0.5);
        assertRejected(16000, 0, 1000, 0.5);
        assertRejected(16000, 440, 0, 0.5);
        assertRejected(16000, 440, 1000, -0.1);
        assertRejected(16000, 440, 1000, 1.1);
    }

    private static void assertPeakWithin(byte[] pcm, int limit) {
        int peak = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 255) | (pcm[i + 1] << 8));
            peak = Math.max(peak, Math.abs((int) sample));
        }
        assertTrue("peak " + peak + " exceeds " + limit, peak <= limit);
        assertTrue("tone is unexpectedly silent", peak > limit / 2);
    }

    private static void assertRejected(int sampleRate, double frequencyHz, int durationMs, double amplitude) {
        try {
            PcmAudio.sineTone(sampleRate, frequencyHz, durationMs, amplitude);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static byte[] pcm16(int... samples) {
        byte[] output = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            output[i * 2] = (byte) (samples[i] & 255);
            output[i * 2 + 1] = (byte) ((samples[i] >> 8) & 255);
        }
        return output;
    }
}
