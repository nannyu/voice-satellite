package io.nannyu.voicesatellite.r1.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Aggregates arbitrary PCM16 chunks into 60 ms frames, including codec lookahead at EOS. */
public final class OpusPacketizer {
    public interface Encoder { byte[] encode(byte[] pcm); }
    public static final int FRAME_SAMPLES = 960;
    public static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private final Encoder encoder;
    private final int preSkip;
    private final byte[] pending = new byte[FRAME_BYTES];
    private int filled;
    private long samples;
    private boolean finished;

    public OpusPacketizer(Encoder encoder, int preSkip) {
        if (encoder == null || preSkip < 0 || preSkip > FRAME_SAMPLES)
            throw new IllegalArgumentException("encoder and bounded lookahead required");
        this.encoder = encoder;
        this.preSkip = preSkip;
    }
    public long inputSamples() { return samples; }
    public List<byte[]> push(byte[] pcm) {
        if (finished) throw new IllegalStateException("packetizer finished");
        if (pcm == null || (pcm.length & 1) != 0)
            throw new IllegalArgumentException("even PCM16 buffer required");
        samples += pcm.length / 2;
        List<byte[]> packets = new ArrayList<>();
        append(pcm, packets);
        return packets;
    }
    public List<byte[]> finish() {
        if (finished) throw new IllegalStateException("packetizer finished");
        finished = true;
        List<byte[]> packets = new ArrayList<>();
        if (samples == 0) return packets;
        // Encode the delayed tail as well as a partially filled 60 ms frame.
        append(new byte[preSkip * 2], packets);
        if (filled > 0) {
            Arrays.fill(pending, filled, pending.length, (byte) 0);
            emit(packets);
        }
        return packets;
    }
    private void append(byte[] pcm, List<byte[]> packets) {
        int offset = 0;
        while (offset < pcm.length) {
            int count = Math.min(pending.length - filled, pcm.length - offset);
            System.arraycopy(pcm, offset, pending, filled, count);
            filled += count;
            offset += count;
            if (filled == pending.length) emit(packets);
        }
    }
    private void emit(List<byte[]> packets) {
        byte[] packet = encoder.encode(Arrays.copyOf(pending, pending.length));
        if (packet == null || packet.length == 0 || packet.length > 4000)
            throw new IllegalStateException("Opus encoder produced an invalid packet");
        packets.add(packet);
        filled = 0;
    }
}
