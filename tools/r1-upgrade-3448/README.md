# Phicomm R1 3415 → 3448 one-click upgrade

This package reproduces the verified 2026-09-17 upgrade workflow for a stock
Phicomm R1: exact build checks, a file-level backup, local-LAN OTA hosting,
upgrade monitoring, post-upgrade validation, and trigger cleanup.

## Run

On macOS, double-click `upgrade-r1-to-3448.command`, or run:

```bash
./tools/r1-upgrade-3448/upgrade-r1-to-3448.command
```

Defaults:

- R1: `192.168.1.17:5555`
- source: exact stock build `3415`
- target: exact stock build `3448`
- local HTTP port: `18080`
- backup output: `tools/r1-upgrade-3448/backups/`

The script asks for `UPGRADE` immediately before it writes the trigger and
reboots. For unattended use, pass `--yes`. Run `--help` for all options.

Useful safe checks:

```bash
# Verify the bundled OTA and inspect the connected device without changing it.
./tools/r1-upgrade-3448/upgrade-r1-to-3448.command --preflight-only

# Use another device or another LAN address for this computer.
./tools/r1-upgrade-3448/upgrade-r1-to-3448.command \
  --ip 192.168.1.17 \
  --host-ip 192.168.1.18
```

If `adb` is absent, the script downloads current Platform Tools from Google's
official fixed download URL into the ignored `.cache/` directory. Set `ADB` to
an executable path or pass `--no-adb-download` to disable that behavior.

## Safety behavior

- Refuses versions other than exact 3415; exact 3448 is a successful no-op.
- Checks OTA byte size, MD5, SHA-256, ZIP integrity, and pre/post fingerprints.
- Backs up internal shared storage, installed Voice Satellite APK, device
  inventory, and all `/system` files readable by the stock ADB shell.
- Verifies the system archive on both the device and host before continuing.
- Tests a complete OTA download from the R1 before writing `otaprop.txt`.
- Keeps the HTTP server running if an upgrade was triggered but not confirmed.
- Removes `otaprop.txt` only after the 3448 fingerprint is verified.

The backup is not a raw eMMC/boot/recovery image. Stock firmware has no `su`,
and ADB cannot read raw block devices or several execute-only system files. A
true brick-recovery image requires a privileged Rockchip Loader/Maskrom path.

Keep the R1 powered and keep this computer on the same LAN until the script
prints `Upgrade verified`.

## Bundled firmware provenance

`firmware/incremental-ota-3415-3448.zip` is an unmodified, signed Phicomm
incremental OTA preserved by `pexcn/phicomm-r1-ota` at Git commit
`2ce76756bfd9495370a5e82e46474032779654dc`.

- Size: `8,134,939` bytes
- MD5: `ffb637b235077752af7306567eae8ef4`
- SHA-256: `581c1bdcb6313b9b9acf2ea545731816872298c97326005ab7a409f5efa89b88`

The firmware is vendor binary material, not project source code. Its
redistribution license has not been identified; review this before publishing
or redistributing the package outside a personal recovery archive.

## Verification

```bash
bash -n tools/r1-upgrade-3448/upgrade-r1-to-3448.command
./tools/r1-upgrade-3448/tests/test-upgrade-script.sh
```

The test covers the supported 3415 preflight, the idempotent 3448 no-op, and
rejection of an unsupported firmware version without touching a device.
