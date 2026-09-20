# 09. Wake Word Decision (historical independent client)

Date: 2026-09-18 (revised same day).

## Status (2026-09-20)

**Not the current mainline.** The authoritative direction is the stock Agent bridge
in `docs/08-stock-agent-bridge.md`. Snowboy remains only as an independent-client
regression/backup implementation; no new Porcupine or sherpa work is planned.
Chinese `.pmdl` work is optional and does not satisfy stock-bridge acceptance.

## Product requirements (historical for Snowboy path)

- **中文唤醒词**
- **完全离线运行**
- **无账号 / 无 AccessKey / 无云端鉴权**

Picovoice Porcupine **已淘汰并移出工程**（需要 AccessKey，且内置词偏英文）。

## Candidates (re-scored)

| Engine | 中文 | 离线无账号 | ARMv7 / API 22 | 许可证 | 结论 |
|--------|------|------------|----------------|--------|------|
| Porcupine | 可（仍要 Key） | 否（要 AccessKey） | 是 | 专有免费档 | **淘汰（不满足需求）** |
| **Snowboy (Kitt-AI 官方)** | 个人 `.pmdl` | **运行时是** | 是 | 引擎/官方库 **Apache-2.0** | **采用** |
| 能量门限 | — | 是 | 是 | — | 否决（不是唤醒词） |
| 从 r1-helper 抄 Snowboy 包装/模型 | — | — | — | GPL / 来源不清 | **禁止** |

## Decision

独立客户端实验的默认引擎为 **官方 Snowboy**（`SnowboyWakeWordEngine`），挂在可替换的 `WakeWordEngine` 后面。运行时**无账号、无 AccessKey、不联网**。原厂桥接模式不启动这条独立录音链路。

已接入内容（均来自 Kitt-AI 官方树 / 官方 demo APK，**禁止**从 `r1-helper` 抄）：

| 路径 | 用途 |
|------|------|
| `app/src/main/jniLibs/armeabi-v7a/libsnowboy-detect-android.so` | Native 检测 |
| `app/src/main/assets/snowboy/common.res` | 必选资源 |
| `app/src/main/assets/snowboy/snowboy.umdl` | 英文冒烟（Apache） |
| `app/src/main/java/ai/kitt/snowboy/*` | 自有 SWIG 风格 JNI 包装 |

模型选择（`SnowboyAssets`）：任意 `*.pmdl` **优先于** `snowboy.umdl`；都没有则 `engine=none`（Simulate Wake 仍可用）。

### 中文 `.pmdl` 怎么放

官方没有现成中文 universal `.umdl`，量产必须用你自己的 personal model：

1. **编译进 APK**：把文件放到
   `voice-satellite/android-r1/app/src/main/assets/snowboy/wakeword.pmdl`
   （文件名任意，扩展名 `.pmdl` 即可），重新打包安装。
2. **装机后推送**：同步到应用私有目录（首次启动会从 assets 拷 `common.res` / umdl，**不会覆盖**已有 `.pmdl`）：

```bash
adb push your_zh.pmdl /sdcard/wakeword.pmdl
# 再用能写 app files 的方式拷到：
#   /data/data/io.nannyu.voicesatellite.r1/files/snowboy/wakeword.pmdl
```

细节与哈希见 `app/src/main/assets/snowboy/PROVENANCE.md`。

### 关于「训练要不要账号」

- **设备上听唤醒：零账号、纯离线。**
- 训练 `.pmdl` 是离线前的一次性步骤。历史上 snowboy.kitt.ai 可能要注册；也可用 seasalt-ai/snowboy 等本地训练。那不是运行时联网。
- 不要塞来路不明的第三方 `.pmdl` / `.so`；个人模型本地放置即可。

### 建议唤醒词

短、开口音清楚、三音节内（如「小智小智」「你好管家」），用**你的声音**录若干条练成 `.pmdl`。词越长、口音越杂，误唤醒/漏唤醒越差。

## Mic ownership（不变）

1. IDLE + READY → wake 占麦
2. 唤醒 / TALK / Simulate Wake → **先停 wake**，再开会话 `AudioRecorder`
3. 回到 IDLE → 再开 wake

## Exit criteria

20 次：中文唤醒 → 说话 → echo → Idle，不重启 App。
在中文 `.pmdl` 就绪前，用 Simulate Wake / `snowboy.umdl` 冒烟，不把「英文 Snowboy」当成中文验收。
