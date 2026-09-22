#!/usr/bin/env python3
"""S0 read-only inventory for Phicomm R1 stock voice frontend (Unisound).

Collects build props, package/service state, audio occupancy hints, and optional
APK pull + SHA-256. Supports stock-bridge P0 evidence (docs/08) and the
superseded extracted-frontend investigation (docs/11).

Safety contract (hard):
  - No reboot, install, uninstall, hide, unhide, enable, disable
  - No logcat -c / clear
  - No writes under /system or package data
  - No microphone / AudioRecord start
  - No root / su attempts
  - Each shell command has a timeout; failures are recorded, not retried with privilege

Usage:
  python3 stock_frontend_inventory.py --out ./r1-stock-inventory-3448 --pull-apk
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

STOCK_PKG = "com.phicomm.speaker.device"
DEFAULT_TIMEOUT = 25
# Paths we never pass to adb shell as writable targets in this tool.
FORBIDDEN_WRITE_PREFIXES = (
    "/system",
    "/vendor",
    "/data/data/",
    "/data/user/",
)


class InventoryError(RuntimeError):
    pass


def which_adb(explicit: Optional[str]) -> str:
    if explicit:
        path = Path(explicit)
        if path.is_file():
            return str(path.resolve())
        found = shutil.which(explicit)
        if found:
            return str(Path(found).resolve())
        raise InventoryError("adb not found: " + explicit)
    env = os.environ.get("ADB")
    if env:
        path = Path(env)
        if path.is_file():
            return str(path.resolve())
        found = shutil.which(env)
        if found:
            return str(Path(found).resolve())
    found = shutil.which("adb")
    if not found:
        raise InventoryError("adb not in PATH; pass --adb or set ADB")
    return found


def list_devices(adb: str, timeout: float = DEFAULT_TIMEOUT) -> List[str]:
    proc = subprocess.run(
        [adb, "devices"],
        capture_output=True,
        text=True,
        timeout=timeout,
        check=False,
    )
    serials = []
    for line in (proc.stdout or "").splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serials.append(parts[0])
    return serials


def pick_serial(adb: str, requested: Optional[str]) -> str:
    devices = list_devices(adb)
    if requested:
        if requested not in devices:
            raise InventoryError(
                "serial {!r} not online (device). online={!r}".format(
                    requested, devices
                )
            )
        return requested
    if len(devices) == 0:
        raise InventoryError("no authorized adb devices")
    if len(devices) > 1:
        raise InventoryError(
            "multiple devices {}; pass --serial".format(devices)
        )
    return devices[0]


def adb_cmd(
    adb: str,
    serial: str,
    args: Sequence[str],
    timeout: float,
) -> Tuple[int, str, str, float]:
    """Run adb -s SERIAL ... ; return (code, stdout, stderr, elapsed_s)."""
    cmd = [adb, "-s", serial] + list(args)
    t0 = time.monotonic()
    try:
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
        elapsed = time.monotonic() - t0
        return proc.returncode, proc.stdout or "", proc.stderr or "", elapsed
    except subprocess.TimeoutExpired as exc:
        elapsed = time.monotonic() - t0
        out = (exc.stdout or b"")
        err = (exc.stderr or b"")
        if isinstance(out, bytes):
            out = out.decode("utf-8", "replace")
        if isinstance(err, bytes):
            err = err.decode("utf-8", "replace")
        return 124, out, err + "\nTIMEOUT after {:.1f}s".format(elapsed), elapsed


def shell(
    adb: str,
    serial: str,
    command: str,
    timeout: float = DEFAULT_TIMEOUT,
) -> Tuple[int, str, str, float]:
    # Pass the remote command as ONE argv after `shell`. Splitting into
    # `sh -c` + unquoted tokens makes R1's adbd drop arguments (getprop then
    # dumps the entire property DB).
    return adb_cmd(adb, serial, ["shell", command], timeout)


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def record_run(
    raw_dir: Path,
    name: str,
    code: int,
    stdout: str,
    stderr: str,
    elapsed: float,
    argv_note: str,
) -> Path:
    safe = re.sub(r"[^a-zA-Z0-9._-]+", "_", name)
    path = raw_dir / (safe + ".txt")
    body = []
    body.append("name={}".format(name))
    body.append("argv={}".format(argv_note))
    body.append("exit_code={}".format(code))
    body.append("elapsed_s={:.3f}".format(elapsed))
    body.append("--- stdout ---")
    body.append(stdout.rstrip("\n"))
    body.append("--- stderr ---")
    body.append(stderr.rstrip("\n"))
    body.append("")
    write_text(path, "\n".join(body))
    return path


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def parse_package_path(pm_path_out: str) -> Optional[str]:
    """Extract package APK path from `pm path` output."""
    for line in pm_path_out.splitlines():
        line = line.strip()
        if line.startswith("package:"):
            return line[len("package:") :].strip() or None
    return None


def parse_dumpsys_code_path(dumpsys_out: str) -> Optional[str]:
    """Extract codePath from dumpsys package (works when package is hidden)."""
    m = re.search(r"(?m)^\s*codePath=(\S+)\s*$", dumpsys_out)
    if not m:
        return None
    code_path = m.group(1).rstrip("/")
    # Prefer explicit APK file under the codePath directory when known.
    return code_path


def resolve_apk_candidates(code_path: str) -> List[str]:
    """Likely APK file paths given a PackageManager codePath."""
    if code_path.endswith(".apk"):
        return [code_path]
    base = code_path.rstrip("/")
    name = base.rsplit("/", 1)[-1]
    return [
        "{}/base.apk".format(base),
        "{}/{}.apk".format(base, name),
        "{}/Unisound.apk".format(base),
    ]


def is_allowed_pull_path(remote: str) -> bool:
    return (
        remote.startswith("/data/app/")
        or remote.startswith("/system/app/")
        or remote.startswith("/system/priv-app/")
        or remote.startswith("/vendor/app/")
    )


def ensure_fresh_outdir(out: Path) -> None:
    if out.exists():
        raise InventoryError(
            "refusing to overwrite existing --out directory: {}".format(out)
        )
    out.mkdir(parents=True, exist_ok=False)


def summarize_audio_flinger(text: str) -> dict:
    """Lightweight heuristics — not a full dumpsys parser."""
    lower = text.lower()
    return {
        "mentions_deadlock": ("deadlock" in lower) or ("dead locked" in lower),
        "mentions_unisound_or_device_pkg": (
            STOCK_PKG in text
            or "unisound" in lower
            or "speaker.device" in text
        ),
        "input_track_lines": len(
            re.findall(r"(?i)Input (thread|track)|Active.*[Ii]nput", text)
        ),
        "bytes": len(text.encode("utf-8", "replace")),
    }


def build_report_md(
    serial: str,
    props: dict,
    pkg_state: dict,
    audio_summary: dict,
    apk_info: Optional[dict],
    failures: List[str],
) -> str:
    lines = []
    lines.append("# R1 stock frontend inventory (S0)")
    lines.append("")
    lines.append("Generated by `stock_frontend_inventory.py` (read-only).")
    lines.append("")
    lines.append("## Device")
    lines.append("")
    lines.append("- serial: `{}`".format(serial))
    for k in (
        "ro.build.display.id",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.build.version.incremental",
        "ro.product.model",
        "ro.product.device",
        "ro.product.cpu.abi",
        "sys.boot_completed",
    ):
        lines.append("- {}: `{}`".format(k, props.get(k, "?")))
    lines.append("")
    lines.append("## Package `{}`".format(STOCK_PKG))
    lines.append("")
    for k, v in pkg_state.items():
        lines.append("- {}: `{}`".format(k, v))
    lines.append("")
    lines.append("## AudioFlinger heuristics")
    lines.append("")
    for k, v in audio_summary.items():
        lines.append("- {}: `{}`".format(k, v))
    lines.append("")
    if apk_info:
        lines.append("## Pulled APK")
        lines.append("")
        for k, v in apk_info.items():
            lines.append("- {}: `{}`".format(k, v))
        lines.append("")
        lines.append(
            "APK is for local analysis only — do not commit to a public repo."
        )
        lines.append("")
    if failures:
        lines.append("## Failures / timeouts")
        lines.append("")
        for f in failures:
            lines.append("- {}".format(f))
        lines.append("")
    lines.append("## Next (manual)")
    lines.append("")
    lines.append(
        "1. Confirm whether stock package is running or hidden "
        "(do **not** change state in S0)."
    )
    lines.append(
        "2. Decompile pulled APK locally; verify wake event codes and "
        "`IAudioSource` / AEC branch against **this** build."
    )
    lines.append(
        "3. Continue with stock-bridge P0/P1 in docs/08-stock-agent-bridge.md; "
        "consult docs/11-stock-frontend.md only if an explicit device-control "
        "gap requires the superseded extracted-frontend investigation."
    )
    lines.append("")
    return "\n".join(lines)


def run_inventory(args: argparse.Namespace) -> int:
    adb = which_adb(args.adb)
    serial = pick_serial(adb, args.serial)
    out = Path(args.out).expanduser().resolve()
    ensure_fresh_outdir(out)
    raw_dir = out / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)

    failures: List[str] = []
    props = {}

    prop_keys = [
        "ro.build.display.id",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.build.version.incremental",
        "ro.product.model",
        "ro.product.device",
        "ro.product.cpu.abi",
        "sys.boot_completed",
    ]
    for key in prop_keys:
        # Prefer multi-arg getprop so the key cannot be dropped by the shell.
        code, so, se, elapsed = adb_cmd(
            adb, serial, ["shell", "getprop", key], timeout=args.timeout
        )
        record_run(
            raw_dir,
            "getprop_" + key.replace(".", "_"),
            code,
            so,
            se,
            elapsed,
            "shell getprop " + key,
        )
        if code != 0:
            failures.append("getprop {} exit={}".format(key, code))
            props[key] = ""
        else:
            # If the device ignored the key and dumped everything, keep a marker.
            val = so.strip()
            if val.startswith("[") and "]: [" in val and val.count("\n") > 3:
                failures.append(
                    "getprop {} returned full dump; check adb quoting".format(key)
                )
                # Best-effort extract
                m = re.search(
                    r"\[{}\]:\s*\[([^\]]*)\]".format(re.escape(key)), so
                )
                props[key] = m.group(1) if m else ""
            else:
                props[key] = val

    pkg_state = {
        "package": STOCK_PKG,
    }

    # R1 toolbox often lacks `head`; never pipe dumpsys through it.
    commands = [
        ("pm_path", "pm path {}".format(STOCK_PKG)),
        ("dumpsys_package", "dumpsys package {}".format(STOCK_PKG)),
        (
            "pm_list_packages_visible",
            "pm list packages | grep phicomm.speaker || true",
        ),
        (
            "pm_list_packages_uninstalled",
            "pm list packages -u | grep phicomm.speaker || true",
        ),
        (
            "ps_speaker_device",
            "ps | grep phicomm.speaker.device | grep -v grep || true",
        ),
        (
            "dumpsys_activity_services",
            "dumpsys activity services {}".format(STOCK_PKG),
        ),
        ("dumpsys_audio_flinger", "dumpsys media.audio_flinger"),
        ("ls_system_app_unisound", "ls -la /system/app/Unisound/ || true"),
        (
            "find_system_app_unisound",
            "find /system/app/Unisound -type f 2>/dev/null || true",
        ),
        (
            "ls_data_app",
            "ls -la /data/app/ 2>/dev/null || true",
        ),
    ]

    cmd_outputs = {}
    for name, cmdline in commands:
        # Prefer wrapping pm via sh /system/bin/pm when bare pm is blocked:
        if cmdline.startswith("pm "):
            cmdline = "sh /system/bin/pm " + cmdline[3:]
        timeout = args.timeout
        if name in ("dumpsys_audio_flinger", "dumpsys_package"):
            timeout = max(timeout, 45)
        code, so, se, elapsed = shell(adb, serial, cmdline, timeout=timeout)
        record_run(raw_dir, name, code, so, se, elapsed, cmdline)
        cmd_outputs[name] = (code, so, se)
        if code == 124:
            failures.append("{} TIMEOUT".format(name))
        elif code != 0 and "|| true" not in cmdline:
            failures.append("{} exit={}".format(name, code))

    code, so, _ = cmd_outputs.get("pm_path", (1, "", ""))
    apk_remote = parse_package_path(so) if code == 0 else None
    pkg_state["pm_path_exit"] = code

    code_ds, so_ds, _ = cmd_outputs.get("dumpsys_package", (1, "", ""))
    code_path = parse_dumpsys_code_path(so_ds) if code_ds == 0 else None
    pkg_state["code_path"] = code_path or ""

    if not apk_remote and code_path:
        # When hidden, `pm path` is often empty; resolve APK under codePath.
        for candidate in resolve_apk_candidates(code_path):
            c_code, c_so, c_se, c_elapsed = shell(
                adb,
                serial,
                "ls -la {}".format(candidate),
                timeout=args.timeout,
            )
            record_run(
                raw_dir,
                "probe_apk_" + candidate.replace("/", "_"),
                c_code,
                c_so,
                c_se,
                c_elapsed,
                "ls -la " + candidate,
            )
            if c_code == 0 and "No such file" not in (c_so + c_se):
                apk_remote = candidate
                break

    pkg_state["apk_path"] = apk_remote or ""

    code, so, _ = cmd_outputs.get("ps_speaker_device", (1, "", ""))
    pkg_state["process_lines"] = len(
        [ln for ln in so.splitlines() if ln.strip()]
    )
    pkg_state["process_running_heuristic"] = pkg_state["process_lines"] > 0

    # Visible vs -u list: if only in -u, treat as hidden/uninstalled from launcher.
    _, so_vis, _ = cmd_outputs.get("pm_list_packages_visible", (0, "", ""))
    _, so_un, _ = cmd_outputs.get("pm_list_packages_uninstalled", (0, "", ""))
    in_visible = STOCK_PKG in so_vis
    in_uninstalled_list = STOCK_PKG in so_un
    pkg_state["in_pm_list_packages"] = in_visible
    pkg_state["in_pm_list_packages_u"] = in_uninstalled_list

    hidden = None
    version_name = None
    version_code = None
    if code_ds == 0:
        m = re.search(r"(?i)hidden=(\w+)", so_ds)
        if m:
            hidden = m.group(1)
        m = re.search(r"(?m)^\s*versionName=(\S+)\s*$", so_ds)
        if m:
            version_name = m.group(1)
        m = re.search(r"(?m)^\s*versionCode=(\d+)\b", so_ds)
        if m:
            version_code = m.group(1)
    if hidden is None and in_uninstalled_list and not in_visible:
        hidden = "likely_true_not_in_pm_list"
    pkg_state["hidden_heuristic"] = hidden if hidden is not None else "unknown"
    if version_name:
        pkg_state["versionName"] = version_name
    if version_code:
        pkg_state["versionCode"] = version_code

    # A vs B fork hint for the report (read-only observation).
    if pkg_state["process_running_heuristic"]:
        pkg_state["fork_hint"] = "A_possible_process_running"
    elif hidden in ("true", "likely_true_not_in_pm_list"):
        pkg_state["fork_hint"] = (
            "A_blocked_while_hidden__prefer_prove_interfaces_or_B"
        )
    else:
        pkg_state["fork_hint"] = "A_needs_running_service_check"

    code, so, _ = cmd_outputs.get("dumpsys_audio_flinger", (1, "", ""))
    audio_summary = summarize_audio_flinger(so if code == 0 else "")
    if code != 0:
        audio_summary["dumpsys_exit"] = code

    apk_info = None
    if args.pull_apk:
        if not apk_remote:
            failures.append(
                "pull-apk skipped: no apk path (pm path empty and "
                "codePath probe failed)"
            )
        elif not is_allowed_pull_path(apk_remote):
            failures.append(
                "pull-apk refused: unexpected path {!r}".format(apk_remote)
            )
        else:
            apk_dir = out / "apk"
            apk_dir.mkdir(parents=True, exist_ok=True)
            local = apk_dir / "com.phicomm.speaker.device.apk"
            t0 = time.monotonic()
            code, so, se, elapsed = adb_cmd(
                adb,
                serial,
                ["pull", apk_remote, str(local)],
                timeout=max(args.timeout, 180),
            )
            record_run(
                raw_dir,
                "adb_pull_apk",
                code,
                so,
                se,
                elapsed,
                "pull {} -> {}".format(apk_remote, local),
            )
            if code != 0 or not local.is_file():
                failures.append("adb pull apk failed exit={}".format(code))
            else:
                digest = sha256_file(local)
                write_text(
                    apk_dir / "SHA256SUMS",
                    "{}  {}\n".format(digest, local.name),
                )
                apk_info = {
                    "remote_path": apk_remote,
                    "local_path": str(local),
                    "bytes": local.stat().st_size,
                    "sha256": digest,
                    "pull_elapsed_s": "{:.3f}".format(time.monotonic() - t0),
                }

    report = build_report_md(
        serial, props, pkg_state, audio_summary, apk_info, failures
    )
    write_text(out / "report.md", report)
    write_text(
        out / "META.txt",
        "tool=stock_frontend_inventory.py\n"
        "stock_pkg={}\n"
        "serial={}\n"
        "created_unix={}\n"
        "pull_apk={}\n".format(
            STOCK_PKG, serial, int(time.time()), bool(args.pull_apk)
        ),
    )

    print(report)
    print("Wrote {}".format(out / "report.md"))
    return 1 if failures and args.strict else 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Read-only S0 inventory for R1 stock voice frontend"
    )
    p.add_argument(
        "--out",
        required=True,
        help="new output directory (must not already exist)",
    )
    p.add_argument("--serial", default=None, help="adb serial")
    p.add_argument("--adb", default=None, help="path to adb binary")
    p.add_argument(
        "--pull-apk",
        action="store_true",
        help="pull stock Unisound APK via pm path + adb pull",
    )
    p.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT,
        help="per-command timeout seconds (default {})".format(DEFAULT_TIMEOUT),
    )
    p.add_argument(
        "--strict",
        action="store_true",
        help="exit 1 if any command failed/timed out",
    )
    return p


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return run_inventory(args)
    except InventoryError as exc:
        print("ERROR: {}".format(exc), file=sys.stderr)
        return 2
    except subprocess.TimeoutExpired as exc:
        print("ERROR: timeout: {}".format(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
