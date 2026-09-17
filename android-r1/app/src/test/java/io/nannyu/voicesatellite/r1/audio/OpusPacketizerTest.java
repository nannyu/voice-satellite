package io.nannyu.voicesatellite.r1.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class OpusPacketizerTest {
    @Test public void twoThirtyMsChunksBecomeOneSixtyMsPacket() {
        OpusPacketizer p = new OpusPacketizer(frame -> frame, 0);
        byte[] first = new byte[960], second = new byte[960];
        Arrays.fill(first, (byte) 1); Arrays.fill(second, (byte) 2);
        assertTrue(p.push(first).isEmpty());
        List<byte[]> packets = p.push(second);
        assertEquals(1, packets.size());
        assertArrayEquals(first, Arrays.copyOfRange(packets.get(0), 0, 960));
        assertArrayEquals(second, Arrays.copyOfRange(packets.get(0), 960, 1920));
        assertTrue(p.finish().isEmpty());
        assertEquals(960, p.inputSamples());
    }
    @Test public void partialTailIsPaddedOnce() {
        OpusPacketizer p = new OpusPacketizer(frame -> frame, 0);
        byte[] pcm = new byte[960]; Arrays.fill(pcm, (byte) 7);
        p.push(pcm);
        byte[] packet = p.finish().get(0);
        assertArrayEquals(pcm, Arrays.copyOfRange(packet, 0, 960));
        assertArrayEquals(new byte[960], Arrays.copyOfRange(packet, 960, 1920));
        assertEquals(480, p.inputSamples());
    }
    @Test public void lookaheadFlushesDelayedTailEvenAtExactBoundary() {
        OpusPacketizer p = new OpusPacketizer(frame -> frame, 104);
        assertEquals(1, p.push(new byte[1920]).size());
        assertEquals(1, p.finish().size());
        assertEquals(960, p.inputSamples());
    }
    @Test public void oddNumberOfCaptureFramesDoesNotDropTail() {
        OpusPacketizer p = new OpusPacketizer(frame -> frame, 104);
        List<byte[]> packets = new ArrayList<>();
        for (int i = 0; i < 35; i++) packets.addAll(p.push(new byte[960]));
        packets.addAll(p.finish());
        assertEquals(18, packets.size());
        assertEquals(16800, p.inputSamples());
    }
    @Test public void arbitraryEvenChunkBoundariesWork() {
        OpusPacketizer p = new OpusPacketizer(frame -> frame, 0);
        assertTrue(p.push(new byte[22]).isEmpty());
        assertEquals(2, p.push(new byte[3818]).size());
        assertTrue(p.finish().isEmpty());
    }
    @Test public void emptyUtteranceDoesNotEmitLookaheadOnlyPackets() {
        OpusPacketizer p = new OpusPacketizer(frame -> frame, 104);
        assertTrue(p.finish().isEmpty());
        assertEquals(0, p.inputSamples());
    }
    @Test(expected=IllegalArgumentException.class) public void oddPcmIsRejected() {
        new OpusPacketizer(frame -> frame, 0).push(new byte[3]);
    }
    @Test(expected=IllegalStateException.class) public void cannotPushAfterFinish() {
        OpusPacketizer p = new OpusPacketizer(frame -> frame, 0); p.finish(); p.push(new byte[960]);
    }
    @Test(expected=IllegalStateException.class) public void cannotFinishTwice() {
        OpusPacketizer p = new OpusPacketizer(frame -> frame, 0); p.finish(); p.finish();
    }
    @Test(expected=IllegalStateException.class) public void nullEncoderResultFails() {
        new OpusPacketizer(frame -> null, 0).push(new byte[1920]);
    }
    @Test(expected=IllegalStateException.class) public void oversizedEncoderResultFails() {
        new OpusPacketizer(frame -> new byte[4001], 0).push(new byte[1920]);
    }
    @Test(expected=IllegalArgumentException.class) public void lookaheadIsBounded() {
        new OpusPacketizer(frame -> frame, 961);
    }
}
