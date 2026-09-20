#!/usr/bin/env bash
# Download sherpa-onnx Android AAR + Zipformer KWS models for the R1 perf probe.
# Does not modify the voice session path. Models stay outside the APK.
set -euo pipefail

AAR_ONLY=false
if [[ "${1:-}" == "--aar-only" ]]; then
  AAR_ONLY=true
  shift
fi
if [[ "$#" -ne 0 ]]; then
  echo "Usage: $0 [--aar-only]" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/app/libs"
MODEL_DIR="$ROOT/../tools/kws-probe/models"
AAR_VER="1.11.3"
AAR_NAME="sherpa-onnx-${AAR_VER}.aar"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${AAR_VER}/${AAR_NAME}"
AAR_SHA256="3c7a67f662f3ea0f8c99a177501c82ad7a4b975cf33020e9951580ce8fdc2988"
# Pin ≤1.11.x: newer AARs ship libonnxruntime.so with GNU_HASH only, which
# Android 5.1 / API 22 rejects (missing DT_HASH).
MODEL_NAME="sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/${MODEL_NAME}.tar.bz2"
MODEL_SHA256="68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

verify_sha256() {
  local file="$1" expected="$2" actual
  actual="$(sha256_file "$file")"
  if [[ "$actual" != "$expected" ]]; then
    echo "SHA-256 mismatch: $file" >&2
    echo "expected=$expected" >&2
    echo "actual=$actual" >&2
    exit 1
  fi
}

mkdir -p "$LIBS"

if [[ ! -f "$LIBS/$AAR_NAME" ]]; then
  echo "Downloading $AAR_NAME ..."
  curl -fL --retry 3 -o "$LIBS/$AAR_NAME.part" "$AAR_URL"
  mv "$LIBS/$AAR_NAME.part" "$LIBS/$AAR_NAME"
fi
verify_sha256 "$LIBS/$AAR_NAME" "$AAR_SHA256"
echo "AAR: $LIBS/$AAR_NAME ($(du -h "$LIBS/$AAR_NAME" | awk '{print $1}'))"

if [[ "$AAR_ONLY" == true ]]; then
  exit 0
fi

mkdir -p "$MODEL_DIR/wavs"

if [[ ! -f "$MODEL_DIR/tokens.txt" ]]; then
  TAR="${TMPDIR:-/tmp}/${MODEL_NAME}.tar.bz2"
  if [[ ! -f "$TAR" ]]; then
    echo "Downloading $MODEL_NAME ..."
    curl -fL --retry 3 -o "$TAR.part" "$MODEL_URL"
    mv "$TAR.part" "$TAR"
  fi
  verify_sha256 "$TAR" "$MODEL_SHA256"
  EXTRACT="$(mktemp -d "${TMPDIR:-/tmp}/r1-kws-model.XXXXXX")"
  trap 'rm -rf "$EXTRACT"' EXIT
  tar -xjf "$TAR" -C "$EXTRACT"
  SRC="$EXTRACT/$MODEL_NAME"
  # chunk-8 = ~160 ms; keep INT8 + FP32 encoder/joiner for the 2×2 matrix
  cp "$SRC"/encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx "$MODEL_DIR/"
  cp "$SRC"/encoder-epoch-13-avg-2-chunk-8-left-64.onnx "$MODEL_DIR/"
  cp "$SRC"/decoder-epoch-13-avg-2-chunk-8-left-64.onnx "$MODEL_DIR/"
  cp "$SRC"/joiner-epoch-13-avg-2-chunk-8-left-64.int8.onnx "$MODEL_DIR/"
  cp "$SRC"/joiner-epoch-13-avg-2-chunk-8-left-64.onnx "$MODEL_DIR/"
  cp "$SRC"/tokens.txt "$MODEL_DIR/"
  cp "$SRC"/test_wavs/keywords.txt "$MODEL_DIR/"
  cp "$SRC"/test_wavs/zh_3.wav "$SRC"/test_wavs/zh_4.wav "$SRC"/test_wavs/zh_5.wav "$MODEL_DIR/wavs/"
  cat > "$MODEL_DIR/PROVENANCE.txt" <<EOF
Upstream: https://github.com/k2-fsa/sherpa-onnx
AAR: ${AAR_NAME}
AAR-SHA256: ${AAR_SHA256}
Model: ${MODEL_NAME} (chunk-8)
Model-Archive-SHA256: ${MODEL_SHA256}
EOF
fi

echo "Models: $MODEL_DIR"
ls -lh "$MODEL_DIR" "$MODEL_DIR/wavs"

echo
echo "Push to R1 (after Wi-Fi ADB connect):"
echo "  adb push $MODEL_DIR/. /sdcard/kws-probe/"
echo "Then open Diagnostics → Run KWS Perf Probe"
