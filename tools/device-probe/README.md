# R1 on-device audio probe automation

通过 Wi-Fi ADB 驱动 Voice Satellite 诊断 App 的自动音频探测，并导出文本报告。
仅用于自己的 R1：不安装软件、不修改系统包、不提权、不上传数据。

## 脚本

- `r1_probe_playback.py` — **当前版本**，对应 App 0.1.2+。运行完整 Probe
  （三路麦克风 + 扬声器播放/采播并存），等待报告包含
  `[MIC] [VOICE_RECOGNITION] [VOICE_COMMUNICATION] [PLAYBACK]` 全部段落。
  对本固件偶发的 `error: closed` ADB 断连做有限重试。
- `r1_probe_ui.py` — 早期版本，对应 App 0.1.x（仅三路麦克风，无播放段）。
  保留用于对照旧固件/旧 APK 行为。

## 用法

```bash
# 环境：Python 3.8+，adb 在 PATH 或用 --adb / ADB 环境变量指定
adb connect 192.168.1.17:5555

# 安静环境基线（无人值守）
python3 r1_probe_playback.py run --label quiet

# 语音场景：开始后对设备连续重复同一句话，播放阶段会听到约 1.5 秒 440Hz 测试音
python3 r1_probe_playback.py run --label speech

# 只读取当前界面上的已有报告（不启动录音）
python3 r1_probe_playback.py read
```

退出码：`0` 报告无异常标记；`2` 报告已导出但含 ERROR/timeout 等异常标记
（不等于硬件验证通过）；`1` 未取得完整报告。

报告默认写入 `tools/device-probe/r1-probe-results/<label>-<时间戳>/`
（已 gitignore），含完整报告文本和每次界面快照 XML。

## 设备事实（3448 固件实测）

- adbd 拒绝顶层 `pm` 命令，必须 `sh -c` 包装（脚本已内建）。
- adbd 偶发 `error: closed` 断连，脚本会有限重连重试。
- AudioFlinger 异常（如僵尸输入 `already exists available input`）会导致
  采集/播放线程永久阻塞；Probe 的 8 秒硬超时保证报告可导出，但恢复音频服务
  只能重启设备。
