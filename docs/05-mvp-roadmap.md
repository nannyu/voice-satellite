# 05. MVP Roadmap

## Phase 0 - Hardware validation

Goal: prove the stock R1 is a viable endpoint before writing the integration around assumptions.

Tasks:

- connect with Wi-Fi ADB
- collect Android/build/package information
- enumerate audio input sources and supported formats
- record microphone samples
- test speaker playback
- test simultaneous/alternating capture and playback
- inspect audio focus behavior
- identify vendor service conflicts
- measure idle memory and CPU budget

Exit criteria:

- repeatable microphone recording without Root
- repeatable speaker playback without Root
- documented ADB recovery path

## Phase 1 - Android audio skeleton

- minimum Android-compatible Gradle project
- foreground/background service strategy compatible with target runtime
- AudioRecord capture
- AudioTrack playback
- diagnostics Activity
- connection/retry skeleton

Exit criteria: install APK over ADB and complete a loopback/test-session reliably.

## Phase 2 - Voice session

- wake-word engine
- VAD
- pre-roll audio buffer
- session state machine
- WebSocket transport
- binary audio frames

Exit criteria: wake, speak and receive captured utterance at a test server for 20 consecutive sessions without app restart.

## Phase 3 - Home Assistant spike

Test direct and Gateway strategies against the current Home Assistant version.

Measure:

- integration complexity
- authentication
- audio transport compatibility
- latency
- announcement/media implications
- reconnect behavior

Exit criteria: select and document one v0.1 HA architecture based on measured results.

## Phase 4 - End-to-end Assist

- R1 wake/listen
- HA STT
- HA Conversation Agent
- HA action execution
- HA TTS
- R1 response playback

Exit criteria: 20 consecutive common voice requests with no manual recovery.

## Phase 5 - Media

- ExoPlayer URL playback
- pause/resume/stop/seek
- volume
- duck during voice session
- restore playback after interaction

Exit criteria: voice request can start media; wake word during media produces a voice interaction and restores playback correctly.

## Phase 6 - Reliability

Test:

- R1 reboot
- Wi-Fi outage
- HA restart
- Gateway restart
- long idle period
- 2+ hour media playback
- repeated voice/media transitions

Target: device returns to useful `Idle` state automatically after recoverable failures.

## v0.2 candidates

- HA announcements
- continuous conversation
- barge-in
- stop wake word
- timer semantics
- optional LED integration
- audiobook progress synchronization

## v0.3 candidates

- multi-room
- generic Android client
- Linux client
- Xiaozhi compatibility adapter
- device provisioning/discovery

## Definition of MVP success

On stock firmware and without opening the enclosure:

1. Reboot R1.
2. Satellite starts automatically.
3. Say configured wake word.
4. Ask Home Assistant to perform an action or answer a question.
5. Hear the response from R1.
6. Ask for media playback.
7. Wake the assistant while media is playing.
8. Complete the interaction and restore media.
9. Restart HA/network and observe automatic recovery.

Anything less is a demo. The goal is a household appliance, which is an annoyingly higher bar than making a terminal print `connected` once.
