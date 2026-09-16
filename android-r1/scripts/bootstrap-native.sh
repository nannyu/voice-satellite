#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENDOR="$ROOT/app/src/main/cpp/vendor"
mkdir -p "$VENDOR"

clone_pinned() {
  local url="$1" ref="$2" dir="$3"
  if [ ! -d "$dir/.git" ]; then git clone --filter=blob:none "$url" "$dir"; fi
  git -C "$dir" fetch --depth 1 origin "$ref"
  git -C "$dir" checkout --detach FETCH_HEAD
}

clone_pinned https://github.com/xiph/opus.git v1.3.1 "$VENDOR/opus"
clone_pinned https://github.com/dpirch/libfvad.git 532ab666c20d3cfda38bca63abbb0f152706c369 "$VENDOR/libfvad"
echo "Native dependencies ready in $VENDOR"
