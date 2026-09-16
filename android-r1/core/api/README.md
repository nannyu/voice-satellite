# R1 Core API

These are project-owned interfaces. Protocol adapters and upstream-derived implementations meet here instead of depending on one another directly.

Planned contracts:

```text
AudioInput
VoiceActivityDetector
AudioCodec
WakeWordEngine
ConversationalAudioOutput
MediaPlayer
DeviceCapabilities
```

## Dependency rule

```text
SessionController
      |
      v
   Core API
   /     \
  v       v
R1 implementations   protocol adapters consume Core events
```

No Home Assistant or Xiaozhi message type belongs in this layer.

## Initial implementation mapping

- `AudioInput`: adapted from the useful subset of `r1-manager` AudioRecorder.
- `VoiceActivityDetector`: libfvad-backed implementation derived from audited `r1-manager` code.
- `AudioCodec`: optional Opus implementation derived from audited `r1-manager` code; PCM remains supported.
- `WakeWordEngine`: replaceable; no Snowboy dependency until native/model licenses are separately verified.
- `ConversationalAudioOutput`: low-latency PCM output for TTS/response streams.
- `MediaPlayer`: ExoPlayer-backed generic URL player adapted from the useful subset of `r1-manager`.
- `DeviceCapabilities`: populated by R1-specific clean-room probes.

See `docs/06-source-reuse-inventory.md`, `docs/07-upstream-integration.md` and `THIRD_PARTY_NOTICES.md` before importing code.
