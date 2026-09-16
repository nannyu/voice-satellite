# R1 Android Client

Android 5.1-compatible Phicomm R1 satellite client.

The client is backend-neutral. Home Assistant, Xiaozhi and custom agents connect through protocol adapters; microphone/VAD/codec/media capabilities sit behind project-owned Core interfaces.

## Planned tree

```text
app/
  lifecycle/
  diagnostics/
core/
  api/
  audio/
  wakeword/
  vad/
  session/
media/
transport/
  websocket/
protocol/
  homeassistant/
  custom/
  xiaozhi/
vendor/
  r1/
upstream/
  r1-manager/
```

## Source integration rules

- `upstream/r1-manager/` is the audited landing zone for selected MIT-licensed implementation code adapted from `kitakeyos-dev/r1-manager`.
- `vendor/r1/` is independently implemented from observable R1 behavior and documentation, including knowledge from GPL-2.0 `sagan/r1-helper`.
- `protocol/xiaozhi/` is independently implemented from protocol behavior; source from `chiduciot/phicomm_r1-xiaozhi` is not copied while its license provenance remains ambiguous.
- Wake-word binaries/models are a separate dependency audit and are not automatically covered by an enclosing repository license.

Read [`../docs/06-source-reuse-inventory.md`](../docs/06-source-reuse-inventory.md), [`../docs/07-upstream-integration.md`](../docs/07-upstream-integration.md), and [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) before importing source.

Implementation starts with Phase 0 hardware validation and the smallest set of reusable modules: audio capture, VAD, optional Opus, conversational playback and generic media playback.
