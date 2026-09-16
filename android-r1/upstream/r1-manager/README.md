# r1-manager integration area

Upstream: https://github.com/kitakeyos-dev/r1-manager
License: MIT

This directory is reserved for source that is deliberately imported or adapted from `r1-manager` after provenance and dependency review.

## Planned first candidates

- Audio capture / frame dispatch
- libfvad JNI wrapper
- Opus encoder / decoder JNI wrapper
- conversational stream playback primitives
- generic ExoPlayer URL playback subset

## Not imported by default

- Snowboy models/native assets until separately licensed
- ZingMp3 integration
- MCP tools
- remote shell / app manager
- web UI
- Xiaozhi-specific session orchestration
- LED effects requiring privileged hardware access

## Required provenance

Every source file added below this directory must identify:

```text
Upstream-Repo
Upstream-Commit
Upstream-Path
License
Adaptation
```

Project code must access implementations in this directory through interfaces defined by `android-r1/core/api`. Do not let upstream service singletons become the public API of this project.
