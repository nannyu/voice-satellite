# R1 Android Client

Android 5.1 / API 22 compatible Phicomm R1 client. The first executable slice now contains microphone capture, hybrid libfvad endpointing and Opus JNI encode/decode diagnostics.

## Current implementation

```text
app/src/main/java/io/nannyu/voicesatellite/r1/
  MainActivity.java                 diagnostics UI
  audio/AudioRecorder.java         16 kHz PCM16 + pre-roll + endpointing
  audio/VadDetector.java           libfvad JNI wrapper
  audio/OpusEncoder.java           Opus JNI encoder
  audio/OpusDecoder.java           Opus JNI decoder
app/src/main/cpp/
  voice_sat_native.cpp             project JNI bridge
  CMakeLists.txt
scripts/bootstrap-native.sh        pinned upstream dependency bootstrap
```

Target: `minSdk 22`, `targetSdk 22`, `armeabi-v7a` only. The debug APK is intentionally a hardware/codec diagnostic build, not yet the Home Assistant satellite service.

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

The launcher Activity currently provides two tests:

1. `Test VAD + Opus JNI`: loads the ARMv7 native library and performs an Opus encode/decode smoke test.
2. `Start / Stop microphone`: opens `VOICE_COMMUNICATION` at 16 kHz mono PCM16 and runs the hybrid VAD endpointing path.

The recorder uses 30 ms frames, 300 ms pre-roll, minimum speech gating and about 800 ms trailing-silence endpointing. libfvad is primary; low-level capture can fall back to amplitude detection.

## Next integration boundary

The next step is to move these proven primitives behind project-owned interfaces and add the persistent Satellite Service / transport layer. Home Assistant, Xiaozhi and custom agents remain protocol adapters rather than dependencies of the audio engine.

Read [`../docs/06-source-reuse-inventory.md`](../docs/06-source-reuse-inventory.md), [`../docs/07-upstream-integration.md`](../docs/07-upstream-integration.md), and [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) for provenance and reuse rules.
