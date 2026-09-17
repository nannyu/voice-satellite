# Opus 端到端音频 MVP

基线：`review-fixes` 的 `1117eb6a39fcb86f24b4957ea24c35c5094ce8a3`。
开发分支：`e2e-opus-mvp`。Android 版本：`0.3.0-mvp` / versionCode 5。

## 已实现范围

```text
R1 AudioRecorder / VAD（16 kHz、单声道 PCM16、30 ms）
  → OpusPacketizer（聚合为 60 ms，结束时补齐编码延迟）
  → OpusEncoder JNI（24 kbit/s 目标码率、complexity 5）
  → WebSocket（二进制消息各承载一个 raw Opus packet）
  → Gateway libopus 解码 → AudioBackend（echo / tone / silence）
  → Gateway libopus 重新编码 → WebSocket
  → R1 OpusDecoder JNI → 去除 pre_skip 与尾部补零 → AudioPlayer
```

这是可运行的音频传输 MVP，不是已接入 Agent 的语音助手。
默认后端解码后返回录音；`tone` 返回 1 秒提示音；`silence` 用于无音频响应验收。
`AudioBackend.respond(pcm)` 是后续接入 HA / STT / Agent / TTS 的边界。
暂不包括唤醒词、HA Assist、媒体 URL、后台常驻、全双工、边接收边播放。
上传按帧发送，响应在完整接收并验证后播放；协议中的 idle 不会提前结束本地播放。

## 网关启动（Linux 参考环境）

需要 Python 3.9+ 与系统 libopus。验证基线使用 Python 3.11 / websockets 12.0。

```sh
sudo apt-get update
sudo apt-get install -y libopus0 python3-venv
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r gateway/requirements-opus.txt
python gateway/server.py --host 127.0.0.1 --port 8765 --mode echo
```

供 R1 访问的可信局域网诊断需显式开放监听：

```sh
python gateway/server.py --host 0.0.0.0 --port 8765 --mode echo --allow-insecure-lan
```

需要令牌时，通过环境变量 `VOICE_GATEWAY_TOKEN` 提供，Android 界面填入同一个 token。
有令牌时可省略 `--allow-insecure-lan`。token 不写入 Android 偏好设置、界面保存状态或网关日志。
`ws://` 不加密音频与 token；本实现的 CLI 不直接终止 TLS，不应直接暴露公网。
允许 `wss://` 的 Android 客户端仍需经过正确配置的可信 TLS 入口。
网关默认拒绝浏览器 Origin，并限定同时一台卫星；这不是多租户服务。

## 无屏幕 R1 的单次回环

使用该分支 CI 生成的 APK，不要求刷机、Root 或改变原厂系统服务。
以下命令会安装应用并显式启动麦克风，不属于只读操作。

```sh
adb install -r app-debug.apk
adb shell am force-stop io.nannyu.voicesatellite.r1
adb shell am start -n io.nannyu.voicesatellite.r1/.MainActivity \
  --es gateway_url 'ws://192.168.1.100:8765' \
  --es audio_codec opus --ez loopback_once true
adb logcat -s VoiceSatellite:I '*:S'
```

将示例地址替换成运行网关的局域网地址；它不是设备发现结果。
握手完成后出现 `Ready: Opus MVP`，单次模式立即进入 LISTENING。说一句短句，静音后进入
PROCESSING、SPEAKING，播放结束后回到 IDLE。
有 token 时可在上述 `am start` 中附加 `--es gateway_token '<token>'`；ADB 命令可能进入
终端历史，只在可信调试环境使用，不要把真实 token 放进文档或工单。
未提供 `loopback_once` 时，不会自动启动录音；界面提供单次触发与取消按钮。
离开 Activity 会停止采集、播放及网络连接。

若已安装 APK 与 CI 使用不同签名，`adb install -r` 会失败；不要为了重试自动卸载或清除应用数据。
需使用原签名重新构建，或在确认可丢弃应用数据后另行处理。此分支不执行卸载。

旧 PCM 诊断仍保留：运行 `gateway/test_server.py`，并在 Android 中取消 Opus 选项，
或传入 `--es audio_codec pcm_s16le`。Opus 模式不会静默降级连接旧 PCM 服务器。

## 线协议补充（protocol 1 的显式协商配置）

`hello` 在既有设备字段和 `capabilities: ["opus", ...]` 外附加：

```json
{"audio":{"codec":"opus","sample_rate":16000,"channels":1,"frame_ms":60}}
```

`hello.ack` 必须明确返回同一 audio 对象；省略、错采样率、字符串数值、30 ms 等均不接受。

每轮顺序：

1. `voice.start`：独立 `session_id`、trigger、audio、`pre_skip`。
2. 每个二进制消息是一个 60 ms raw Opus 包，不含 WAV/Ogg 头，不拼接多个 packet。
3. `voice.end`：同一个 `session_id`、reason、`samples`（原始有效单声道样本数，不含补零）。
4. `state.processing` → `response.start`（session_id、audio、pre_skip）。
5. 二进制 Opus 响应包 → `response.end`（session_id、samples）→ `state.idle`。

`pre_skip` 取自编码器的 OPUS_GET_LOOKAHEAD，单位为 16 kHz 样本，不是字节或毫秒。
编码结束时还会输入 lookahead 数量的零样本，解码后根据 pre_skip 与 samples 裁剪，
避免丢失编码器中尚未输出的句尾。每轮请求、响应均使用新的编解码器状态。
WebSocket 顺序传输配合单轮并发约束；取消或错误会销毁连接，避免残留音频串入下一轮。
认证或协商失败会停止自动重试，需重新显式连接；网络故障沿用退避重连机制。

## 资源与失败边界

无语音等待 5 秒；客户端单次采集最长 15 秒、完整响应等待 15 秒；
服务器后端超时 10 秒、等待 voice.end 最长 20 秒。输出 PCM 最多 30 秒。
包上限 4,000 字节，接收 WebSocket 消息上限 65,536 字节、接收队列上限 8；
采集与响应还分别按解码后样本数限额，不能靠压缩率绕过内存限制。
服务日志只记录 session 标识、样本/包/传输字节数和耗时，不保存录音。

## 可重复验证

纯 JVM 回归（包含既有 58 项及本阶段新增测试）：

```sh
mvn --batch-mode -f android-r1/jvm-tests/pom.xml test
```

真实 libopus 与本地 WebSocket 的 Python 测试：

```sh
python -m unittest discover -s gateway -p 'test_opus_gateway.py' -v
```

跨语言端到端测试（Linux、JDK 17、Maven、g++、pkg-config、libopus-dev）：

```sh
sudo apt-get install -y libopus-dev pkg-config
bash android-r1/e2e/run-host.sh
```

该脚本编译生产 `opus_jni.cpp` 与生产 Java 类，连接真实 Python Gateway；
在 echo、tone 两种模式分别完成三个回合，其中第三回合重建 WebSocket 后再握手。
验证样本数、非零音频能量、echo 波形相关性、每轮独立 codec，以及 JNI 单/双声道边界。
只替换麦克风输入与扬声器输出，不模拟编解码器、网络或服务器。
它不等同于 R1 AudioRecord / AudioTrack / VAD 的真机验收。

Android APK 构建仍由 `Android R1 build` 工作流执行；新增 `Opus gateway E2E`
工作流执行网关与跨语言测试并上传日志和 JUnit 报告。

## R1 真机验收门槛

在 3448 上连续完成至少 20 轮短句回放；检查偶数/奇数个采集帧都不截断句尾；
录音途中停止网关并恢复，验证重新握手后能开始新会话；播放期间不能再次录音；
取消、离开界面、网络中断后无残留录音；错误 token 与旧 PCM 服务器会明确拒绝。
只有这些项目实际通过后，才可称为该设备上的端到端验收通过。

## API 依据

- Opus 编码帧大小与编码器状态：https://opus-codec.org/docs/opus_api-1.6/group__opus__encoder.html
- WebSocket 服务端边界：https://websockets.readthedocs.io/en/12.0/reference/asyncio/server.html
- WebSocket 队列与消息大小：https://websockets.readthedocs.io/en/12.0/reference/asyncio/common.html
