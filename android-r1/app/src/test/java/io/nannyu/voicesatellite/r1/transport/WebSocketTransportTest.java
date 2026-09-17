package io.nannyu.voicesatellite.r1.transport;

import java.util.ArrayList;
import java.util.List;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebSocketTransportTest {
    private static final class Socket implements WebSocket {
        final Request request;
        final WebSocketListener listener;
        boolean cancelled;
        int closeCalls;
        Socket(Request request, WebSocketListener listener) { this.request = request; this.listener = listener; }
        @Override public Request request() { return request; }
        @Override public long queueSize() { return 0; }
        @Override public boolean send(String text) { return !cancelled; }
        @Override public boolean send(ByteString bytes) { return !cancelled; }
        @Override public boolean close(int code, String reason) { closeCalls++; return true; }
        @Override public void cancel() { cancelled = true; }
    }
    private static final class Factory implements WebSocket.Factory {
        final List<Socket> sockets = new ArrayList<>();
        @Override public WebSocket newWebSocket(Request request, WebSocketListener listener) {
            Socket socket = new Socket(request, listener);
            sockets.add(socket);
            return socket;
        }
    }
    private static final class Events implements SocketTransport.Listener {
        int opens, texts, frames, terminals;
        @Override public void onOpen() { opens++; }
        @Override public void onText(String text) { texts++; }
        @Override public void onBinary(byte[] data) { frames++; }
        @Override public void onClosed(int code, String reason) { terminals++; }
        @Override public void onFailure(Throwable failure) { terminals++; }
    }
    @Test public void replacedSocketCannotDeliverAnyCallbacks() {
        Factory factory = new Factory(); Events events = new Events();
        WebSocketTransport transport = new WebSocketTransport(factory);
        transport.connect("ws://localhost/one", events);
        Socket old = factory.sockets.get(0);
        transport.connect("ws://localhost/two", events);
        Socket current = factory.sockets.get(1);
        assertTrue(old.cancelled);
        old.listener.onOpen(old, null);
        old.listener.onMessage(old, "stale");
        old.listener.onMessage(old, ByteString.of(new byte[] {1}));
        old.listener.onClosing(old, 1000, "old");
        old.listener.onClosed(old, 1000, "old");
        old.listener.onFailure(old, new Exception("old"), null);
        assertEquals(0, events.opens + events.texts + events.frames + events.terminals);
        assertEquals(0, old.closeCalls);
        current.listener.onOpen(current, null);
        current.listener.onMessage(current, "current");
        current.listener.onMessage(current, ByteString.of(new byte[] {2}));
        assertEquals(1, events.opens); assertEquals(1, events.texts); assertEquals(1, events.frames);
        assertTrue(transport.sendText("still connected"));
    }
    @Test public void terminalEventIsDeliveredOnlyOnce() {
        Factory factory = new Factory(); Events events = new Events();
        WebSocketTransport transport = new WebSocketTransport(factory);
        transport.connect("ws://localhost/", events);
        Socket socket = factory.sockets.get(0);
        socket.listener.onFailure(socket, new Exception(), null);
        socket.listener.onClosed(socket, 1000, "late");
        socket.listener.onFailure(socket, new Exception(), null);
        assertEquals(1, events.terminals);
        assertFalse(transport.sendText("closed"));
    }
    @Test public void peerCloseIsAcknowledged() {
        Factory factory = new Factory(); Events events = new Events();
        WebSocketTransport transport = new WebSocketTransport(factory);
        transport.connect("ws://localhost/", events);
        Socket socket = factory.sockets.get(0);
        socket.listener.onClosing(socket, 1000, "bye");
        assertEquals(1, socket.closeCalls);
        socket.listener.onClosed(socket, 1000, "bye");
        assertEquals(1, events.terminals);
    }
    @Test public void shutdownInvalidatesCallbacksAndIsIdempotent() {
        Factory factory = new Factory(); Events events = new Events();
        WebSocketTransport transport = new WebSocketTransport(factory);
        transport.connect("ws://localhost/", events);
        Socket socket = factory.sockets.get(0);
        transport.shutdown(); transport.shutdown();
        socket.listener.onFailure(socket, new Exception(), null);
        assertEquals(0, events.terminals);
        assertTrue(socket.cancelled);
        try { transport.connect("ws://localhost/", events); fail("shutdown must be terminal"); }
        catch (IllegalStateException expected) { }
    }
}
