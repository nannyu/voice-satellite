# Stock frontend read-only inventory

这个仓库内工具仅做 R1/3448 原厂语音包、服务、AudioFlinger 与可选 APK 的只读取证。它不会 root、重启、hide/unhide、安装或开启麦克风。

```bash
cd tools/stock-frontend
python3 -m unittest -v test_stock_frontend_inventory.py
python3 stock_frontend_inventory.py \
  --serial 192.168.1.17:5555 \
  --out ./r1-stock-inventory-3448 \
  --pull-apk
```

输出目录、原厂 APK、日志和设备标识均被 `.gitignore` 排除，不应公开提交。

当前主线：[`docs/08-stock-agent-bridge.md`](../../docs/08-stock-agent-bridge.md)。
被取代的 PCM/自建 ASR 备选路线：[`docs/11-stock-frontend.md`](../../docs/11-stock-frontend.md)。
