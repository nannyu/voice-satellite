package io.nannyu.voicesatellite.r1.transport;

/** Injectable boundary around the WebSocket implementation. */
public interface SocketTransport {
    interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] frame);
        void onClosed(int code, String reason);
        void onFailure(Throwable failure);
    }
    void connect(String url, Listener listener);
    boolean sendText(String text);
    boolean sendBinary(byte[] frame);
    void disconnect(int code, String reason);
    void shutdown();
}
