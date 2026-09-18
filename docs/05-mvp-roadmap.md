# 05. MVP Roadmap

## Phase 0 - Hardware validation

Goal: prove the stock R1 is a viable endpoint before writing the integration around assumptions.

Tasks:

- connect with Wi-Fi ADB — verified 2026-09-17
- collect Android/build/package information — verified 2026-09-17 (stock 3415 inventory and the 3415 → 3448 upgrade in `tools/r1-upgrade-3448/`)
- enumerate audio input sources and supported formats — verified 2026-09-18 on 3448 with APK `0.1.2-playback` (MIC / VOICE_RECOGNITION / VOICE_COMMUNICATION all init+100 frames at 16 kHz stereo PCM16 + software mono); prerequisite: `pm hide com.phicomm.speaker.device`
- record microphone samples — verified 2026-09-18 (`quiet` + `speech` probes, EXIT=0; speech MIC peakRms≈960 vs quiet≈100–280)
- test speaker playback — verified 2026-09-18 (`[PLAYBACK] trackInit=true played=true written=48000/48000`)
- test simultaneous/alternating capture and playback — verified 2026-09-18 (`simultaneous=true` while 440 Hz tone plays)
- inspect audio focus behavior
- identify vendor service conflicts — verified 2026-09-18: stock `com.phicomm.speaker.device` (Unisound) holds active AudioRecord inputs at boot and leaves AudioFlinger deadlocked (`AudioFlinger may be deadlocked`); without hide, probe MIC hard-timeouts. Reversible via `pm unhide`
- measure idle memory and CPU budget

Exit criteria:

- repeatable microphone recording without Root — verified 2026-09-18 (3× quiet + 1× speech after hide; no App restart between runs)
- repeatable speaker playback without Root — verified 2026-09-18
- documented ADB recovery path — verified 2026-09-17 (`tools/r1-upgrade-3448/` file-level backup plus pinned OTA restore path; raw brick recovery still requires Rockchip Loader/Maskrom)

## Phase 1 - Android audio skeleton

- minimum Android-compatible Gradle project — verified 2026-09-17 (CI builds the armeabi-v7a debug APK and runs unit tests)
- foreground/background service strategy compatible with target runtime — verified 2026-09-18: `VoiceSatelliteService` (started + bound, sticky, notification)
- AudioRecord capture — verified 2026-09-18 on device via diagnostics probe (all three sources)
- AudioTrack playback — verified 2026-09-18 on device via diagnostics probe
- diagnostics Activity — implemented (probe, JNI smoke test, manual capture); launcher is now `SatelliteActivity`
- connection/retry skeleton — verified 2026-09-18: `ConnectionSupervisor` live reconnect after gateway kill

Exit criteria: install APK over ADB and complete a loopback/test-session reliably. — verified 2026-09-18 (diagnostics probe + live echo session against gateway test server).

## Phase 2 - Voice session

- wake-word engine
- VAD — libfvad JNI exercised on device 2026-09-18 inside the probe (speech frame counts reported); dedicated endpointing path in `AudioRecorder` still needs a session-level device test
- pre-roll audio buffer — implemented in `AudioRecorder` (unverified on device)
- session state machine — verified 2026-09-18 on device: `VoiceSatelliteService` wires `SessionController` + button trigger; UI on `SatelliteActivity`
- WebSocket transport — verified 2026-09-18 on device against `gateway/test_server.py --mode echo` (hello → hello.ack; auto-reconnect after server kill)
- binary audio frames — verified 2026-09-18: uplink PCM frames + downlink echo playback (`Playing N bytes`); batch 5/5 + reconnect echo OK

Exit criteria: wake, speak and receive captured utterance at a test server for 20 consecutive sessions without app restart. — **partial 2026-09-18**: button-triggered echo works end-to-end (1 + 5 + reconnect); wake-word path and formal 20-session soak still open.

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
