# review-fixes：生命周期修复与 PCM 回环

## 范围

基线提交：`05e271fc100e68cdb4adb3b6d72e8c30045d546b`。
本分支修复连接重试、旧连接回调、采集资源释放，并将诊断入口接入现有 PCM 回声服务器。
不包含 HA Assist、唤醒词、后台常驻服务、流式 TTS 或媒体 URL 播放。

`gateway/test_server.py` 是可运行的参考服务器，而不只是测试代码。
当前 `Protocol.voiceStart()` 与该服务器使用 `pcm_s16le / 16000 Hz / mono`。
Opus 保留为单独的 JNI 诊断，本回环不发送 Opus 数据，也不冒充已经完成编解码协商。

## 修复点

1. `ConnectionSupervisor` 使用串行队列、连接代次与唯一重试任务。
   断线/心跳/握手超时直接触发重连，不再等待已经失联的 socket 返回关闭回调。
   成功收到有效 `hello.ack` 才重置退避。停止后取消定时器并忽略迟到回调。
   supervisor 是一次性对象，停止后重启应创建新实例。
2. `WebSocketTransport` 同时检查连接代次和 socket 身份。
   替换连接前先使旧连接失效，主动关闭使用 `cancel()` 释放连接。
   每代只转发一次终止事件，并响应服务器的关闭握手。
3. `CaptureWorker` 管理每次采集的线程与资源。停止先解除 `read()` 阻塞，最多等待 1 秒；
   只有退出采集处理后的 worker 才能释放录音和 VAD。超时保留旧代资源及所有权，拒绝本实例重启。
   自身回调触发停止不进行自我 join；初始化/读取/释放异常都有收尾路径。
   同时消除预录缓冲中触发帧被重复发送的问题。
4. `LoopbackSession` 串接 `SessionController`、协议、采集和播放；
   `AndroidLoopback` 负责实际设备适配；`MainActivity` 提供显式连接与单次收音入口。
   只有握手完成才能收音，每轮请求单独标识，丢弃旧录音与播放完成回调。
   服务器 `state.idle` 不会提前结束本地播放。离开诊断界面停止采集和连接。

## 边界与保护

这是前台、半双工、缓冲后播放的诊断链路，不是完整语音助手。
无语音等待上限 5 秒，单次采集上限 15 秒，等待完整响应上限 15 秒。
PCM 响应最多 960,000 字节（30 秒）；异常或取消会重建连接以清除服务器残留会话。
`ws://` 仅用于可信局域网诊断，无认证的参考服务器不得暴露到公网。

## 自动化验证

新增 27 个 JUnit 测试，覆盖连接去重/旧回调、握手与心跳超时、采集线程生命周期，
以及 PCM 请求/响应顺序、播放完成、旧请求隔离、超时与响应大小限制。

不需要 Android SDK 的完整纯 JVM 回归入口：

```sh
mvn --batch-mode -f android-r1/jvm-tests/pom.xml test
```

Android 工程中的原生构建与全部单元测试：

```sh
bash android-r1/scripts/bootstrap-native.sh
cd android-r1
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

新增 JVM CI 直接编译生产源文件，使用仓库相同版本的 OkHttp、org.json 和 JUnit。
既有 Android CI 继续验证 Android 平台编译与 APK 构建。
本地无 Android SDK 时的签名桩编译检查不等同于 APK 构建；假设备单元测试也不等同于 R1 真机通过。

## R1 3448 人工验收

在可信局域网服务器运行：

```sh
python -m pip install -r gateway/requirements.txt
python gateway/test_server.py --host 0.0.0.0 --port 8765 --mode echo
```

安装该分支生成的 debug APK。诊断界面填入服务器地址，连接后点击 `Speak one request`。
无屏幕场景可显式发起一次收音（替换为实际服务器地址）：

```sh
adb shell am force-stop io.nannyu.voicesatellite.r1
adb shell am start -n io.nannyu.voicesatellite.r1/.MainActivity \
  --es gateway_url 'ws://192.168.1.100:8765' --ez loopback_once true
```

确认握手后说一句短句，等待静音触发结束，应听到自己的录音。
此操作明确启动麦克风采集，不属于只读诊断。

验收还应覆盖：服务器 `--no-ack`、`--drop-after 3`、`--slow-response 20`，
网络恢复后重新握手、播放时触发第二次请求被拒绝、离开 Activity 后录音停止。
本分支不要求刷固件、Root 或改动原厂系统服务。
