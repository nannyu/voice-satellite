package io.nannyu.voicesatellite.r1.transport;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/** OkHttp 3.12 transport. Callbacks must enqueue work, not block on another owner. */
public final class WebSocketTransport implements SocketTransport {
    private final WebSocket.Factory factory;
    private final OkHttpClient ownedClient;
    private WebSocket socket;
    private long generation;
    private boolean shutdown;

    public WebSocketTransport() { this(new OkHttpClient(), true); }
    WebSocketTransport(WebSocket.Factory factory) { this(factory, false); }
    private WebSocketTransport(WebSocket.Factory factory, boolean ownsClient) {
        this.factory = factory;
        this.ownedClient = ownsClient ? (OkHttpClient) factory : null;
    }

    @Override public synchronized void connect(String url, final Listener listener) {
        if (shutdown) throw new IllegalStateException("transport is shut down");
        final Request request = new Request.Builder().url(url).build();
        disconnect(1000, "replace");
        final long ticket = generation;
        socket = factory.newWebSocket(request, new WebSocketListener() {
            private boolean current(WebSocket ws) {
                return ticket == generation && ws == socket && !shutdown;
            }
            @Override public void onOpen(WebSocket ws, Response response) {
                synchronized (WebSocketTransport.this) {
                    if (current(ws)) listener.onOpen();
                }
            }
            @Override public void onMessage(WebSocket ws, String text) {
                synchronized (WebSocketTransport.this) {
                    if (current(ws)) listener.onText(text);
                }
            }
            @Override public void onMessage(WebSocket ws, ByteString bytes) {
                synchronized (WebSocketTransport.this) {
                    if (current(ws)) listener.onBinary(bytes.toByteArray());
                }
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) {
                synchronized (WebSocketTransport.this) {
                    if (current(ws)) ws.close(code, reason);
                }
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                synchronized (WebSocketTransport.this) {
                    if (!current(ws)) return;
                    socket = null;
                    generation++;
                    listener.onClosed(code, reason);
                }
            }
            @Override public void onFailure(WebSocket ws, Throwable failure, Response response) {
                synchronized (WebSocketTransport.this) {
                    if (!current(ws)) return;
                    socket = null;
                    generation++;
                    listener.onFailure(failure);
                }
            }
        });
    }
    @Override public synchronized boolean sendText(String text) {
        return socket != null && socket.send(text);
    }
    @Override public synchronized boolean sendBinary(byte[] frame) {
        return socket != null && socket.send(ByteString.of(frame));
    }
    @Override public synchronized void disconnect(int code, String reason) {
        WebSocket previous = socket;
        socket = null;
        generation++; // Invalidate before cancel(), which may cause a callback.
        if (previous != null) previous.cancel();
        // Intentional disconnects do not wait for a close handshake on a dead link.
    }
    @Override public synchronized void shutdown() {
        shutdown = true;
        disconnect(1000, "shutdown");
        if (ownedClient != null) {
            ownedClient.dispatcher().executorService().shutdown();
            ownedClient.connectionPool().evictAll();
        }
    }
}
