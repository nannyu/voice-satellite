# 10. KWS Performance Probe (historical evidence)

Date: 2026-09-18 (measured on device 2026-09-19).

## Why this exists

Before wiring sherpa-onnx into `WakeWordEngine` / `VoiceSatelliteService`, measure whether
R1 (API 22 / ARMv7) can keep up with live audio. If realtime factor (RTF) ≥ 1, the
device falls behind and feels like it is “still processing the previous utterance”.

This probe is **deliberately not** part of the session / transport / Snowboy path.

## Matrix

Model: `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20`, **chunk-8** (~160 ms).

| # | Precision | ORT threads |
|---|-----------|-------------|
| 1 | INT8 encoder+joiner | 1 |
| 2 | INT8 | 2 |
| 3 | FP32 encoder+joiner | 1 |
| 4 | FP32 | 2 |

Workload: offline `zh_3/4/5.wav` from the upstream package (no mic, no WebSocket).

## Metrics

Per config: `init_ms`, `audio_sec`, `decode_wall_ms`, **`rtf`**, `hits`, native-heap RSS delta.

Verdict rules (printed at end of report):

- **GO_CANDIDATE**: all ok and RTF &lt; 0.50
- **MARGINAL**: 0.50 ≤ RTF &lt; 1.0
- **NO_GO**: any failure, or RTF ≥ 1.0

## Android 5.1 linker constraint

Stock sherpa-onnx **≥ 1.12** Android AARs ship `libonnxruntime.so` with **GNU_HASH only**.
Android 5.1’s linker requires SYSV `DT_HASH` and fails with:

`dlopen failed: empty/missing DT_HASH in "libonnxruntime.so"`

The probe therefore pins **sherpa-onnx 1.11.3** (last verified release that still
includes `DT_HASH` for `armeabi-v7a`). Treat newer AARs as incompatible with R1
until rebuilt with `-Wl,--hash-style=both`.

## How to run

```bash
cd voice-satellite/android-r1
bash scripts/bootstrap-kws-probe.sh
gradle :app:assembleDebug

adb push ../tools/kws-probe/models/. /sdcard/kws-probe/
# SatelliteActivity → Open Diagnostics → Run KWS Perf Probe

adb pull /sdcard/kws-probe/last-report.txt /tmp/kws-last-report.txt
```

## Measured on R1 (3448, 2026-09-19)

AAR: sherpa-onnx **1.11.3**. Audio: ~16.8 s (`zh_3/4/5.wav`). Hits=6 on all configs.

| Config | init_ms | rtf | rss_delta_kb | flag |
|--------|---------|-----|--------------|------|
| INT8 × 1t | 10475 | **0.954** | ~15 MB | THIN_HEADROOM |
| INT8 × 2t | 10266 | **0.785** | ~12 MB | THIN_HEADROOM |
| FP32 × 1t | 8741 | **0.994** | ~18 MB | THIN_HEADROOM |
| FP32 × 2t | 8669 | **0.789** | ~18 MB | THIN_HEADROOM |

**RESULT=MARGINAL** — best INT8×2t ≈ 0.79 RTF. Not enough headroom for always-on wake beside capture/playback; live audio will often feel like it is still catching up. Init also ~9–10 s.

## Integration gate

**Do not** wire sherpa-onnx into `WakeWordEngine` / session based on this matrix.

**2026-09-20 project decision:** stop further sherpa-onnx investment. The current
mainline is the stock Agent bridge (`docs/08-stock-agent-bridge.md`). This probe
remains as historical evidence only.

If open-vocab Chinese wake is revisited later (unlikely while stock frontend is primary):

1. Smaller / faster KWS model until RTF &lt; 0.50 on R1
2. Two-stage: cheap gate → sherpa only on candidate windows
3. Accept MARGINAL only for non-always-on experiments

The independent-client regression tree still contains Snowboy; the stock bridge
does not start either KWS engine.
