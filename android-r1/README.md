# R1 Android Client

Android 5.1 / API 22 compatible Phicomm R1 client. The first executable slice now contains microphone capture, hybrid libfvad endpointing, Opus JNI encode/decode and speaker playback diagnostics.

## Current implementation

```text
app/src/main/java/io/nannyu/voicesatellite/r1/
  MainActivity.java                 diagnostics UI
  audio/AudioRecorder.java         16 kHz PCM16 + pre-roll + endpointing
  audio/AudioProbe.java            mic-source probe + playback diagnostics with hard timeouts
  audio/AudioPlayer.java           blocking 16 kHz mono PCM16 AudioTrack playback
  audio/PcmAudio.java              pure PCM16 helpers (stereo downmix, tone generation)
  audio/VadDetector.java           libfvad JNI wrapper
  audio/OpusEncoder.java           Opus JNI encoder
  audio/OpusDecoder.java           Opus JNI decoder
  session/SessionController.java   pure-Java satellite state machine (idle/listening/processing/speaking)
  protocol/Protocol.java           docs/04 draft message constructors and parsers (org.json)
  transport/WebSocketTransport.java     thin OkHttp 3.12 WebSocket wrapper (text + binary frames)
  transport/ConnectionSupervisor.java   reconnect + heartbeat wiring (not unit-tested)
  transport/ReconnectPolicy.java        pure backoff decisions (exponential, capped, jittered)
  transport/HeartbeatMonitor.java       pure heartbeat/dead-link decisions with injected clock
app/src/test/java/io/nannyu/voicesatellite/r1/
  audio/PcmAudioTest.java               JVM unit tests for the pure PCM helpers
  session/SessionControllerTest.java    state machine transition table tests
  protocol/ProtocolTest.java            protocol shape and tolerance tests
  transport/ReconnectPolicyTest.java    backoff sequence, cap and jitter bounds
  transport/HeartbeatMonitorTest.java   heartbeat interval and dead-link verdicts
app/src/main/cpp/
  voice_sat_native.cpp             project JNI bridge
  CMakeLists.txt
scripts/bootstrap-native.sh        pinned upstream dependency bootstrap
```

The session/protocol/transport layer is device-independent, fully unit-tested
on the JVM, and deliberately not wired into the diagnostics UI yet. org.json is
provided by the Android framework on device; JVM tests pin the vintage
`org.json:json:20140107` artifact to stay on the Android 5.1 API surface.

Target: `minSdk 22`, `targetSdk 22`, `armeabi-v7a` only.

Checklist B wiring lives in `SatelliteActivity` + `VoiceSatelliteService`
(session/transport/mic/speaker). `MainActivity` remains the Phase A diagnostics UI
(open it from SatelliteActivity). Default echo URL is `ws://192.168.1.18:8765`
(plain TextView — R1 ClipboardManager is null, so EditText crashes under
accessibility). Mic still requires `pm hide com.phicomm.speaker.device` + reboot.

## Build

Requirements: JDK 17, Android SDK 33, NDK 25.2.9519653, CMake 3.22.1 and Gradle 7.6.4.

```bash
cd android-r1
bash scripts/bootstrap-native.sh
gradle :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

CI performs the same build and uploads `voice-satellite-r1-debug` as a workflow artifact. The build is verified on GitHub Actions for `armeabi-v7a`.

## Native dependencies

`bootstrap-native.sh` fetches exact upstream revisions instead of committing third-party source snapshots:

- Xiph Opus `v1.3.1`
- `dpirch/libfvad` commit `532ab666c20d3cfda38bca63abbb0f152706c369`

The adapted Java/JNI structure originates from MIT-licensed `kitakeyos-dev/r1-manager`; its license notice is preserved under `upstream/r1-manager/LICENSE`.

## Diagnostics APK

The launcher Activity currently provides three tests:

1. `Run automatic R1 Audio Probe`: records MIC, VOICE_RECOGNITION and VOICE_COMMUNICATION at 16 kHz stereo with software mono downmix and libfvad, then plays a 440 Hz tone while recording on MIC to verify the playback path and simultaneous capture/playback. Every stage has an 8 second hard timeout and the report is printed on screen.
2. `Test VAD + Opus JNI`: loads the ARMv7 native library and performs an Opus encode/decode smoke test.
3. `Start / Stop microphone`: opens `VOICE_COMMUNICATION` at 16 kHz mono PCM16 and runs the hybrid VAD endpointing path.

Pure PCM helpers (stereo downmix, test-tone generation) live in `PcmAudio` without Android imports and are covered by `gradle :app:testDebugUnitTest`, which also runs in CI before the APK build.

The recorder uses 30 ms frames, 300 ms pre-roll, minimum speech gating and about 800 ms trailing-silence endpointing. libfvad is primary; low-level capture can fall back to amplitude detection.

## Next integration boundary

Wake-word engine selection and a formal 20-session soak. Home Assistant, Xiaozhi
and custom agents remain protocol adapters rather than dependencies of the audio
engine.

Read [`../docs/06-source-reuse-inventory.md`](../docs/06-source-reuse-inventory.md), [`../docs/07-upstream-integration.md`](../docs/07-upstream-integration.md), and [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) for provenance and reuse rules.
