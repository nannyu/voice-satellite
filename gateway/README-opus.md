# Opus Voice Gateway MVP

可运行的 `decode → PCM backend → encode` 网关，不是压缩包原样回传，也不是已经接入 HA/Agent 的助手。
完整启动、R1 ADB 命令、协议、测试和安全边界见 [`docs/09-opus-mvp.md`](../docs/09-opus-mvp.md)。

```sh
python -m pip install -r gateway/requirements-opus.txt
# 另需系统 libopus，例如 Debian/Ubuntu 的 libopus0。
python gateway/server.py --mode echo
```

默认仅监听 `127.0.0.1:8765`；局域网监听需显式令牌或 `--allow-insecure-lan`。
`AudioBackend.respond(pcm)` 接收并返回 PCM16/16 kHz/mono 的 bytes，是未来后端适配点。
保留 `test_server.py` 作为旧 PCM 客户端的诊断服务器。
