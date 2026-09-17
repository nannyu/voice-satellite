import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import io.nannyu.voicesatellite.r1.audio.OpusDecoder;
import io.nannyu.voicesatellite.r1.audio.OpusEncoder;
import io.nannyu.voicesatellite.r1.audio.OpusTurnCodec;
import io.nannyu.voicesatellite.r1.protocol.Protocol;
import io.nannyu.voicesatellite.r1.session.LoopbackSession;
import io.nannyu.voicesatellite.r1.transport.ConnectionSupervisor;
import io.nannyu.voicesatellite.r1.transport.WebSocketTransport;
import io.nannyu.voicesatellite.r1.util.TaskQueue;

/** Real Java/JNI + WebSocket + Python/libopus E2E. Only microphone and speaker are substituted. */
public final class OpusGatewayE2E {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static byte[] tone(int samples) {
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short sample = (short)(7000 * Math.sin(i * 2 * Math.PI * 440 / 16000));
            pcm[2*i] = (byte)sample; pcm[2*i+1] = (byte)(sample >> 8);
        }
        return pcm;
    }
    private static double energy(byte[] pcm) {
        double sum = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            short sample = (short)((pcm[i] & 255) | (pcm[i+1] << 8));
            sum += (double)sample * sample;
        }
        return sum / (pcm.length / 2);
    }
    private static double correlation(byte[] first, byte[] second) {
        double dot = 0, a2 = 0, b2 = 0;
        for (int i = 0; i < first.length; i += 2) {
            short a = (short)((first[i] & 255) | (first[i+1] << 8));
            short b = (short)((second[i] & 255) | (second[i+1] << 8));
            dot += (double)a*b; a2 += (double)a*a; b2 += (double)b*b;
        }
        return dot / Math.sqrt(a2*b2);
    }
    static final class Harness implements AutoCloseable {
        final TaskQueue.Serial events = new TaskQueue.Serial();
        final WebSocketTransport transport = new WebSocketTransport();
        final BlockingQueue<Boolean> ready = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> output = new LinkedBlockingQueue<>();
        final AtomicReference<String> failure = new AtomicReference<>();
        final byte[] input = tone(16800); // 35 recorder-sized 30 ms frames, exercising a partial tail.
        final ConnectionSupervisor connection;
        final LoopbackSession session;
        volatile boolean closed;
        int nativeCodecs;
        Harness(String url) {
            session = new LoopbackSession("host-e2e", new LoopbackSession.Link() {
                public boolean send(JSONObject value) { return connection.send(value); }
                public boolean audio(byte[] value) { return connection.sendAudio(value); }
                public void reset() { connection.reconnect(); }
                public void reject() { connection.stop(); }
            }, new LoopbackSession.Capture() {
                public boolean start(String id) {
                    events.execute(() -> {
                        session.speechStarted(id);
                        for (int offset = 0; offset < input.length; offset += 960)
                            session.captured(id, Arrays.copyOfRange(input, offset, offset + 960));
                        session.speechEnded(id);
                    });
                    return true;
                }
                public void stop() {}
            }, new LoopbackSession.Playback() {
                public void play(String id, byte[] pcm) {
                    session.playbackFinished(id, null);
                    output.add(pcm);
                }
                public void stop() {}
            }, message -> {
                if (message.startsWith("Loopback stopped") || message.startsWith("Connection rejected"))
                    failure.compareAndSet(null, message);
            }, events, () -> { nativeCodecs++; return new OpusTurnCodec(); }, "");
            connection = new ConnectionSupervisor(transport, new ConnectionSupervisor.Listener() {
                private void dispatch(Runnable action) { events.execute(() -> { if (!closed) action.run(); }); }
                public void onConnected() { dispatch(session::connected); }
                public void onMessage(Protocol.Message message) { dispatch(() -> {
                    session.message(message);
                    if (Protocol.TYPE_HELLO_ACK.equals(message.type) && session.isReady()) ready.add(true);
                }); }
                public void onAudio(byte[] packet) { dispatch(() -> session.responseAudio(packet)); }
                public void onDisconnected(boolean retry) { dispatch(session::disconnected); }
            }, 100, 1000, 0, 5000);
            connection.start(url);
        }
        void awaitReady() throws Exception {
            check(ready.poll(10, TimeUnit.SECONDS) != null, "handshake failed: " + failure.get());
        }
        byte[] turn() throws Exception {
            events.execute(session::trigger);
            byte[] pcm = output.poll(15, TimeUnit.SECONDS);
            check(pcm != null, "turn failed: " + failure.get());
            check(failure.get() == null, failure.get());
            return pcm;
        }
        @Override public void close() {
            closed = true; connection.stop();
            events.execute(() -> { session.close(); transport.shutdown(); events.shutdown(); });
        }
    }
    private static void nativeGuards() {
        for (int channels : new int[]{1, 2}) {
            OpusEncoder encoder = new OpusEncoder(16000, channels, 60);
            OpusDecoder decoder = new OpusDecoder(16000, channels, 60);
            byte[] pcm = new byte[1920 * channels];
            byte[] packet = encoder.encode(pcm);
            check(decoder.decode(packet).length == pcm.length, "JNI channel/frame length mismatch");
            check(encoder.preSkipSamples() >= 0, "missing encoder lookahead");
            encoder.close(); encoder.close(); decoder.close(); decoder.close();
            boolean rejected = false;
            try { encoder.encode(pcm); } catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "closed encoder accepted a frame");
        }
        try (OpusDecoder decoder = new OpusDecoder(16000, 1, 60)) {
            boolean rejected = false;
            try { decoder.decode(new byte[]{(byte)255}); }
            catch (IllegalStateException expected) { rejected = true; }
            check(rejected, "malformed Opus packet accepted");
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("gateway-url echo|tone required");
        nativeGuards();
        try (Harness harness = new Harness(args[0])) {
            harness.awaitReady();
            for (int turn = 0; turn < 3; turn++) {
                if (turn == 2) { harness.connection.reconnect(); harness.awaitReady(); }
                byte[] output = harness.turn();
                int expected = args[1].equals("tone") ? 32000 : harness.input.length;
                check(output.length == expected, "sample count mismatch: " + output.length);
                check(energy(output) > 100000, "decoded output has no tone energy");
                if (args[1].equals("echo"))
                    check(correlation(harness.input, output) > 0.85, "echo failed signal correlation check");
            }
            check(harness.nativeCodecs == 3, "codec state leaked across turns");
            System.out.println("E2E_OK mode=" + args[1] + " turns=3 reconnects=1 native_codec_instances=3");
        }
    }
}
