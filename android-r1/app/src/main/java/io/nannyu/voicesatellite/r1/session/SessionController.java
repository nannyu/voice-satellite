package io.nannyu.voicesatellite.r1.session;

/**
 * Pure-Java satellite session state machine from docs/02-r1-device.md.
 * No Android imports so the full transition table runs in JVM unit tests.
 *
 * States: IDLE waits for a trigger; LISTENING captures an utterance;
 * PROCESSING waits for the backend after voice.end; SPEAKING plays the response.
 *
 * Illegal events are never fatal: they are dropped and reported through
 * Listener.onIllegalTransition with the state left unchanged.
 */
public final class SessionController {
    public enum State { IDLE, LISTENING, PROCESSING, SPEAKING }

    public enum Event {
        WAKE_DETECTED, BUTTON_PRESSED,
        SPEECH_STARTED, SPEECH_ENDED,
        SERVER_PROCESSING, SERVER_RESPONDING, SERVER_IDLE,
        PLAYBACK_FINISHED,
        MEDIA_STARTED, MEDIA_STOPPED,
        NETWORK_LOST, ERROR,
        /** Generated internally; never sent by external callers. */
        LISTEN_TIMEOUT, RESPONSE_TIMEOUT
    }

    public interface Listener {
        /** Called only on actual state changes. */
        void onTransition(State from, State to, Event event);
        /** Called when an event is not legal in the current state; the state is unchanged. */
        void onIllegalTransition(State state, Event event);
    }

    /** Testable timer indirection; Android wiring uses a Handler, tests use a fake. */
    public interface Scheduler {
        Object schedule(long delayMs, Runnable task);
        void cancel(Object handle);
    }

    private final Listener listener;
    private final Scheduler scheduler;
    private final long noSpeechTimeoutMs;
    private final long responseTimeoutMs;

    private State state=State.IDLE;
    private Object timer;
    private int generation;

    public SessionController(Listener listener,Scheduler scheduler,long noSpeechTimeoutMs,long responseTimeoutMs){
        if(listener==null||scheduler==null)throw new IllegalArgumentException("listener and scheduler are required");
        if(noSpeechTimeoutMs<=0||responseTimeoutMs<=0)throw new IllegalArgumentException("timeouts must be positive");
        this.listener=listener;this.scheduler=scheduler;
        this.noSpeechTimeoutMs=noSpeechTimeoutMs;this.responseTimeoutMs=responseTimeoutMs;
    }

    public synchronized State state(){return state;}

    public synchronized void onEvent(Event event){
        if(event==null)throw new IllegalArgumentException("event is required");
        switch(state){
            case IDLE: onIdle(event);return;
            case LISTENING: onListening(event);return;
            case PROCESSING: onProcessing(event);return;
            case SPEAKING: onSpeaking(event);return;
            default: illegal(event);
        }
    }

    private void onIdle(Event event){
        switch(event){
            case WAKE_DETECTED: case BUTTON_PRESSED: enter(State.LISTENING,event);return;
            case MEDIA_STARTED: case MEDIA_STOPPED: return; // informational until Phase 5
            default: illegal(event);
        }
    }

    private void onListening(Event event){
        switch(event){
            case SPEECH_STARTED: disarmTimer();return; // stay LISTENING; VAD owns max-utterance
            case SPEECH_ENDED: case SERVER_PROCESSING: enter(State.PROCESSING,event);return;
            case LISTEN_TIMEOUT: case SERVER_IDLE: case NETWORK_LOST: case ERROR: enter(State.IDLE,event);return;
            case MEDIA_STARTED: case MEDIA_STOPPED: return;
            default: illegal(event);
        }
    }

    private void onProcessing(Event event){
        switch(event){
            case SERVER_PROCESSING: return; // duplicate backend ack
            case SERVER_RESPONDING: enter(State.SPEAKING,event);return;
            case RESPONSE_TIMEOUT: case SERVER_IDLE: case NETWORK_LOST: case ERROR: enter(State.IDLE,event);return;
            case MEDIA_STARTED: case MEDIA_STOPPED: return;
            default: illegal(event); // includes a new WAKE_DETECTED while processing
        }
    }

    private void onSpeaking(Event event){
        switch(event){
            case PLAYBACK_FINISHED: case SERVER_IDLE: case NETWORK_LOST: case ERROR: enter(State.IDLE,event);return;
            case SERVER_RESPONDING: return; // further response chunks for the same utterance
            case MEDIA_STARTED: case MEDIA_STOPPED: return;
            default: illegal(event); // barge-in is an explicit v0.1 non-goal
        }
    }

    private void enter(State next,Event cause){
        State from=state;
        disarmTimer();
        state=next;
        if(next==State.LISTENING)armTimer(noSpeechTimeoutMs,Event.LISTEN_TIMEOUT);
        else if(next==State.PROCESSING)armTimer(responseTimeoutMs,Event.RESPONSE_TIMEOUT);
        listener.onTransition(from,next,cause);
    }

    private void illegal(Event event){listener.onIllegalTransition(state,event);}

    private void armTimer(long delayMs,final Event timeoutEvent){
        final int ticket=++generation;
        timer=scheduler.schedule(delayMs,new Runnable(){@Override public void run(){
            synchronized(SessionController.this){
                if(ticket!=generation||timer==null)return;
                timer=null;
                onEvent(timeoutEvent);
            }
        }});
    }

    private void disarmTimer(){
        generation++;
        if(timer!=null){scheduler.cancel(timer);timer=null;}
    }
}
