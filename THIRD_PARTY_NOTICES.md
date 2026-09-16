# Third-Party Notices and Provenance Policy

This file tracks upstream projects and third-party components that may influence or be incorporated into `voice-satellite`.

Audit date: 2026-09-16.

## kitakeyos-dev/r1-manager

Repository: https://github.com/kitakeyos-dev/r1-manager

License: MIT License.

Planned use: selected Android/Java/JNI implementations may be adapted into `voice-satellite`, especially audio capture, libfvad integration, Opus encode/decode, and generic ExoPlayer playback. Any imported source must preserve the upstream copyright and MIT license notice and must record the exact upstream commit and path.

Important: the repository also contains or references third-party native libraries, wake-word engines, models, APKs and assets. The repository-level MIT license is not assumed to override the license of those third-party components. Each binary/model/native dependency must be audited separately before inclusion.

## sagan/r1-helper

Repository: https://github.com/sagan/r1-helper

License: GNU General Public License v2.0 (GPL-2.0).

Project policy: source code is not copied into the current `voice-satellite` core. We use the project as documentation of observable R1 behavior and hardware/firmware characteristics, then independently implement the required behavior. Examples include stock package conflicts, boot behavior, LED/sysfs observations and Android 5.1/ARMv7 compatibility.

If the licensing strategy of `voice-satellite` changes later, this boundary must be reviewed explicitly before any GPL-licensed code is imported.

## chiduciot/phicomm_r1-xiaozhi

Repository: https://github.com/chiduciot/phicomm_r1-xiaozhi

Parent repository: https://github.com/xuan2261/r1-xiaozhi

License status at audit date: GitHub repository metadata reports no recognized license file. The README states "MIT License - Free to use and modify", but no repository-root `LICENSE` file was detected during this audit.

Project policy: reference only until licensing provenance is clarified. No source code or binary assets are copied into `voice-satellite`. Its Android 5.1 lifecycle, Xiaozhi activation/WebSocket flows, reconnect behavior and configuration model may be used as behavioral references for an independent implementation.

## Snowboy / wake-word assets

Some examined R1 projects include Snowboy native libraries, resource files and wake-word models. Their provenance is not automatically covered by the enclosing repository's license. In particular, `r1-manager`'s wake-word wrapper states that its Snowboy assets came from `r1-helper`.

Project policy: no Snowboy binary, model or resource is imported until the exact asset license and redistribution conditions are verified. The Core API will support a replaceable `WakeWordEngine`, so the project is not structurally dependent on Snowboy.

## Opus and libfvad

`r1-manager` uses Opus and libfvad/WebRTC VAD through native integration. Before native binaries or source are imported, their own license notices and source provenance must be included here and in the distributable APK notices.

## Import checklist

For every copied or materially adapted source file:

1. Confirm that the upstream license permits the intended use and distribution.
2. Pin the upstream repository URL and commit SHA.
3. Record the original file path.
4. Preserve required copyright/license headers.
5. Document local changes.
6. Audit embedded models, `.so` files, APKs and generated artifacts separately.
7. Keep third-party implementation behind a project-owned interface so it can be replaced without changing the rest of the architecture.

Recommended provenance block in commits or adjacent documentation:

```text
Upstream-Repo: https://github.com/owner/repo
Upstream-Commit: <sha>
Upstream-Path: path/to/file
License: SPDX-Identifier
Adaptation: description of local changes
```
