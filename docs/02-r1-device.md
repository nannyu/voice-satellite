# 02. Phicomm R1 Device Design

## Constraints

Target baseline:

- Phicomm R1
- Android 5.1 class runtime
- ARMv7 / RK3229 class SoC
- 512 MB class memory budget
- stock firmware where possible
- installation over Wi-Fi ADB
- no physical disassembly
- no Root requirement for core voice functionality

Exact hardware/audio behavior must be measured on the target unit rather than inferred from community reports.

## Android modules

```text
android-r1/
  app/
  core/
    audio/
    wakeword/
    vad/
    session/
    diagnostics/
  media/
  hardware/
  protocol/
    api/
    homeassistant/
    custom/
    xiaozhi/
```

## AudioEngine

Responsibilities:

- enumerate/test Android audio sources
- initialize `AudioRecord`
- capture stable mono PCM
- timestamp frames
- expose frame stream to VAD/session layer
- own `AudioTrack` for conversational playback
- manage Android audio focus
- coordinate with MediaEngine

Do not assume the default microphone source is the best R1 far-field path. The hardware-validation stage must test available sources and sample rates.

## WakeWordEngine

Interface:

```text
start()
stop()
onWakeWord(id, confidence, timestamp)
```

Implementation is replaceable. v0.1 should choose the smallest proven ARMv7-compatible engine after profiling memory and CPU usage.

## VAD

VAD determines utterance boundaries after wake-up. Required parameters:

- speech-start threshold
- speech-end silence duration
- maximum utterance duration
- pre-roll buffer so initial phonemes are not lost

## SessionController

Owns the state machine and prevents audio components from independently fighting over the speaker like badly socialized processes.

Events include:

```text
WakeDetected
ButtonPressed
SpeechStarted
SpeechEnded
BackendConnected
BackendResponseStarted
BackendResponseEnded
MediaStarted
MediaStopped
NetworkLost
Error
```

## MediaEngine

Use ExoPlayer-compatible components available for the minimum Android target.

Required v0.1 commands:

```text
play(url, position?)
pause()
resume()
stop()
seek(position)
setVolume(level)
duck(level)
restoreVolume()
```

Later:

- queue / playlist
- audiobook chapter metadata
- playback progress reporting
- HLS validation
- repeat / shuffle

## Boot and lifecycle

Target behavior:

1. Device boots.
2. Satellite service starts.
3. Network is acquired.
4. Backend reconnects with exponential backoff.
5. Wake-word listening begins.
6. App remains usable without a visible UI.

A small settings/diagnostic Activity is still useful for initial configuration and testing.

## Diagnostics screen

Show at least:

- app version
- Android/build information
- local IP
- backend connection state
- selected microphone source
- sample rate / buffer size
- current satellite state
- audio focus state
- free memory
- last wake event
- last session latency
- last error

## Root boundary

Core v0.1 must not require Root. Features that turn out to depend on privileged sysfs/vendor APIs belong behind optional capability interfaces, especially LED effects and vendor-specific hardware control.

## Stock-service coexistence

Do not initially uninstall vendor packages. During hardware validation:

1. Observe conflicts.
2. Identify the minimum conflicting service/package.
3. Disable only when necessary and reversible through ADB.
4. Document the exact restore command.

The project should be recoverable without reflashing the device.

## Stock boot music (verified 3448, 2026-09-17)

The audible power-on jingle is NOT Rockchip `bootaudio` (`/system/media/bootaudio.zip`
contains only `audio_conf.txt` mixer settings, no audio payload). It is played by
`com.phicomm.speaker.device` (Unisound.apk): on `BOOT_COMPLETED`,
`.Receiver.MessageReceiver` starts `.ui.MainActivity`, which plays the APK resource
`res/raw/bootloader_completed.mp3` (~5 s) through `UniMediaPlayer.playBeepSound`.
Baseline logcat: `E/MessageReceiver: action boot complted` → `D/UniMediaPlayer:
---->>playBeepSound` → `onCompletion`.

Disabled without root via package hide (community-proven, reversible):

```sh
adb shell "sh /system/bin/pm hide com.phicomm.speaker.device"
adb shell reboot
# restore:
adb shell "sh /system/bin/pm unhide com.phicomm.speaker.device"
```

Verified on 3448: after reboot, no `playBeepSound`/`MessageReceiver` boot logs,
no `com.phicomm.speaker.device` process, `sys.boot_completed=1`, no FATAL/ANR.

Side effects of hiding this package: stock 小讯 wake-word/voice answers and the
top-button Bluetooth pairing gesture stop working. Volume keys (Launcher) and
other stock services are unaffected.

Device ADB quirks found along the way:

- Stock adbd (`/sbin/adbd`) runs a command whitelist (`create_subproc not support
  <cmd>`): bare `pm` is rejected and drops the ADB connection; invoking it as
  `sh /system/bin/pm ...` works.
- Shell lacks `CHANGE_COMPONENT_ENABLED_STATE`, so `pm disable <pkg>/<component>`
  fails with SecurityException; `pm hide` on the package works.
- `adb root` does not elevate (Rockchip build ignores `service.adb.root`;
  `sys.rkadb.root` is not settable). SELinux is permissive, so `setprop` of
  non-`ro.` properties works, but `/system` and ramdisk stay read-only to shell.
- ADB over TCP intermittently returns `error: closed`; a fresh reconnect per
  command is the reliable pattern.
