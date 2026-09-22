# KWS performance probe assets

Independent sherpa-onnx Keyword Spotting micro-benchmark for Phicomm R1.
See [`../../docs/10-kws-perf-probe.md`](../../docs/10-kws-perf-probe.md).

```bash
cd ../../android-r1
bash scripts/bootstrap-kws-probe.sh
adb push ../tools/kws-probe/models/. /sdcard/kws-probe/
```

`models/` is gitignored (large ONNX). The AAR lives at
`android-r1/app/libs/sherpa-onnx-1.11.3.aar` (also gitignored; bootstrap restores it).
The bootstrap verifies pinned SHA-256 digests for both the AAR and model archive.
Must stay on ≤1.11.x for Android 5.1 `DT_HASH` compatibility.
