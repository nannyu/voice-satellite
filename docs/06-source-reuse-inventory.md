# 06. Source Reuse Inventory

本文档记录 `voice-satellite` 对三个现有 R1 项目的复用边界、目标模块、许可证处理和验证要求。目标不是把三个工程搅成一锅，而是把真正有价值的部分拆出来，放进稳定的接口后面。

审计日期：2026-09-16。

## Reuse modes

- **PORT**：允许移植/改造上游代码。必须保留上游版权与许可证声明，并记录原文件路径和上游 commit。
- **CLEAN_ROOM**：只参考外部行为、设备事实、协议语义和测试结果，由本项目独立实现，不复制源码。
- **REFERENCE_ONLY**：仅用于理解设计/协议/兼容性问题，不复制源码或资产。
- **DEFER**：当前不进入 R1 v0.1 客户端，避免把服务器职责塞进 512 MB 的终端。

## Upstream summary

| Upstream | License status | Project policy |
|---|---|---|
| `kitakeyos-dev/r1-manager` | MIT | 可 PORT，但第三方 native 库、模型和资产必须单独审计 |
| `sagan/r1-helper` | GPL-2.0 | CLEAN_ROOM，只复用行为与硬件知识，不复制源码进核心模块 |
| `chiduciot/phicomm_r1-xiaozhi` | GitHub 元数据未识别许可证；README 声明 MIT，但仓库根目录未发现 LICENSE 文件 | 保守按 REFERENCE_ONLY 处理，许可证明确前不复制源码 |

## r1-manager

Upstream: https://github.com/kitakeyos-dev/r1-manager

这是当前最适合作为实现基础的项目。它明确面向 Phicomm R1 / Android 5.x，并已经把语音与媒体链路拆成相对独立的 Java/JNI 组件。

| Upstream component | Existing behavior | voice-satellite target | Mode | Required adaptation |
|---|---|---|---|---|
| `server/voicebot/AudioRecorder.java` | `AudioRecord` 16 kHz mono；raw listener；VAD endpointing；pre-roll；低电平回退 | `android-r1/core/audio` | PORT | 去掉 `AppLog`/`ThreadManager` 耦合；抽象 `AudioSource`；把阈值放入配置；实机测试 `VOICE_COMMUNICATION` 与 `MIC` |
| `server/voicebot/VadDetector.java` | libfvad/WebRTC VAD JNI wrapper；10/20/30 ms frame | `android-r1/core/vad` | PORT | JNI 与 native lib 独立打包；增加 capability/fallback；记录 native 依赖来源许可证 |
| `server/voicebot/OpusEncoder.java` | 16-bit PCM -> Opus JNI | `android-r1/core/audio/codec` | PORT | 从 Xiaozhi 固定帧长中解耦；协议层协商采样率/帧长 |
| `server/voicebot/OpusDecoder.java` | Opus -> PCM JNI | `android-r1/core/audio/codec` | PORT | 同上；支持无 Opus 时 PCM fallback |
| `server/manager/XiaozhiAudioEngine.java` | 16 kHz capture、30 ms VAD、60 ms Opus、24 kHz playback、wake/conversation 模式切换 | `android-r1/core/session` 的设计参考 | PORT selectively | 不整体搬类；拆成 AudioInput/VAD/Codec/SessionController 四个接口，实现不依赖 Xiaozhi |
| `server/manager/SnowboyHotwordDetector.java` | Snowboy wake-word wrapper | `android-r1/core/wakeword` | REFERENCE_ONLY until asset audit | 上游代码中明确引用来自 `r1-helper` 的 Snowboy assets；模型与 native library 许可证必须先厘清，不能因为外层 MIT 就自动变 MIT |
| `server/service/ExoPlayerService.java` | ExoPlayer；queue/history/shuffle/repeat/speed/seek/volume | `android-r1/media` | PORT selectively | 去除 ZingMp3、LED、Song model 强耦合；只保留通用 URL player、queue、position、duck/restore |
| WebSocket voice client | 实时 Xiaozhi voice transport | `android-r1/transport/websocket` | PORT selectively | 只复用连接、重连、binary frame 技术，不让 Xiaozhi message schema 泄漏进 Core |
| `McpManager` / MCP tools | 设备端 MCP 控制 | server/gateway | DEFER | MCP 属于 Agent/Gateway 层，v0.1 不在 R1 上运行工具系统 |
| `LedManager` / LED effects | R1 LED 控制和可视化 | `android-r1/hardware` | REFERENCE_ONLY initially | Core 不依赖 Root；LED 作为 optional capability，Phase 0 后再判断 |
| Web UI / app management / shell | R1 管理界面 | diagnostics tooling | DEFER | 只借鉴诊断需求，不把远程 shell 等高风险功能放进 MVP |

### Audio code facts worth keeping

当前 `AudioRecorder` 使用 16 kHz/16-bit mono、30 ms VAD frame、约 300 ms pre-roll、约 800 ms silence endpointing，并在 libfvad 对 R1 低电平麦克风效果不佳时回退到 amplitude VAD。这个机制非常贴合 R1，但这些数值只是上游经验值，必须通过我们的实机录音重新标定。

`XiaozhiAudioEngine` 还把两帧 30 ms PCM 合成为 60 ms Opus 上行，并采用 24 kHz 下行播放。我们保留“分层和流式”做法，不把这些 Xiaozhi 特定参数写死到通用 Core。

## phicomm_r1-xiaozhi

Upstream: https://github.com/chiduciot/phicomm_r1-xiaozhi
Parent: https://github.com/xuan2261/r1-xiaozhi

该项目对 Android 5.1 生命周期、Xiaozhi WebSocket、activation/reconnect 和 R1 后台运行很有参考价值，但实现质量与安全策略需要筛选，不能整包移植。

| Upstream component/behavior | voice-satellite target | Mode | What we keep |
|---|---|---|---|
| `VoiceRecognitionService` | lifecycle + audio validation | REFERENCE_ONLY | API 22 权限处理、`START_STICKY`、音频线程优先级、录音错误恢复的测试场景 |
| `XiaozhiConnectionService` | `protocol/xiaozhi` | REFERENCE_ONLY | Bearer token、activation、hello、WebSocket lifecycle、retry 的协议行为 |
| BootReceiver / auto start | `app/lifecycle` | REFERENCE_ONLY | 开机自启需求与恢复流程 |
| AudioPlaybackService | `core/audio/output` | REFERENCE_ONLY | 服务化播放生命周期测试用例 |
| config / cloud+self-hosted | `protocol/xiaozhi` | REFERENCE_ONLY | backend profile 模型，不复制硬编码配置 |
| LEDControlService | `hardware/led` | REFERENCE_ONLY | optional Root capability 的状态语义 |
| energy-based wake detection | none | REJECT | 能量阈值不能称为真正 wake-word detection，只作为调试音量/VAD工具 |
| trust-all TLS helper | none | REJECT | 不进入项目；TLS 验证不可通过信任所有证书解决 |

## r1-helper

Upstream: https://github.com/sagan/r1-helper

它最大的价值不是 Alexa 代码，而是 R1 原厂固件的行为资料。

| Known behavior | voice-satellite target | Mode | Integration |
|---|---|---|---|
| Stock Android 5.1 / ARMv7 app compatibility | platform baseline | CLEAN_ROOM | 固化到兼容矩阵和 CI target |
| `com.phicomm.speaker.player` / `com.phicomm.speaker.device` 是主要语音冲突源 | `android-r1/vendor/r1` | CLEAN_ROOM | Phase 0 capability probe 检测，不默认禁用 |
| 不应禁用 `com.phicomm.speaker.launcher` | safety guard | CLEAN_ROOM | ADB 工具脚本加入 deny-list，避免破坏设备基本启动/配网能力 |
| package hide/restore workflow | `tools/adb` | CLEAN_ROOM | 编写可逆脚本，所有 disable 都必须同时有 restore 命令 |
| `/sys/class/leds/multi_leds0/led_color` LED 行为 | `hardware/led` | CLEAN_ROOM | 仅作为 capability probe；Root/SELinux 边界明确隔离 |
| Snowboy on API 22/ARMv7 | wakeword research | REFERENCE_ONLY | 证明历史可行性，不复制 GPLv2 wrapper/asset |
| boot auto-start | lifecycle tests | CLEAN_ROOM | 用 Android 标准机制独立实现 |

## Integration priority

### P0: directly useful for MVP

1. `r1-manager` AudioRecorder concepts/code, behind `AudioInput`.
2. `r1-manager` libfvad wrapper/native implementation, behind `VoiceActivityDetector`.
3. `r1-manager` Opus encoder/decoder, behind `AudioCodec`.
4. `r1-manager` ExoPlayer URL playback subset, behind `MediaPlayer`.
5. Independent SessionController combining wake, VAD, transport and media ducking.

### P1: after voice round-trip works

1. Real wake-word engine after model/native-license audit.
2. Xiaozhi adapter independently implemented from protocol behavior.
3. R1 vendor conflict probe and reversible package controls.
4. optional LED capability.

### Explicitly not imported into R1 Core

- MCP server/tools
- ZingMp3 provider
- remote shell/file manager
- vendor-specific cloud logic
- Alexa integration
- Xiaozhi TLS bypass code
- GPLv2 `r1-helper` source
- unlicensed/unclear-license source or binary assets

## Provenance rule

When a PORT item is actually imported, the commit must include:

```text
Upstream-Repo: <url>
Upstream-Commit: <sha>
Upstream-Path: <path>
License: <SPDX>
Adaptation: <summary>
```

The exact attribution also goes into `THIRD_PARTY_NOTICES.md`. This is intentionally tedious. License archaeology is cheaper than discovering six months later that half the APK has an unclear provenance chain.
