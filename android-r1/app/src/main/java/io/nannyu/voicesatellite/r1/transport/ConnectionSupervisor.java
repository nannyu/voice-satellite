package io.nannyu.voicesatellite.r1.transport;

import java.util.Random;
import org.json.JSONObject;
import io.nannyu.voicesatellite.r1.protocol.Protocol;
import io.nannyu.voicesatellite.r1.util.TaskQueue;

/** One-shot supervisor. Every socket event is serialized and scoped to an attempt. */
public final class ConnectionSupervisor {
    public interface Listener {
        void onConnected();
        void onMessage(Protocol.Message message);
        void onAudio(byte[] frame);
        void onDisconnected(boolean willReconnect);
    }
    private final SocketTransport transport;
    private final Listener listener;
    private final ReconnectPolicy reconnect;
    private final TaskQueue queue;
    private final HeartbeatMonitor.Clock clock;
    private final long timeoutMs;
    private HeartbeatMonitor heartbeat;
    private volatile String sessionId;
    private boolean started, running;
    private String url;
    private long generation;
    private Object retry, handshake, tick;

    public ConnectionSupervisor(SocketTransport transport, Listener listener,
            long baseMs, long maxMs, double jitter, long timeoutMs) {
        this(transport, listener, baseMs, maxMs, jitter, timeoutMs, new TaskQueue.Serial(),
                () -> System.nanoTime() / 1000000L, new Random());
    }
    ConnectionSupervisor(SocketTransport transport, Listener listener,
            long baseMs, long maxMs, double jitter, long timeoutMs,
            TaskQueue queue, HeartbeatMonitor.Clock clock, Random random) {
        if (transport == null || listener == null || queue == null || clock == null || timeoutMs <= 0)
            throw new IllegalArgumentException("transport, listener, queue, clock and positive timeout required");
        this.transport = transport;
        this.listener = listener;
        this.queue = queue;
        this.clock = clock;
        this.timeoutMs = timeoutMs;
        reconnect = new ReconnectPolicy(baseMs, maxMs, jitter, random);
    }
    public synchronized void start(String endpoint) {
        if (started) throw new IllegalStateException("supervisor is one-shot; create a new instance");
        if (endpoint == null || !(endpoint.startsWith("ws://") || endpoint.startsWith("wss://")))
            throw new IllegalArgumentException("ws:// or wss:// endpoint required");
        started = running = true;
        url = endpoint;
        queue.execute(this::connect);
    }
    public synchronized void stop() {
        if (started && !running) return;
        started = true;
        running = false;
        generation++;
        clearAttempt();
        cancel(retry);
        retry = null;
        transport.disconnect(1000, "stop");
        queue.shutdown();
    }
    /** Force a fresh protocol session after a local timeout or cancellation. */
    public void reconnect() { queue.execute(() -> { synchronized (this) { lost(generation); } }); }
    public boolean send(JSONObject message) { return transport.sendText(message.toString()); }
    public boolean sendAudio(byte[] frame) { return transport.sendBinary(frame); }
    public String sessionId() { return sessionId; }

    private synchronized void connect() {
        retry = null;
        if (!running) return;
        final long ticket = ++generation;
        clearAttempt();
        handshake = queue.schedule(timeoutMs, () -> { synchronized (this) { lost(ticket); } });
        try {
            transport.connect(url, new SocketTransport.Listener() {
                private void dispatch(Runnable event) {
                    queue.execute(() -> { synchronized (ConnectionSupervisor.this) {
                        if (current(ticket)) event.run();
                    }});
                }
                @Override public void onOpen() { dispatch(listener::onConnected); }
                @Override public void onText(String text) { dispatch(() -> text(ticket, text)); }
                @Override public void onBinary(byte[] frame) { dispatch(() -> {
                    if (heartbeat == null) return; // No audio before the hello acknowledgement.
                    heartbeat.onInbound();
                    listener.onAudio(frame);
                }); }
                @Override public void onClosed(int code, String reason) { dispatch(() -> lost(ticket)); }
                @Override public void onFailure(Throwable failure) { dispatch(() -> lost(ticket)); }
            });
        } catch (RuntimeException failure) { lost(ticket); }
    }
    private void text(long ticket, String text) {
        final Protocol.Message message;
        try {
            message = Protocol.parse(text);
            if (Protocol.TYPE_HELLO_ACK.equals(message.type)) {
                if (heartbeat != null) return;
                Protocol.HelloAck ack = Protocol.helloAck(message);
                if (ack.protocol != Protocol.VERSION || ack.sessionId.trim().isEmpty()) {
                    lost(ticket);
                    return;
                }
                sessionId = ack.sessionId;
                heartbeat = new HeartbeatMonitor(ack.heartbeatSeconds * 1000L, timeoutMs, clock);
                cancel(handshake);
                handshake = null;
                reconnect.reset(); // A TCP open alone is not a successful protocol session.
                scheduleTick(ticket);
            } else if (heartbeat != null) heartbeat.onInbound();
            listener.onMessage(message);
        } catch (Protocol.ProtocolException ignored) {
            // Malformed acknowledgements retain the handshake deadline.
        }
    }
    private void scheduleTick(long ticket) {
        tick = queue.schedule(1000, () -> { synchronized (this) {
            if (!current(ticket) || heartbeat == null) return;
            switch (heartbeat.check()) {
                case HEARTBEAT_DUE:
                    if (!transport.sendText(Protocol.ping(sessionId).toString())) { lost(ticket); return; }
                    heartbeat.onHeartbeatSent();
                    break;
                case DEAD: lost(ticket); return;
                default: break;
            }
            scheduleTick(ticket);
        }});
    }
    private boolean current(long ticket) { return running && ticket == generation; }
    private void lost(long ticket) {
        if (!current(ticket) || retry != null) return;
        generation++; // Failure/closed/timeout from this attempt now become no-ops.
        clearAttempt();
        transport.disconnect(1000, "reconnect");
        retry = queue.schedule(reconnect.nextDelayMs(), this::connect);
        listener.onDisconnected(true);
    }
    private void clearAttempt() {
        heartbeat = null;
        sessionId = null;
        cancel(handshake);
        cancel(tick);
        handshake = tick = null;
    }
    private void cancel(Object task) { if (task != null) queue.cancel(task); }
}
