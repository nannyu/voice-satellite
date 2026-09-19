# R1 原厂语音前端与服务器 Agent 桥接设计

日期：2026-09-20。本目录是设计资料，不是可部署服务或 APK。

## 文件

- [`../08-stock-agent-bridge.md`](../08-stock-agent-bridge.md)：完整架构、接口语义、打断/取消、部署、安全、实施阶段与回滚。
- `agent-api-examples.json`：拟定的内部 Agent 接口样例，不是原厂协议。
- `acceptance-matrix.json`：20 个拟定验收场景；全部标记为未执行。
- `sources.json`：核对的来源与仓库提交。
- `validate_design.py`：离线校验 JSON、引用、样例关联和验收编号，不访问网络或设备。
- `VALIDATION.md`：本轮文档校验记录。

## 使用顺序

阅读 [完整方案](../08-stock-agent-bridge.md) 的决策、打断边界及 P0/P1 门槛；在真实 Agent 接口确定后实现适配器；通过原厂固定回复与打断基线后再启用真实业务。不要将样例路径当成已存在的服务。

## 验证

```bash
python3 docs/stock-agent-bridge/validate_design.py
```

从仓库根目录运行。上述命令仅做资料一致性校验，不验证真机、ASR 上游或 Agent 实现。

本次提交仅更新设计资料、仓库说明与离线一致性校验脚本；没有更改运行代码、路由器、设备包状态或权限。
