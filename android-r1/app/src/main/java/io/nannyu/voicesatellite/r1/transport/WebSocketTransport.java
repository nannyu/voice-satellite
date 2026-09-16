package io.nannyu.voicesatellite.r1.transport;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Thin OkHttp WebSocket wrapper carrying JSON text frames and binary audio
 * frames. OkHttp 3.12.13 is the last branch compatible with Android 5.1.
 * Listener callbacks arrive on OkHttp background threads.
 */
public final class WebSocketTransport {
    public interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] frame);
        void onClosed(int code,String reason);
        void onFailure(Throwable failure);
    }

    private final OkHttpClient client;
    private WebSocket socket;

    public WebSocketTransport(){client=new OkHttpClient();}

    public synchronized void connect(String url,final Listener listener){
        disconnect(1000,"reconnect");
        socket=client.newWebSocket(new Request.Builder().url(url).build(),new WebSocketListener(){
            @Override public void onOpen(WebSocket webSocket,Response response){listener.onOpen();}
            @Override public void onMessage(WebSocket webSocket,String text){listener.onText(text);}
            @Override public void onMessage(WebSocket webSocket,ByteString bytes){listener.onBinary(bytes.toByteArray());}
            @Override public void onClosed(WebSocket webSocket,int code,String reason){listener.onClosed(code,reason);}
            @Override public void onFailure(WebSocket webSocket,Throwable t,Response response){listener.onFailure(t);}
        });
    }

    public synchronized boolean sendText(String text){WebSocket s=socket;return s!=null&&s.send(text);}
    public synchronized boolean sendBinary(byte[] frame){WebSocket s=socket;return s!=null&&s.send(ByteString.of(frame));}
    public synchronized void disconnect(int code,String reason){WebSocket s=socket;socket=null;if(s!=null)s.close(code,reason);}
    public synchronized void shutdown(){disconnect(1000,"shutdown");client.dispatcher().executorService().shutdown();}
}
