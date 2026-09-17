package io.nannyu.voicesatellite.r1.audio;

import java.util.List;
import io.nannyu.voicesatellite.r1.protocol.OpusFormat;

/** Production native codec; never silently falls back to PCM on the wire. */
public final class OpusTurnCodec implements TurnCodec {
    private final OpusEncoder encoder;
    private final OpusDecoder decoder;
    private final OpusPacketizer packetizer;
    private final int preSkip;
    public OpusTurnCodec() {
        encoder = new OpusEncoder(16000, 1, 60);
        try {
            preSkip = encoder.preSkipSamples();
            packetizer = new OpusPacketizer(encoder::encode, preSkip);
            decoder = new OpusDecoder(16000, 1, 60);
        } catch (RuntimeException | LinkageError failure) { encoder.close(); throw failure; }
    }
    @Override public int preSkipSamples() { return preSkip; }
    @Override public List<byte[]> encode(byte[] pcm) { return packetizer.push(pcm); }
    @Override public List<byte[]> finish() { return packetizer.finish(); }
    @Override public long inputSamples() { return packetizer.inputSamples(); }
    @Override public byte[] decode(byte[] packet) {
        byte[] pcm = decoder.decode(packet);
        if (pcm.length != OpusFormat.FRAME_SAMPLES * 2)
            throw new IllegalArgumentException("expected a 60 ms Opus packet");
        return pcm;
    }
    @Override public void close() { encoder.close(); decoder.close(); }
}
