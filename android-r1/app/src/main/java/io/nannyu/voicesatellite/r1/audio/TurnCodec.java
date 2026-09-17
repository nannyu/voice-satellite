package io.nannyu.voicesatellite.r1.audio;

import java.util.List;

/** One encoder and one decoder per utterance. All calls use the session event queue. */
public interface TurnCodec extends AutoCloseable {
    interface Factory { TurnCodec create(); }
    int preSkipSamples();
    List<byte[]> encode(byte[] pcm);
    List<byte[]> finish();
    long inputSamples();
    byte[] decode(byte[] packet);
    @Override void close();
}
