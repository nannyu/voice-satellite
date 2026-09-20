# Third-Party Notices and Provenance Policy

This file tracks upstream projects and third-party components that may influence or be incorporated into `voice-satellite`.

Audit date: 2026-09-17.

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

## Picovoice Porcupine

Previously evaluated (`ai.picovoice:porcupine-android`). **Rejected and removed** from
the app: requires AccessKey / account and does not meet Chinese + fully offline
product requirements. See `docs/09-wake-word.md`.

## Snowboy (Kitt-AI) — independent-client backup wake engine

Upstream: https://github.com/Kitt-AI/snowboy
Pinned commit: `c9ff036e2ef3f9c422a3b8c9a01361dbad7a9bd4`
License: Apache License 2.0 for the toolkit, libraries, `common.res`, and
`resources/models/snowboy.umdl`. Other hotword models have their own licenses.
Personal `.pmdl` models are user-generated.

Vendored into `android-r1/app` (hashes in `src/main/assets/snowboy/PROVENANCE.md`):

- `jniLibs/armeabi-v7a/libsnowboy-detect-android.so` — from official `SnowboyAlexaDemo.apk`
- `assets/snowboy/common.res`, `assets/snowboy/snowboy.umdl`
- `java/ai/kitt/snowboy/{SnowboyDetect,snowboyJNI}.java` — project-owned SWIG-compatible
  wrappers matching the official JNI `.so` (not copied from r1-helper)

Do **not** copy Snowboy wrappers, JNI, or models from `sagan/r1-helper` (GPL-2.0)
or other unverified R1 dumps. Chinese wake uses a user-supplied `*.pmdl`.

## sherpa-onnx (KWS probe only)

Upstream: https://github.com/k2-fsa/sherpa-onnx
AAR: `sherpa-onnx-1.11.3.aar` (bootstrap into `android-r1/app/libs/`, gitignored).
Pinned to ≤1.11.x because newer AARs lack SYSV `DT_HASH` and fail to `dlopen` on
Android 5.1 (`empty/missing DT_HASH in libonnxruntime.so`).
KWS model: `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` chunk-8 subset under
`tools/kws-probe/models/` (gitignored; see `docs/10-kws-perf-probe.md`).

Used **only** by the diagnostics `KwsPerfProbe` to record whether open-vocab KWS
is viable on R1. It is not part of the stock Agent bridge or the session wake path.
License: Apache-2.0 (upstream).

2026-09-19 device matrix: **RESULT=MARGINAL** (best INT8×2t RTF≈0.79). Formal
wake integration deferred.

## Opus and libfvad

`r1-manager` uses Opus and libfvad/WebRTC VAD through native integration. Before native binaries or source are imported, their own license notices and source provenance must be included here and in the distributable APK notices.

## Phicomm R1 stock firmware OTA

Archive source: https://github.com/pexcn/phicomm-r1-ota

Pinned archive commit: `2ce76756bfd9495370a5e82e46474032779654dc`.

Bundled artifact: `tools/r1-upgrade-3448/firmware/incremental-ota-3415-3448.zip`.

Integrity: 8,134,939 bytes; MD5 `ffb637b235077752af7306567eae8ef4`; SHA-256 `581c1bdcb6313b9b9acf2ea545731816872298c97326005ab7a409f5efa89b88`.

License status: vendor firmware binary with no identified redistribution license. It is not project source code and is retained for device maintenance/recovery. Review redistribution rights before publishing it in a release or third-party package.

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
