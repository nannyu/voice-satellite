# 11. Extracted Stock Frontend (superseded alternative)

Date: 2026-09-19. Superseded as mainline on 2026-09-20.

> Historical decision record only. The current direction is
> [`08-stock-agent-bridge.md`](08-stock-agent-bridge.md), which keeps the stock
> ASR/playback protocol and bridges final text to the server Agent. The inventory,
> device-state evidence and IPC/PCM constraints below remain useful if the bridge
> later proves an explicit device-control gap.

## Decision

**2026-09-19 时的备选决策：原厂本地唤醒＋原厂音频前端，自建 ASR／Agent／TTS。该优先级现已被第 08 号桥接方案取代。**

| 保留（目标） | 不保留 |
|--------------|--------|
| 原厂「小讯小讯」本地唤醒 | 原厂云识别 / 原厂问答 / 原厂业务云 |
| 原厂采集与音频处理（待证） | `r1-stock-bridge` 式「保留原厂 ASR、只换后端」 |
| 现有会话 / WebSocket / Gateway（S3 再接） | 继续投入 sherpa-onnx 正式唤醒 |
| 自建 ASR → Agent/HA → TTS → R1 播放 | 恢复 Unisound 后再开现有 `AudioRecorder` |

**Sherpa-onnx**：探针结论已是 `MARGINAL`（见 `docs/10-kws-perf-probe.md`）。**停止继续投入**；诊断代码可留档，不进会话。

**现有会话链路（Snowboy / Simulate Wake / echo）暂时不动。** 正式接线推迟到 S2 通过之后。

## Architecture target

```text
原厂引擎
  ├─ 本地「小讯小讯」唤醒
  └─ 原有采集与音频处理
              ↓
      StockFrontendAdapter
      唤醒事件＋有效 PCM＋状态控制
              ↓
      现有会话／WebSocket／Gateway
              ↓
      自建 ASR → Agent／HA → TTS
              ↓
          R1 现有播放路径
```

已验证事实（3448）：原厂 `com.phicomm.speaker.device` 运行时与独立 `AudioRecord` 冲突；`pm hide` 后录放探测通过。因此 **不能**「恢复原厂服务 + 现有 AudioRecorder 照常启动」。麦克风只有一个主人。

## Implementation fork: A vs B

| | A：连接正在运行的原厂服务 | B：独立宿主加载原厂引擎 |
|--|---------------------------|-------------------------|
| 麦克风 | 原厂服务 | 新的 `StockEngineHost` |
| 原厂包 | 保留运行 | 旧包避免抢占（可能仍 hide） |
| 必须先证明 | 可访问的事件 / 音频 / 控制通道 | 库+资源可在新宿主初始化、出音频、离线工作 |

**先证 A；没有真实接口再转 B。**
禁止正式方案：「听 logcat 再开独立录音」。不先走系统注入 / 刷机。

> 复用原厂引擎 ≠ 必须让原厂整个应用继续运行（B 路径）。

约束：不拆机、尽量免 Root。若验证发现必须原厂签名 / Root / 改 `/system`，**显式列出阻塞点**，不把项目悄悄升级成刷机工程。

## Known clues (reference only — must re-verify on 3448 APK)

1. **Wake event exists but is not a public broadcast.** Reference `NativeANTEngine` listener marks wake success (e.g. branch `3103`) then `return` without `fireASREvent()`. Downstream-only listening may miss wake. Event codes must be confirmed against **this device’s** APK.
2. **Audio APIs exist; processing stage unclear.** `IAudioSource.readData()` / `IAudioSourceAEC` are boundaries to chase — not proof that what we read is already AEC’d far-field mono.
3. **Stock Binder is not a cross-process API.** Direct `Binder` subclass returning engine objects only works same-process. Independent APK binding ≠ established path. Community rebuilds with shared UID / `su` are not a verified rootless product.

## Phases (S0–S3)

| Phase | Deliverable | Pass |
|-------|-------------|------|
| **S0 本机取证** | 3448 APK、库/资源清单、包与服务状态、音频占用 | 以本机文件为依据 |
| **S1 离线原厂基线** | 无外网冷启动、唤醒、错误与恢复 | 不依赖原厂云也能唤醒并待机 |
| **S2 独立前端探针** | 唤醒 → 有效 PCM → 本地验证 → 待机 | 首音节完整、格式明确、循环稳定 |
| **S3 接入现有链路** | 原厂前端替换采集入口 | 不抢麦、不重复会话、后端断仍本地唤醒 |

**S2 通过前不改正式会话接线。** 上行继续 PCM（与当前 `VoiceSatelliteService` 一致），不顺手混 Opus。播放中唤醒 / AEC 单独验收：先允许唤醒后压低或暂停播放。

## First acceptance point

在没有原厂云参与的情况下，完成：

**「小讯小讯」→ 有效音频 → 重新待机**

比「会亮灯的唤醒适配器」更有决定意义。

## Tools

**仓库内只读取证工具包：** `tools/stock-frontend/`
（`README.md` + `stock_frontend_inventory.py` + 无设备单测）

契约：不重启、不安装、不 hide/unhide、不清 log、不改系统、不开麦、不提权。

2026-09-19 的本机报告、原厂 APK 和其他可能含隐私/授权约束的产物仅保留在本地且被 `.gitignore` 排除，不进入公开仓库。

### S0 本机结果（3448，只读，未改包状态）

设备 `192.168.1.17:5555`，build incremental `3448`：

| 项 | 观测 |
|----|------|
| 包 | `com.phicomm.speaker.device` → `/system/app/Unisound` |
| 版本 | `versionName=V3.2.5.2-` / `versionCode=2` |
| 状态 | **`hidden=true`**；不在 `pm list packages`；进程未运行 |
| APK | `/system/app/Unisound/Unisound.apk`（24 936 387 B） |
| SHA-256 | `a0548242…841bd7`（完整值见 inventory `apk/SHA256SUMS`） |
| 对 A/B | **A 当前被 hide 挡住**。APK 内有 `IAudioSource` / `IAudioSourceAEC` / `WAKEUP_EVENT_*` / `assets/wakeup` → **下一步优先用本机 APK 做接口取证，B 更可能；短暂 unhide 做 A 探测需单独变更窗口** |

首轮 S0 输出：`voice-satellite/tools/stock-frontend/r1-stock-inventory-3448/`（含 `report.md`、`apk_contents.txt`、`apk_wake_audio_notes.md`）。**APK 勿提交公开仓库。**

说明：包 hide 后 `pm path` 常为空；权威脚本已增加只读的 `dumpsys codePath` 候选回退（仍不 unhide）。

### S0 → S1 门槛

1. jadx 对本机 APK：唤醒成功码（对照 `WAKEUP_EVENT_RECOGNITION_SUCCESS`，勿直接照搬社区 `3103`）、`IAudioSource` 调用链。
2. 不改会话接线；不恢复原厂包除非开「A 接口探测」变更窗口。
3. S1：无外网冷启动下「小讯小讯 → 待机」基线（若走 A 需先处理 hide；走 B 则在宿主内加载）。
