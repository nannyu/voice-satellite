#!/usr/bin/env bash
set -euo pipefail

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UPGRADE_SCRIPT="$(cd "$TEST_DIR/.." && pwd)/upgrade-r1-to-3448.command"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

FAKE_ADB="$TMP_DIR/adb"
cat > "$FAKE_ADB" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "connect" ]]; then
  printf 'connected to %s\n' "${2:-unknown}"
  exit 0
fi

if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi

case "${1:-}" in
  wait-for-device)
    exit 0
    ;;
  get-state)
    printf 'device\n'
    ;;
  shell)
    shift
    if [[ "${1:-}" == "getprop" ]]; then
      case "${2:-}" in
        ro.build.version.incremental)
          printf '%s\n' "${FAKE_R1_VERSION:?}"
          ;;
        ro.build.fingerprint)
          printf '%s\n' "${FAKE_R1_FINGERPRINT:?}"
          ;;
        *)
          printf '\n'
          ;;
      esac
    else
      printf 'unexpected fake adb shell command: %s\n' "$*" >&2
      exit 2
    fi
    ;;
  *)
    printf 'unexpected fake adb command: %s\n' "$*" >&2
    exit 2
    ;;
esac
EOF
chmod +x "$FAKE_ADB"

run_case() {
  local version="$1" fingerprint="$2"
  shift 2
  ADB="$FAKE_ADB" \
    FAKE_R1_VERSION="$version" \
    FAKE_R1_FINGERPRINT="$fingerprint" \
    "$UPGRADE_SCRIPT" "$@"
}

source_3415='Android/rk322x_echo/rk322x_echo:5.1.1/LMY49F/3415:user/release-keys'
target_3448='Android/rk322x_echo/rk322x_echo:5.1.1/LMY49F/3448:user/release-keys'

output="$(run_case 3415 "$source_3415" --preflight-only --no-adb-download)"
grep -Fq 'Preflight passed; no backup, push, reboot, or upgrade was performed' <<<"$output"

output="$(run_case 3448 "$target_3448" --no-adb-download)"
grep -Fq 'Device is already on verified firmware 3448; no changes made' <<<"$output"

if run_case 3331 'unexpected-fingerprint' --preflight-only --no-adb-download >"$TMP_DIR/unsupported.log" 2>&1; then
  printf 'unsupported version unexpectedly succeeded\n' >&2
  exit 1
fi
grep -Fq 'Only firmware 3415 is supported; found 3331' "$TMP_DIR/unsupported.log"

printf 'upgrade script tests passed\n'
