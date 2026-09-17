package io.nannyu.voicesatellite.r1.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;
import io.nannyu.voicesatellite.r1.protocol.Protocol;
import io.nannyu.voicesatellite.r1.util.ManualQueue;

public class ConnectionSupervisorTest {
    private static final String ACK = "{\"type\":\"hello.ack\",\"protocol\":1,\"session_id\":\"s\",\"heartbeat_seconds\":1}";
    private static final class FakeTransport implements SocketTransport {
        final List<Listener> attempts = new ArrayList<>();
        final List<String> sent = new ArrayList<>();
        int disconnects;
        @Override public void connect(String url, Listener listener) { attempts.add(listener); }
        @Override public boolean sendText(String text) { sent.add(text); return true; }
        @Override public boolean sendBinary(byte[] frame) { return true; }
        @Override public void disconnect(int code, String reason) { disconnects++; }
        @Override public void shutdown() { }
    }
    private static final class Events implements ConnectionSupervisor.Listener {
        int opens, messages, frames, losses;
        @Override public void onConnected() { opens++; }
        @Override public void onMessage(Protocol.Message message) { messages++; }
        @Override public void onAudio(byte[] frame) { frames++; }
        @Override public void onDisconnected(boolean retry) { assertTrue(retry); losses++; }
    }
    private static final class Fixture {
        final ManualQueue queue = new ManualQueue();
        final FakeTransport transport = new FakeTransport();
        final Events events = new Events();
        final ConnectionSupervisor supervisor = new ConnectionSupervisor(transport, events,
                100, 800, 0, 1000, queue, queue::now, new Random(0));
        Fixture() { supervisor.start("ws://localhost/"); queue.runReady(); }
        SocketTransport.Listener socket() { return transport.attempts.get(transport.attempts.size() - 1); }
    }
    @Test public void failureAndCloseScheduleExactlyOneRetry() {
        Fixture f = new Fixture(); SocketTransport.Listener old = f.socket();
        old.onFailure(new Exception()); old.onClosed(1000, "late"); f.queue.runReady();
        assertEquals(1, f.events.losses);
        f.queue.advance(100);
        assertEquals(2, f.transport.attempts.size());
        old.onText(ACK); old.onBinary(new byte[2]); old.onFailure(new Exception()); f.queue.runReady();
        assertNull(f.supervisor.sessionId()); assertEquals(0, f.events.frames);
        assertEquals(1, f.events.losses);
        f.socket().onText(ACK); f.queue.runReady();
        assertEquals("s", f.supervisor.sessionId());
    }
    @Test public void stopCancelsRetryAndDropsLateEvents() {
        Fixture f = new Fixture(); SocketTransport.Listener old = f.socket();
        old.onFailure(new Exception()); f.queue.runReady();
        f.supervisor.stop(); f.supervisor.stop();
        old.onClosed(1000, "late"); old.onText(ACK); f.queue.advance(10000);
        assertEquals(1, f.transport.attempts.size()); assertNull(f.supervisor.sessionId());
        try { f.supervisor.start("ws://localhost/"); fail("one-shot lifecycle"); }
        catch (IllegalStateException expected) { }
    }
    @Test public void missingAckTimesOutWithoutWaitingForSocketClose() {
        Fixture f = new Fixture(); f.socket().onOpen(); f.queue.runReady();
        f.queue.advance(1000); assertEquals(1, f.events.losses);
        f.queue.advance(100); assertEquals(2, f.transport.attempts.size());
    }
    @Test public void failedHandshakeDoesNotResetBackoffOnOpen() {
        Fixture f = new Fixture(); f.socket().onOpen(); f.queue.runReady();
        f.queue.advance(1000); f.queue.advance(100);
        f.socket().onOpen(); f.queue.runReady();
        f.queue.advance(1000); f.queue.advance(199);
        assertEquals(2, f.transport.attempts.size());
        f.queue.advance(1); assertEquals(3, f.transport.attempts.size());
    }
    @Test public void successfulAckCancelsHandshakeAndEnablesHeartbeat() {
        Fixture f = new Fixture();
        f.socket().onBinary(new byte[2]); f.queue.runReady(); assertEquals(0, f.events.frames);
        f.socket().onText(ACK); f.queue.runReady();
        f.queue.advance(1000); assertEquals(0, f.events.losses);
        assertTrue(f.transport.sent.get(0).contains("ping"));
        f.queue.advance(1000); assertEquals(1, f.events.losses);
        f.queue.advance(100); assertEquals(2, f.transport.attempts.size());
    }
    @Test public void malformedAckDoesNotDisableHandshakeTimeout() {
        Fixture f = new Fixture();
        f.socket().onText("{\"type\":\"hello.ack\"}"); f.queue.runReady();
        f.queue.advance(1000); assertEquals(1, f.events.losses); assertNull(f.supervisor.sessionId());
    }
    @Test public void unsupportedProtocolIsNotMarkedReady() {
        Fixture f = new Fixture(); f.socket().onText(ACK.replace("\"protocol\":1", "\"protocol\":2"));
        f.queue.runReady(); assertEquals(1, f.events.losses); assertNull(f.supervisor.sessionId());
        assertEquals(0, f.events.messages);
    }
}
