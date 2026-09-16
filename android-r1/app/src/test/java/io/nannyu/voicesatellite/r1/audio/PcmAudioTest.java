package io.nannyu.voicesatellite.r1.audio;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class PcmAudioTest {
    @Test public void downmixesStereoPcm16WithoutOverflow() {
        byte[] stereo = pcm16(1000, 3000, 32767, 32767, -32768, -32768, -3000, 1000);
        byte[] mono = new byte[8];

        PcmAudio.downmixStereoToMono(stereo, mono);

        assertArrayEquals(pcm16(2000, 32767, -32768, -1000), mono);
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
