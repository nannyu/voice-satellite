# Xiaozhi Protocol Adapter

This module will provide an independently implemented Xiaozhi-compatible backend adapter.

Behavioral references:

- https://github.com/chiduciot/phicomm_r1-xiaozhi
- https://github.com/xuan2261/r1-xiaozhi
- https://github.com/kitakeyos-dev/r1-manager

## License boundary

No source from `phicomm_r1-xiaozhi` is copied here while its repository license provenance remains ambiguous. Its README describes the project as MIT, but GitHub currently reports no recognized repository license file. Protocol behavior may be independently reimplemented.

## Planned responsibilities

- endpoint profile: cloud / self-hosted
- device activation and credential lifecycle
- authenticated WebSocket session
- hello/capability negotiation
- voice-session message mapping
- reconnect/backoff
- server response -> Core state mapping

This adapter must consume the generic Core audio/session APIs. It must not own `AudioRecord`, wake-word detection, VAD or media playback.

## Security rules

- never trust all TLS certificates
- never log full access tokens
- credentials remain replaceable/revocable
- transport errors return to the shared SessionController recovery path
