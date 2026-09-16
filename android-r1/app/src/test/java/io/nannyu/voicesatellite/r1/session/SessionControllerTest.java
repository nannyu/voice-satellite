package io.nannyu.voicesatellite.r1.session;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class SessionControllerTest {
    private static final long NO_SPEECH_MS = 5000, RESPONSE_MS = 10000;

    private static final class FakeScheduler implements SessionController.Scheduler {
        Runnable task; boolean cancelled;
        @Override public Object schedule(long delayMs, Runnable runnable) { task = runnable; cancelled = false; return runnable; }
        @Override public void cancel(Object handle) { if (handle == task) cancelled = true; }
        void fire() { if (task != null && !cancelled) task.run(); }
    }

    private static final class Log {
        final List<String> transitions = new ArrayList<>();
        final List<String> illegal = new ArrayList<>();
        SessionController.Listener listener() {
            return new SessionController.Listener() {
                @Override public void onTransition(SessionController.State from, SessionController.State to, SessionController.Event event) {
                    transitions.add(from + "->" + to + " (" + event + ")");
                }
                @Override public void onIllegalTransition(SessionController.State state, SessionController.Event event) {
                    illegal.add(state + " rejected " + event);
                }
            };
        }
    }

    private static SessionController controller(Log log, FakeScheduler scheduler) {
        return new SessionController(log.listener(), scheduler, NO_SPEECH_MS, RESPONSE_MS);
    }

    @Test public void fullWakeToResponseFlow() {
        Log log = new Log(); FakeScheduler scheduler = new FakeScheduler();
        SessionController c = controller(log, scheduler);

        c.onEvent(SessionController.Event.WAKE_DETECTED);
        c.onEvent(SessionController.Event.SPEECH_STARTED);
        c.onEvent(SessionController.Event.SPEECH_ENDED);
        c.onEvent(SessionController.Event.SERVER_RESPONDING);
        c.onEvent(SessionController.Event.PLAYBACK_FINISHED);

        assertEquals(SessionController.State.IDLE, c.state());
        assertEquals("[IDLE->LISTENING (WAKE_DETECTED), LISTENING->PROCESSING (SPEECH_ENDED), PROCESSING->SPEAKING (SERVER_RESPONDING), SPEAKING->IDLE (PLAYBACK_FINISHED)]",
                log.transitions.toString());
        assertEquals("[]", log.illegal.toString());
    }

    @Test public void wakeWithoutSpeechTimesOutToIdle() {
        Log log = new Log(); FakeScheduler scheduler = new FakeScheduler();
        SessionController c = controller(log, scheduler);

        c.onEvent(SessionController.Event.WAKE_DETECTED);
        scheduler.fire(); // no-speech timer

        assertEquals(SessionController.State.IDLE, c.state());
        assertEquals("[IDLE->LISTENING (WAKE_DETECTED), LISTENING->IDLE (LISTEN_TIMEOUT)]", log.transitions.toString());
    }

    @Test public void speechStartCancelsNoSpeechTimer() {
        Log log = new Log(); FakeScheduler scheduler = new FakeScheduler();
        SessionController c = controller(log, scheduler);

        c.onEvent(SessionController.Event.WAKE_DETECTED);
        c.onEvent(SessionController.Event.SPEECH_STARTED);
        scheduler.fire(); // stale timer must not fire

        assertEquals(SessionController.State.LISTENING, c.state());
        assertEquals("[IDLE->LISTENING (WAKE_DETECTED)]", log.transitions.toString());
    }

    @Test public void newWakeDuringProcessingIsRejected() {
        Log log = new Log(); FakeScheduler scheduler = new FakeScheduler();
        SessionController c = controller(log, scheduler);

        c.onEvent(SessionController.Event.WAKE_DETECTED);
        c.onEvent(SessionController.Event.SPEECH_ENDED);
        c.onEvent(SessionController.Event.WAKE_DETECTED);

        assertEquals(SessionController.State.PROCESSING, c.state());
        assertEquals("[PROCESSING rejected WAKE_DETECTED]", log.illegal.toString());
    }

    @Test public void responseTimeoutReturnsToIdle() {
        Log log = new Log(); FakeScheduler scheduler = new FakeScheduler();
        SessionController c = controller(log, scheduler);

        c.onEvent(SessionController.Event.WAKE_DETECTED);
        c.onEvent(SessionController.Event.SPEECH_ENDED);
        scheduler.fire(); // response timer

        assertEquals(SessionController.State.IDLE, c.state());
        assertEquals("[IDLE->LISTENING (WAKE_DETECTED), LISTENING->PROCESSING (SPEECH_ENDED), PROCESSING->IDLE (RESPONSE_TIMEOUT)]",
                log.transitions.toString());
    }

    @Test public void illegalEventInIdleIsReportedAndIgnored() {
        Log log = new Log(); FakeScheduler scheduler = new FakeScheduler();
        SessionController c = controller(log, scheduler);

        c.onEvent(SessionController.Event.SPEECH_ENDED);
        c.onEvent(SessionController.Event.PLAYBACK_FINISHED);

        assertEquals(SessionController.State.IDLE, c.state());
        assertEquals("[]", log.transitions.toString());
        assertEquals("[IDLE rejected SPEECH_ENDED, IDLE rejected PLAYBACK_FINISHED]", log.illegal.toString());
    }

    @Test public void serverIdleForcesIdleFromSpeaking() {
        Log log = new Log(); FakeScheduler scheduler = new FakeScheduler();
        SessionController c = controller(log, scheduler);

        c.onEvent(SessionController.Event.BUTTON_PRESSED);
        c.onEvent(SessionController.Event.SPEECH_ENDED);
        c.onEvent(SessionController.Event.SERVER_RESPONDING);
        c.onEvent(SessionController.Event.SERVER_IDLE);

        assertEquals(SessionController.State.IDLE, c.state());
        assertEquals("[]", log.illegal.toString());
    }

    @Test public void errorFromListeningReturnsToIdle() {
        Log log = new Log(); FakeScheduler scheduler = new FakeScheduler();
        SessionController c = controller(log, scheduler);

        c.onEvent(SessionController.Event.WAKE_DETECTED);
        c.onEvent(SessionController.Event.ERROR);

        assertEquals(SessionController.State.IDLE, c.state());
        assertEquals("[IDLE->LISTENING (WAKE_DETECTED), LISTENING->IDLE (ERROR)]", log.transitions.toString());
    }

    @Test public void bargeInDuringSpeakingIsRejected() {
        Log log = new Log(); FakeScheduler scheduler = new FakeScheduler();
        SessionController c = controller(log, scheduler);

        c.onEvent(SessionController.Event.WAKE_DETECTED);
        c.onEvent(SessionController.Event.SPEECH_ENDED);
        c.onEvent(SessionController.Event.SERVER_RESPONDING);
        c.onEvent(SessionController.Event.WAKE_DETECTED);

        assertEquals(SessionController.State.SPEAKING, c.state());
        assertEquals("[SPEAKING rejected WAKE_DETECTED]", log.illegal.toString());
    }
}
