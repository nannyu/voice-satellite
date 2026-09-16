package io.nannyu.voicesatellite.r1.transport;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;

import io.nannyu.voicesatellite.r1.protocol.Protocol;

/**
 * Keeps a WebSocketTransport connected: exponential-backoff reconnect with
 * jitter, heartbeat paced by the server hello.ack, and dead-link detection.
 * All decisions live in the unit-tested ReconnectPolicy and HeartbeatMonitor;
 * this class is wiring only. Listener callbacks arrive on background threads.
 * Malformed inbound frames are dropped per the protocol tolerance rules.
 */
public final class ConnectionSupervisor {
    public interface Listener {
        void onConnected();
        void onMessage(Protocol.Message message);
        void onAudio(byte[] frame);
        void onDisconnected(boolean willReconnect);
    }

    private static final long HEARTBEAT_TICK_MS=1000;
    private static final int NORMAL_CLOSE=1000;

    private final WebSocketTransport transport;
    private final Listener listener;
    private final ReconnectPolicy reconnect;
    private final long heartbeatTimeoutMs;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor();
    private final HeartbeatMonitor.Clock clock=new HeartbeatMonitor.Clock(){@Override public long nowMs(){return System.currentTimeMillis();}};

    private String url;
    private volatile HeartbeatMonitor heartbeat;
    private volatile String sessionId;
    private volatile boolean running;

    public ConnectionSupervisor(WebSocketTransport transport,Listener listener,
                                long reconnectBaseMs,long reconnectMaxMs,double jitter,long heartbeatTimeoutMs){
        if(transport==null||listener==null)throw new IllegalArgumentException("transport and listener are required");
        this.transport=transport;this.listener=listener;
        this.reconnect=new ReconnectPolicy(reconnectBaseMs,reconnectMaxMs,jitter,new Random());
        this.heartbeatTimeoutMs=heartbeatTimeoutMs;
    }

    public synchronized void start(String endpoint){
        if(running)throw new IllegalStateException("already started");
        if(endpoint==null)throw new IllegalArgumentException("endpoint is required");
        running=true;url=endpoint;
        executor.execute(connectTask);
        executor.scheduleWithFixedDelay(heartbeatTask,HEARTBEAT_TICK_MS,HEARTBEAT_TICK_MS,TimeUnit.MILLISECONDS);
    }

    public synchronized void stop(){
        running=false;heartbeat=null;
        transport.disconnect(NORMAL_CLOSE,"stop");
        executor.shutdown();
    }

    public boolean send(JSONObject message){return transport.sendText(message.toString());}
    public boolean sendAudio(byte[] frame){return transport.sendBinary(frame);}
    public String sessionId(){return sessionId;}

    private final Runnable connectTask=new Runnable(){@Override public void run(){
        if(running)transport.connect(url,socketListener);
    }};

    private final Runnable heartbeatTask=new Runnable(){@Override public void run(){
        HeartbeatMonitor monitor=heartbeat;
        if(!running||monitor==null)return;
        switch(monitor.check()){
            case HEARTBEAT_DUE:
                String id=sessionId;
                if(id!=null&&transport.sendText(Protocol.ping(id).toString()))monitor.onHeartbeatSent();
                break;
            case DEAD:
                heartbeat=null;
                transport.disconnect(NORMAL_CLOSE,"heartbeat timeout"); // onClosed schedules the reconnect
                break;
            default: // ALIVE
        }
    }};

    private final WebSocketTransport.Listener socketListener=new WebSocketTransport.Listener(){
        @Override public void onOpen(){reconnect.reset();listener.onConnected();}
        @Override public void onText(String text){
            HeartbeatMonitor monitor=heartbeat;if(monitor!=null)monitor.onInbound();
            final Protocol.Message message;
            try{message=Protocol.parse(text);}catch(Protocol.ProtocolException e){return;} // malformed frames dropped by contract
            if(Protocol.TYPE_HELLO_ACK.equals(message.type)){
                try{
                    Protocol.HelloAck ack=Protocol.helloAck(message);
                    sessionId=ack.sessionId;
                    heartbeat=new HeartbeatMonitor(ack.heartbeatSeconds*1000L,heartbeatTimeoutMs,clock);
                }catch(Protocol.ProtocolException ignored){ /* incomplete ack: keep previous heartbeat config */ }
            }
            listener.onMessage(message);
        }
        @Override public void onBinary(byte[] frame){
            HeartbeatMonitor monitor=heartbeat;if(monitor!=null)monitor.onInbound();
            listener.onAudio(frame);
        }
        @Override public void onClosed(int code,String reason){scheduleReconnect();}
        @Override public void onFailure(Throwable failure){scheduleReconnect();}
    };

    private void scheduleReconnect(){
        heartbeat=null;sessionId=null;
        if(!running){listener.onDisconnected(false);return;}
        listener.onDisconnected(true);
        executor.schedule(connectTask,reconnect.nextDelayMs(),TimeUnit.MILLISECONDS);
    }
}
