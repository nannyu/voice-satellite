package io.nannyu.voicesatellite.r1.protocol;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Constructors and parsers for the docs/04 draft protocol (protocol version 1).
 * Runs against the Android framework org.json on device and the vintage
 * org.json:json:20140107 artifact in JVM tests, so only that API surface is used.
 *
 * Tolerance rules (documented in docs/04):
 * - Malformed JSON, non-object payloads and missing/empty "type" raise ProtocolException.
 * - Unknown but well-formed types parse into a Message with isKnown() == false;
 *   receivers must drop them without crashing (docs/04 versioning rule).
 * - Typed decoders (helloAck, stateValue, errorValue) raise ProtocolException
 *   naming the first missing or mistyped required field.
 */
public final class Protocol {
    public static final int VERSION=1;

    public static final String TYPE_HELLO="hello";
    public static final String TYPE_HELLO_ACK="hello.ack";
    public static final String TYPE_VOICE_START="voice.start";
    public static final String TYPE_VOICE_END="voice.end";
    public static final String TYPE_STATE="state";
    public static final String TYPE_RESPONSE_START="response.start";
    public static final String TYPE_RESPONSE_END="response.end";
    public static final String TYPE_PING="ping";
    public static final String TYPE_PONG="pong";
    public static final String TYPE_ERROR="error";

    public static final String TRIGGER_WAKE_WORD="wake_word";
    public static final String TRIGGER_BUTTON="button";

    public static final String REASON_VAD_END="vad_end";
    public static final String REASON_TIMEOUT="timeout";
    public static final String REASON_CANCELLED="cancelled";
    public static final String REASON_ERROR="error";

    private static final String AUDIO_CODEC="pcm_s16le";
    private static final int AUDIO_SAMPLE_RATE=16000;
    private static final int AUDIO_CHANNELS=1;

    public static final class ProtocolException extends Exception {
        public ProtocolException(String message){super(message);}
        public ProtocolException(String message,Throwable cause){super(message,cause);}
    }

    /** A parsed inbound frame. Unknown types are preserved, not rejected. */
    public static final class Message {
        public final String type;
        public final JSONObject body;
        private Message(String type,JSONObject body){this.type=type;this.body=body;}
        public boolean isKnown(){
            return TYPE_HELLO_ACK.equals(type)||TYPE_STATE.equals(type)
                ||TYPE_RESPONSE_START.equals(type)||TYPE_RESPONSE_END.equals(type)
                ||TYPE_PONG.equals(type)||TYPE_ERROR.equals(type)
                ||TYPE_HELLO.equals(type)||TYPE_VOICE_START.equals(type)||TYPE_VOICE_END.equals(type)||TYPE_PING.equals(type);
        }
    }

    public static final class HelloAck {
        public final int protocol; public final String sessionId; public final int heartbeatSeconds;
        private HelloAck(int protocol,String sessionId,int heartbeatSeconds){this.protocol=protocol;this.sessionId=sessionId;this.heartbeatSeconds=heartbeatSeconds;}
    }

    public static final class ErrorMessage {
        public final String code; public final String message; public final boolean recoverable;
        private ErrorMessage(String code,String message,boolean recoverable){this.code=code;this.message=message;this.recoverable=recoverable;}
    }

    private Protocol(){}

    // Outbound constructors ------------------------------------------------

    public static JSONObject hello(String deviceId,String model,String clientVersion,String platform,List<String> capabilities){
        if(isBlank(deviceId))throw new IllegalArgumentException("device_id is required");
        try{
            JSONObject device=new JSONObject();
            device.put("model",model);device.put("client_version",clientVersion);device.put("platform",platform);
            JSONObject hello=new JSONObject();
            hello.put("type",TYPE_HELLO);hello.put("protocol",VERSION);hello.put("device_id",deviceId);
            hello.put("device",device);hello.put("capabilities",new JSONArray(capabilities));
            return hello;
        }catch(JSONException e){throw new IllegalArgumentException(e);}
    }

    public static JSONObject ping(String sessionId){
        try{
            JSONObject ping=new JSONObject();
            ping.put("type",TYPE_PING);ping.put("session_id",sessionId);
            return ping;
        }catch(JSONException e){throw new IllegalArgumentException(e);}
    }

    public static JSONObject voiceStart(String sessionId,String trigger){
        if(isBlank(sessionId))throw new IllegalArgumentException("session_id is required");
        try{
            JSONObject audio=new JSONObject();
            audio.put("codec",AUDIO_CODEC);audio.put("sample_rate",AUDIO_SAMPLE_RATE);audio.put("channels",AUDIO_CHANNELS);
            JSONObject start=new JSONObject();
            start.put("type",TYPE_VOICE_START);start.put("session_id",sessionId);start.put("trigger",trigger);start.put("audio",audio);
            return start;
        }catch(JSONException e){throw new IllegalArgumentException(e);}
    }

    public static JSONObject voiceEnd(String sessionId,String reason){
        if(isBlank(sessionId))throw new IllegalArgumentException("session_id is required");
        try{
            JSONObject end=new JSONObject();
            end.put("type",TYPE_VOICE_END);end.put("session_id",sessionId);end.put("reason",reason);
            return end;
        }catch(JSONException e){throw new IllegalArgumentException(e);}
    }

    public static JSONObject error(String code,String message,boolean recoverable){
        if(isBlank(code))throw new IllegalArgumentException("code is required");
        try{
            JSONObject error=new JSONObject();
            error.put("type",TYPE_ERROR);error.put("code",code);error.put("message",message);error.put("recoverable",recoverable);
            return error;
        }catch(JSONException e){throw new IllegalArgumentException(e);}
    }

    // Inbound parsing ------------------------------------------------------

    public static Message parse(String text) throws ProtocolException {
        if(text==null)throw new ProtocolException("frame text is null");
        final JSONObject body;
        try{body=new JSONObject(text);}
        catch(JSONException e){throw new ProtocolException("frame is not a JSON object",e);}
        String type=body.optString("type",null);
        if(isBlank(type))throw new ProtocolException("frame is missing a string \"type\" field");
        return new Message(type,body);
    }

    public static HelloAck helloAck(Message message) throws ProtocolException {
        requireType(message,TYPE_HELLO_ACK);
        JSONObject body=message.body;
        return new HelloAck(requiredInt(body,"protocol"),requiredString(body,"session_id"),positiveInt(body,"heartbeat_seconds"));
    }

    /** Decodes a state message value (idle/listening/processing/responding). */
    public static String stateValue(Message message) throws ProtocolException {
        requireType(message,TYPE_STATE);
        return requiredString(message.body,"state");
    }

    public static ErrorMessage errorValue(Message message) throws ProtocolException {
        requireType(message,TYPE_ERROR);
        JSONObject body=message.body;
        if(!body.has("recoverable"))throw new ProtocolException("error is missing \"recoverable\"");
        return new ErrorMessage(requiredString(body,"code"),body.optString("message",""),body.optBoolean("recoverable",false));
    }

    // Helpers --------------------------------------------------------------

    private static void requireType(Message message,String expected) throws ProtocolException {
        if(message==null)throw new ProtocolException("message is null");
        if(!expected.equals(message.type))throw new ProtocolException("expected "+expected+" but got "+message.type);
    }

    private static String requiredString(JSONObject body,String field) throws ProtocolException {
        String value=body.optString(field,null);
        if(value==null)throw new ProtocolException("missing string field \""+field+"\"");
        return value;
    }

    private static int requiredInt(JSONObject body,String field) throws ProtocolException {
        if(!body.has(field))throw new ProtocolException("missing integer field \""+field+"\"");
        try{return body.getInt(field);}
        catch(JSONException e){throw new ProtocolException("field \""+field+"\" is not an integer",e);}
    }

    private static int positiveInt(JSONObject body,String field) throws ProtocolException {
        int value=requiredInt(body,field);
        if(value<=0)throw new ProtocolException("field \""+field+"\" must be positive: "+value);
        return value;
    }

    private static boolean isBlank(String value){return value==null||value.trim().isEmpty();}
}
