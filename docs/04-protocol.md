# 04. Voice Satellite Protocol Draft

Status: design draft, not stable API.

The protocol exists so legacy clients can remain simple while adapters evolve independently.

## Transport

Initial candidate: WebSocket.

Control messages are JSON. Audio frames should use binary WebSocket frames to avoid base64 overhead.

## Handshake

Client -> server:

```json
{
  "type": "hello",
  "protocol": 1,
  "device_id": "r1-living-room",
  "device": {
    "model": "phicomm-r1",
    "client_version": "0.1.0",
    "platform": "android-5.1"
  },
  "capabilities": [
    "audio.pcm16",
    "wake.local",
    "media.url",
    "media.duck"
  ]
}
```

Server -> client:

```json
{
  "type": "hello.ack",
  "protocol": 1,
  "session_id": "connection-session-id",
  "heartbeat_seconds": 30
}
```

## Voice session

```mermaid
sequenceDiagram
  participant R as R1
  participant G as Gateway
  R->>G: voice.start
  R->>G: binary audio frames
  R->>G: voice.end
  G-->>R: state.processing
  G-->>R: response.start
  G-->>R: binary response audio
  G-->>R: response.end
```

Example start:

```json
{
  "type": "voice.start",
  "session_id": "uuid",
  "trigger": "wake_word",
  "audio": {
    "codec": "pcm_s16le",
    "sample_rate": 16000,
    "channels": 1
  }
}
```

End:

```json
{
  "type": "voice.end",
  "session_id": "uuid",
  "reason": "vad_end"
}
```

## State messages

```json
{"type":"state","state":"idle"}
{"type":"state","state":"listening"}
{"type":"state","state":"processing"}
{"type":"state","state":"responding"}
```

## Media commands

Server -> client:

```json
{
  "type": "media.play",
  "request_id": "uuid",
  "media": {
    "url": "https://example/media/track.m3u8",
    "kind": "music",
    "title": "Example Track",
    "position_ms": 0
  }
}
```

Other commands:

```text
media.pause
media.resume
media.stop
media.seek
media.set_volume
```

Client reports:

```json
{
  "type": "media.state",
  "state": "playing",
  "position_ms": 42812,
  "duration_ms": 220000
}
```

## Announcement command

```json
{
  "type": "announce",
  "request_id": "uuid",
  "url": "https://server/tts/announcement.wav",
  "behavior": "duck_and_resume"
}
```

## Errors

```json
{
  "type": "error",
  "code": "AUDIO_CAPTURE_FAILED",
  "message": "AudioRecord initialization failed",
  "recoverable": true
}
```

## Versioning

- Integer protocol major version in handshake.
- New optional message fields are backward compatible.
- Capability negotiation gates optional behavior.
- Unknown message types must not crash the client.

## Authentication

The protocol must support per-device credentials and rotation. Exact scheme is deferred until deployment mode is selected. Permanent secrets must not be committed to the repository.

## Open questions

- PCM vs Opus as default uplink after R1 profiling.
- Whether response audio should be binary streamed or represented as a URL for some backends.
- Direct HA mode vs Gateway mode for v0.1.
- Discovery/pairing mechanism on a trusted LAN.
