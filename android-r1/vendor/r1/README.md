# Phicomm R1 Vendor Integration

This directory contains project-owned R1-specific compatibility code and diagnostics.

Primary behavioral reference: https://github.com/sagan/r1-helper (GPL-2.0).

## License boundary

`r1-helper` source code is not copied here. Observable device behavior and documented package/hardware facts are reimplemented independently.

## Planned components

### PackageConflictProbe

Report whether known stock voice services are installed/running and whether they interfere with microphone or audio focus.

Initial candidates to observe include:

- `com.phicomm.speaker.player`
- `com.phicomm.speaker.device`

Other stock services may be reported, but the app does not disable them automatically.

### Safety guard

`com.phicomm.speaker.launcher` is treated as protected. Project tooling must refuse to hide/disable it by default.

### Reversible package control

Any future ADB helper that disables a vendor component must:

1. show the exact package being changed;
2. record prior state;
3. provide the matching restore command;
4. never run as part of normal app startup.

### CapabilityProbe

Probe and report, without assuming availability:

- microphone/audio routes
- buttons
- Bluetooth behavior
- root availability
- LED/sysfs nodes
- relevant vendor packages/services

### Optional LED

Historical R1 work documents `/sys/class/leds/multi_leds0/led_color`, with access constrained by Root/SELinux on stock systems. LED support therefore remains an optional capability and is not required for voice-satellite operation.
