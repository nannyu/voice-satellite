#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FIRMWARE_DIR="$SCRIPT_DIR/firmware"
FIRMWARE="$FIRMWARE_DIR/incremental-ota-3415-3448.zip"

DEVICE_IP="192.168.1.17"
ADB_PORT="5555"
HTTP_PORT="18080"
HOST_IP=""
BACKUP_ROOT="$SCRIPT_DIR/backups"
SKIP_BACKUP=0
ASSUME_YES=0
PREFLIGHT_ONLY=0
ALLOW_ADB_DOWNLOAD=1
WAIT_SECONDS=900

EXPECTED_SOURCE_VERSION="3415"
EXPECTED_TARGET_VERSION="3448"
EXPECTED_SOURCE_FINGERPRINT="Android/rk322x_echo/rk322x_echo:5.1.1/LMY49F/3415:user/release-keys"
EXPECTED_TARGET_FINGERPRINT="Android/rk322x_echo/rk322x_echo:5.1.1/LMY49F/3448:user/release-keys"
EXPECTED_OTA_BYTES="8134939"
EXPECTED_OTA_MD5="ffb637b235077752af7306567eae8ef4"
EXPECTED_OTA_SHA256="581c1bdcb6313b9b9acf2ea545731816872298c97326005ab7a409f5efa89b88"

ADB_BIN=""
SERIAL=""
RUN_DIR=""
HTTP_PID=""
UPGRADE_TRIGGERED=0
UPGRADE_CONFIRMED=0
OTAPROP_WRITTEN=0

log() { printf '[R1] %s\n' "$*"; }
warn() { printf '[R1] WARNING: %s\n' "$*" >&2; }
die() { printf '[R1] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Upgrade a stock Phicomm R1 from firmware 3415 to 3448.

Usage:
  ./upgrade-r1-to-3448.command [options]

Options:
  --ip ADDRESS          R1 address (default: 192.168.1.17)
  --adb-port PORT       Wi-Fi ADB port (default: 5555)
  --http-port PORT      Local OTA HTTP port (default: 18080)
  --host-ip ADDRESS     Mac/Linux LAN address visible to the R1
  --backup-dir PATH     Backup parent directory
  --skip-backup         Skip the file-level 3415 backup
  --yes                 Do not prompt before the reboot that starts the update
  --preflight-only      Verify assets/device and stop before backup or changes
  --no-adb-download     Fail instead of downloading official Platform Tools
  --wait-seconds N      Maximum upgrade wait (default: 900)
  -h, --help            Show this help

The default workflow makes a verified file-level backup, serves the bundled
signed OTA over the LAN, triggers one reboot, waits for the device, verifies
3448, removes /sdcard/otaprop.txt, and stops the temporary HTTP server.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ip) DEVICE_IP="${2:?missing value for --ip}"; shift 2 ;;
    --adb-port) ADB_PORT="${2:?missing value for --adb-port}"; shift 2 ;;
    --http-port) HTTP_PORT="${2:?missing value for --http-port}"; shift 2 ;;
    --host-ip) HOST_IP="${2:?missing value for --host-ip}"; shift 2 ;;
    --backup-dir) BACKUP_ROOT="${2:?missing value for --backup-dir}"; shift 2 ;;
    --skip-backup) SKIP_BACKUP=1; shift ;;
    --yes) ASSUME_YES=1; shift ;;
    --preflight-only) PREFLIGHT_ONLY=1; shift ;;
    --no-adb-download) ALLOW_ADB_DOWNLOAD=0; shift ;;
    --wait-seconds) WAIT_SECONDS="${2:?missing value for --wait-seconds}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

[[ "$ADB_PORT" =~ ^[0-9]+$ ]] || die "Invalid ADB port: $ADB_PORT"
[[ "$HTTP_PORT" =~ ^[0-9]+$ ]] || die "Invalid HTTP port: $HTTP_PORT"
[[ "$WAIT_SECONDS" =~ ^[0-9]+$ ]] || die "Invalid wait timeout: $WAIT_SECONDS"
SERIAL="$DEVICE_IP:$ADB_PORT"

cleanup() {
  if [[ "$OTAPROP_WRITTEN" -eq 1 && "$UPGRADE_TRIGGERED" -eq 0 ]]; then
    adb_device shell rm -f /sdcard/otaprop.txt >/dev/null 2>&1 || true
  fi
  if [[ -n "$HTTP_PID" ]] && kill -0 "$HTTP_PID" 2>/dev/null; then
    if [[ "$UPGRADE_TRIGGERED" -eq 1 && "$UPGRADE_CONFIRMED" -ne 1 ]]; then
      warn "Upgrade was triggered but not confirmed. Leaving HTTP server PID $HTTP_PID running."
      warn "After the R1 finishes or is recovered, stop it with: kill $HTTP_PID"
    else
      kill "$HTTP_PID" 2>/dev/null || true
      wait "$HTTP_PID" 2>/dev/null || true
    fi
  fi
}
trap cleanup EXIT INT TERM

need_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found: $1"
}

md5_file() {
  if command -v md5 >/dev/null 2>&1; then
    md5 -q "$1"
  elif command -v md5sum >/dev/null 2>&1; then
    md5sum "$1" | awk '{print $1}'
  else
    die "Neither md5 nor md5sum is available"
  fi
}

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    die "Neither shasum nor sha256sum is available"
  fi
}

download_adb() {
  [[ "$ALLOW_ADB_DOWNLOAD" -eq 1 ]] || die "adb was not found and automatic download is disabled"
  need_command curl
  need_command unzip

  local os archive_url cache_dir zip_file tmp_dir
  os="$(uname -s)"
  case "$os" in
    Darwin) archive_url="https://dl.google.com/android/repository/platform-tools-latest-darwin.zip" ;;
    Linux) archive_url="https://dl.google.com/android/repository/platform-tools-latest-linux.zip" ;;
    *) die "Automatic adb installation is supported only on macOS and Linux" ;;
  esac

  cache_dir="$SCRIPT_DIR/.cache"
  zip_file="$cache_dir/platform-tools.zip"
  tmp_dir="$cache_dir/platform-tools.tmp"
  mkdir -p "$cache_dir"
  rm -rf "$tmp_dir"
  mkdir -p "$tmp_dir"

  log "adb not found; downloading official Android Platform Tools"
  curl --fail --location --output "$zip_file" "$archive_url"
  unzip -q -o "$zip_file" -d "$tmp_dir"
  [[ -x "$tmp_dir/platform-tools/adb" ]] || die "Downloaded archive does not contain adb"
  rm -rf "$cache_dir/platform-tools"
  mv "$tmp_dir/platform-tools" "$cache_dir/platform-tools"
  rm -rf "$tmp_dir"
  ADB_BIN="$cache_dir/platform-tools/adb"
}

find_adb() {
  local candidate
  if [[ -n "${ADB:-}" && -x "${ADB}" ]]; then
    ADB_BIN="$ADB"
    return
  fi
  if command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
    return
  fi
  for candidate in \
    "$SCRIPT_DIR/platform-tools/adb" \
    "$SCRIPT_DIR/.cache/platform-tools/adb" \
    "$REPO_ROOT/../.tools/platform-tools/adb" \
    "$HOME/Library/Android/sdk/platform-tools/adb"; do
    if [[ -x "$candidate" ]]; then
      ADB_BIN="$candidate"
      return
    fi
  done
  download_adb
}

adb_device() {
  "$ADB_BIN" -s "$SERIAL" "$@"
}

connect_device() {
  log "Connecting to $SERIAL"
  "$ADB_BIN" connect "$SERIAL" >/dev/null
  "$ADB_BIN" -s "$SERIAL" wait-for-device
  local state
  state="$("$ADB_BIN" -s "$SERIAL" get-state 2>/dev/null || true)"
  [[ "$state" == "device" ]] || die "ADB device is not ready: ${state:-unknown}"
}

verify_ota_asset() {
  need_command unzip
  [[ -f "$FIRMWARE" ]] || die "Bundled OTA not found: $FIRMWARE"

  local bytes md5_value sha256_value metadata
  bytes="$(wc -c < "$FIRMWARE" | tr -d '[:space:]')"
  md5_value="$(md5_file "$FIRMWARE")"
  sha256_value="$(sha256_file "$FIRMWARE")"
  [[ "$bytes" == "$EXPECTED_OTA_BYTES" ]] || die "OTA size mismatch: $bytes"
  [[ "$md5_value" == "$EXPECTED_OTA_MD5" ]] || die "OTA MD5 mismatch: $md5_value"
  [[ "$sha256_value" == "$EXPECTED_OTA_SHA256" ]] || die "OTA SHA-256 mismatch: $sha256_value"
  unzip -tq "$FIRMWARE" >/dev/null || die "OTA ZIP integrity check failed"
  metadata="$(unzip -p "$FIRMWARE" META-INF/com/android/metadata)"
  grep -Fq "pre-build=$EXPECTED_SOURCE_FINGERPRINT" <<<"$metadata" || die "OTA source fingerprint mismatch"
  grep -Fq "post-build=$EXPECTED_TARGET_FINGERPRINT" <<<"$metadata" || die "OTA target fingerprint mismatch"
  log "OTA verified: $EXPECTED_OTA_BYTES bytes, MD5 $EXPECTED_OTA_MD5"
}

read_property() {
  adb_device shell getprop "$1" | tr -d '\r'
}

write_checksum_manifest() {
  local root="$1" file rel hash
  : > "$root/SHA256SUMS"
  while IFS= read -r file; do
    rel="${file#"$root"/}"
    hash="$(sha256_file "$file")"
    printf '%s  %s\n' "$hash" "$rel" >> "$root/SHA256SUMS"
  done < <(find "$root" -type f ! -name SHA256SUMS | sort)
}

backup_3415() {
  local timestamp remote_archive remote_errors local_archive device_md5 local_md5 apk_path free_value free_kb
  timestamp="$(date '+%Y%m%d-%H%M%S')"
  RUN_DIR="$BACKUP_ROOT/r1-${DEVICE_IP}-${EXPECTED_SOURCE_VERSION}-${timestamp}"
  mkdir -p "$RUN_DIR/device" "$RUN_DIR/ota"

  log "Saving 3415 file-level backup to $RUN_DIR"
  adb_device shell getprop > "$RUN_DIR/device/getprop.txt"
  adb_device shell pm list packages -f > "$RUN_DIR/device/packages.txt"
  adb_device shell 'id; echo ===DF===; df; echo ===MOUNT===; mount; echo ===PARTITIONS===; cat /proc/partitions' > "$RUN_DIR/device/device-inventory.txt"

  log "Copying internal shared storage"
  adb_device pull /mnt/internal_sd "$RUN_DIR/device/internal_sd" >/dev/null

  apk_path="$(adb_device shell pm path io.nannyu.voicesatellite.r1 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
  if [[ -n "$apk_path" ]]; then
    adb_device pull "$apk_path" "$RUN_DIR/device/voice-satellite-r1-base.apk" >/dev/null
  fi

  free_value="$(adb_device shell 'df /mnt/internal_sd' | tr -d '\r' | awk 'NR==2 {print $4}')"
  case "$free_value" in
    *G) free_kb="$(awk -v value="${free_value%G}" 'BEGIN {printf "%.0f", value * 1024 * 1024}')" ;;
    *M) free_kb="$(awk -v value="${free_value%M}" 'BEGIN {printf "%.0f", value * 1024}')" ;;
    *K) free_kb="${free_value%K}" ;;
    ''|*[!0-9]*) die "Could not parse free internal storage: ${free_value:-empty}" ;;
    *) free_kb="$free_value" ;;
  esac
  [[ "$free_kb" =~ ^[0-9]+$ ]] || die "Could not determine free internal storage"
  (( free_kb >= 350000 )) || die "At least 350 MB free internal storage is required; found ${free_kb} KB"

  remote_archive="/mnt/internal_sd/r1-system-readable-${EXPECTED_SOURCE_VERSION}-${timestamp}.tar.gz"
  remote_errors="/mnt/internal_sd/r1-system-readable-${EXPECTED_SOURCE_VERSION}-${timestamp}.errors.txt"
  local_archive="$RUN_DIR/device/r1-system-readable-${EXPECTED_SOURCE_VERSION}.tar.gz"

  log "Creating readable /system archive on the R1 (permission-denied entries are recorded)"
  adb_device shell "/system/bin/busybox tar -czf '$remote_archive' -C / system 2>'$remote_errors' || true"
  adb_device pull "$remote_archive" "$local_archive" >/dev/null
  adb_device pull "$remote_errors" "$RUN_DIR/device/system-unreadable-files.txt" >/dev/null || true
  device_md5="$(adb_device shell "/system/bin/busybox md5sum '$remote_archive'" | tr -d '\r' | awk '{print $1}')"
  local_md5="$(md5_file "$local_archive")"
  [[ -n "$device_md5" && "$device_md5" == "$local_md5" ]] || die "System archive MD5 mismatch"
  gzip -t "$local_archive" || die "System archive gzip validation failed"
  tar -tzf "$local_archive" >/dev/null || die "System archive tar validation failed"
  tar -xOzf "$local_archive" system/build.prop | grep -Fq 'ro.build.version.incremental=3415' || die "Backup archive does not contain build 3415"
  adb_device shell rm -f "$remote_archive" "$remote_errors" || true

  cp "$FIRMWARE" "$RUN_DIR/ota/"
  cat > "$RUN_DIR/README.md" <<EOF
# Phicomm R1 3415 pre-upgrade backup

- Device: \`$SERIAL\`
- Created: \`$(date -Iseconds 2>/dev/null || date)\`
- Source fingerprint: \`$EXPECTED_SOURCE_FINGERPRINT\`
- Readable system archive device/local MD5: \`$local_md5\`
- OTA MD5: \`$EXPECTED_OTA_MD5\`
- OTA SHA-256: \`$EXPECTED_OTA_SHA256\`

This is a verified file-level backup, not a raw eMMC/boot/recovery image. Stock
ADB cannot read raw block devices or several execute-only system files. The
excluded entries are recorded in \`device/system-unreadable-files.txt\`.
EOF
  write_checksum_manifest "$RUN_DIR"
  log "Backup verified"
}

detect_host_ip() {
  [[ -n "$HOST_IP" ]] && return
  case "$(uname -s)" in
    Darwin)
      local iface
      iface="$(route -n get "$DEVICE_IP" 2>/dev/null | awk '/interface:/{print $2; exit}')"
      [[ -n "$iface" ]] && HOST_IP="$(ipconfig getifaddr "$iface" 2>/dev/null || true)"
      ;;
    Linux)
      HOST_IP="$(ip route get "$DEVICE_IP" 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
      ;;
  esac
  [[ -n "$HOST_IP" ]] || die "Could not detect host LAN IP; use --host-ip"
}

start_http_server() {
  need_command python3
  need_command curl
  detect_host_ip
  local log_file
  if [[ -z "$RUN_DIR" ]]; then
    RUN_DIR="$BACKUP_ROOT/r1-${DEVICE_IP}-upgrade-$(date '+%Y%m%d-%H%M%S')"
    mkdir -p "$RUN_DIR"
  fi
  log_file="$RUN_DIR/http-server.log"
  log "Starting local OTA server at http://$HOST_IP:$HTTP_PORT"
  python3 -m http.server "$HTTP_PORT" --bind "$HOST_IP" --directory "$FIRMWARE_DIR" >"$log_file" 2>&1 &
  HTTP_PID=$!
  sleep 1
  kill -0 "$HTTP_PID" 2>/dev/null || die "HTTP server failed; see $log_file"
  curl --fail --silent --show-error --output /dev/null "http://$HOST_IP:$HTTP_PORT/$(basename "$FIRMWARE")"
  adb_device shell "/system/bin/busybox wget -O /dev/null 'http://$HOST_IP:$HTTP_PORT/$(basename "$FIRMWARE")'" >/dev/null
  log "R1 successfully downloaded the complete OTA test payload"
}

stop_http_server() {
  if [[ -n "$HTTP_PID" ]] && kill -0 "$HTTP_PID" 2>/dev/null; then
    kill "$HTTP_PID" 2>/dev/null || true
    wait "$HTTP_PID" 2>/dev/null || true
  fi
  HTTP_PID=""
}

write_otaprop() {
  local otaprop="$RUN_DIR/ota-3415-3448-local.txt"
  local remote_tmp="/sdcard/otaprop.txt.voice-satellite-tmp"
  cat > "$otaprop" <<EOF
# Generated by voice-satellite/tools/r1-upgrade-3448
ota_debug1_md5=
ota_init_upgrade_flag=1
update_cfg_version=1.1
ota_fw_bin_md5=$EXPECTED_OTA_MD5
ota_enviro_model=0
ota_debug1_ssid=
ota_byRouterMD5=
ota_pre_fw_ver=1.0.0.$EXPECTED_SOURCE_VERSION
ota_debug_url=http://$HOST_IP:$HTTP_PORT/$(basename "$FIRMWARE")
ota_debug1_psw=
ota_fw_bin_url=http://$HOST_IP:$HTTP_PORT/$(basename "$FIRMWARE")
ota_update_mode=initial_update
ota_mqtt_ifbyBG=0
ota_bootthenpublish=0
ota_debug1_url=
ota_cur_fw_ver=1.0.0.$EXPECTED_TARGET_VERSION
ota_byRouter=2
ota_fw_bin_size=$EXPECTED_OTA_BYTES
ota_debug_md5=$EXPECTED_OTA_MD5
EOF
  adb_device push "$otaprop" "$remote_tmp" >/dev/null
  local local_md5 remote_md5
  local_md5="$(md5_file "$otaprop")"
  remote_md5="$(adb_device shell "/system/bin/busybox md5sum '$remote_tmp'" | tr -d '\r' | awk '{print $1}')"
  [[ "$local_md5" == "$remote_md5" ]] || die "otaprop.txt verification failed"
  adb_device shell mv "$remote_tmp" /sdcard/otaprop.txt
  OTAPROP_WRITTEN=1
}

confirm_upgrade() {
  [[ "$ASSUME_YES" -eq 1 ]] && return
  [[ -t 0 ]] || die "Refusing to reboot without an interactive terminal; use --yes"
  printf '\nThe verified backup/preflight is complete. Keep the R1 powered and this computer online.\n'
  printf 'Type UPGRADE to reboot and install firmware 3448: '
  local answer
  read -r answer
  [[ "$answer" == "UPGRADE" ]] || die "Upgrade cancelled"
}

port_open() {
  if command -v nc >/dev/null 2>&1; then
    nc -z -w 1 "$DEVICE_IP" "$ADB_PORT" >/dev/null 2>&1
  else
    (echo > "/dev/tcp/$DEVICE_IP/$ADB_PORT") >/dev/null 2>&1
  fi
}

wait_for_3448() {
  local started now elapsed version
  started="$(date +%s)"
  log "Waiting up to $WAIT_SECONDS seconds for firmware 3448"
  while true; do
    now="$(date +%s)"
    elapsed=$((now - started))
    (( elapsed < WAIT_SECONDS )) || die "Timed out waiting for R1; HTTP server remains available"
    if port_open; then
      "$ADB_BIN" disconnect "$SERIAL" >/dev/null 2>&1 || true
      if "$ADB_BIN" connect "$SERIAL" >/dev/null 2>&1; then
        version="$(read_property ro.build.version.incremental 2>/dev/null || true)"
        if [[ "$version" == "$EXPECTED_TARGET_VERSION" ]]; then
          return
        fi
      fi
    fi
    sleep 5
  done
}

post_upgrade_validation() {
  local fingerprint validation_file
  fingerprint="$(read_property ro.build.fingerprint)"
  [[ "$fingerprint" == "$EXPECTED_TARGET_FINGERPRINT" ]] || die "Unexpected target fingerprint: $fingerprint"

  mkdir -p "$RUN_DIR/post-upgrade"
  validation_file="$RUN_DIR/post-upgrade/validation.txt"
  adb_device shell 'echo ===BUILD===; getprop ro.build.version.incremental; getprop ro.build.display.id; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.fingerprint; echo ===PACKAGES===; pm path com.phicomm.speaker.otaservice; pm path com.phicomm.speaker.launcher; pm path io.nannyu.voicesatellite.r1; echo ===STORAGE===; df /system /data /mnt/internal_sd; echo ===UPTIME===; uptime' > "$validation_file"
  adb_device pull /sdcard/otaprop.txt "$RUN_DIR/post-upgrade/otaprop-after-upgrade.txt" >/dev/null || true
  adb_device shell rm -f /sdcard/otaprop.txt || true
  OTAPROP_WRITTEN=0
  stop_http_server
  write_checksum_manifest "$RUN_DIR"
  UPGRADE_CONFIRMED=1
  log "Upgrade verified: $fingerprint"
  log "Results: $RUN_DIR"
}

main() {
  need_command awk
  need_command grep
  need_command gzip
  need_command tar
  verify_ota_asset
  find_adb
  log "Using adb: $ADB_BIN"
  connect_device

  local version fingerprint
  version="$(read_property ro.build.version.incremental)"
  fingerprint="$(read_property ro.build.fingerprint)"
  log "Device reports build $version"

  if [[ "$version" == "$EXPECTED_TARGET_VERSION" && "$fingerprint" == "$EXPECTED_TARGET_FINGERPRINT" ]]; then
    log "Device is already on verified firmware 3448; no changes made"
    exit 0
  fi
  [[ "$version" == "$EXPECTED_SOURCE_VERSION" ]] || die "Only firmware 3415 is supported; found $version"
  [[ "$fingerprint" == "$EXPECTED_SOURCE_FINGERPRINT" ]] || die "3415 fingerprint mismatch: $fingerprint"

  if [[ "$PREFLIGHT_ONLY" -eq 1 ]]; then
    log "Preflight passed; no backup, push, reboot, or upgrade was performed"
    exit 0
  fi

  if [[ "$SKIP_BACKUP" -eq 0 ]]; then
    backup_3415
  else
    warn "Skipping the file-level backup by explicit request"
  fi

  start_http_server
  confirm_upgrade
  write_otaprop
  UPGRADE_TRIGGERED=1
  log "Rebooting R1 to start the signed incremental OTA"
  adb_device reboot
  wait_for_3448
  post_upgrade_validation
}

main "$@"
