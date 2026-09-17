package io.nannyu.voicesatellite.r1.session;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;
import io.nannyu.voicesatellite.r1.audio.AudioPlayer;
import io.nannyu.voicesatellite.r1.audio.AudioRecorder;
import io.nannyu.voicesatellite.r1.audio.OpusTurnCodec;
import io.nannyu.voicesatellite.r1.protocol.Protocol;
import io.nannyu.voicesatellite.r1.transport.ConnectionSupervisor;
import io.nannyu.voicesatellite.r1.transport.WebSocketTransport;
import io.nannyu.voicesatellite.r1.util.TaskQueue;

/** Android audio adapters. Upload and download use Opus unless PCM diagnostics are explicitly selected. */
public final class AndroidLoopback implements AutoCloseable {
    private final TaskQueue.Serial events = new TaskQueue.Serial();
    private final ExecutorService audioOutput = Executors.newSingleThreadExecutor();
    private final AudioRecorder recorder = new AudioRecorder();
    private final AudioPlayer player = new AudioPlayer();
    private final WebSocketTransport transport = new WebSocketTransport();
    private final ConnectionSupervisor connection;
    private final LoopbackSession session;
    private volatile boolean closed;
    private volatile long playbackGeneration;
    private boolean once;

    public AndroidLoopback(String deviceId, LoopbackSession.Status status) {
        this(deviceId, status, true, "");
    }
    public AndroidLoopback(String deviceId, LoopbackSession.Status status, boolean opus, String token) {
        session = new LoopbackSession(deviceId, new LoopbackSession.Link() {
            @Override public boolean send(JSONObject message) { return connection.send(message); }
            @Override public boolean audio(byte[] frame) { return connection.sendAudio(frame); }
            @Override public void reset() { connection.reconnect(); }
            @Override public void reject() { connection.stop(); }
        }, new LoopbackSession.Capture() {
            @Override public boolean start(final String id) {
                recorder.setListener(new AudioRecorder.Listener() {
                    @Override public void onSpeechStart() { dispatch(() -> session.speechStarted(id)); }
                    @Override public void onAudioFrame(byte[] pcm) { dispatch(() -> session.captured(id, pcm)); }
                    @Override public void onSpeechEnd() { dispatch(() -> session.speechEnded(id)); }
                    @Override public void onError(String message) { dispatch(() -> session.captureFailed(id, message)); }
                });
                return recorder.start();
            }
            @Override public void stop() { recorder.close(); }
        }, new LoopbackSession.Playback() {
            @Override public void play(String id, byte[] pcm) {
                final long ticket = ++playbackGeneration;
                audioOutput.execute(() -> {
                    if (closed || ticket != playbackGeneration) return;
                    AudioPlayer.Result result = player.playBlocking(pcm, () -> closed || ticket != playbackGeneration);
                    dispatch(() -> { if (ticket == playbackGeneration)
                        session.playbackFinished(id, result.played ? null : result.error); });
                });
            }
            @Override public void stop() { playbackGeneration++; player.cancel(); }
        }, message -> { if (!closed) status.update(message); }, events,
                opus ? OpusTurnCodec::new : null, token);
        connection = new ConnectionSupervisor(transport, new ConnectionSupervisor.Listener() {
            @Override public void onConnected() { dispatch(session::connected); }
            @Override public void onMessage(Protocol.Message message) { dispatch(() -> {
                session.message(message);
                if (once && session.isReady()) { once = false; session.trigger(); }
            }); }
            @Override public void onAudio(byte[] frame) { dispatch(() -> session.responseAudio(frame)); }
            @Override public void onDisconnected(boolean retry) { dispatch(session::disconnected); }
        }, 500, 30000, 0.2, 10000);
    }
    private void dispatch(Runnable task) { events.execute(() -> { if (!closed) task.run(); }); }
    public void connect(String endpoint, boolean oneShot) { once = oneShot; connection.start(endpoint); }
    public void trigger() { dispatch(session::trigger); }
    public void cancel() { dispatch(session::cancel); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        connection.stop();
        events.execute(() -> {
            session.close(); transport.shutdown(); audioOutput.shutdown(); events.shutdown();
        });
    }
}
