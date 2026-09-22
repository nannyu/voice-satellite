# R1 原厂语音前端与服务器 Agent 桥接设计

日期：2026-09-22。本目录保存设计与验收契约；可运行的 P1 服务器切片位于 `../../services/stock-gateway/`，仍不是已完成的真机交付。

## 文件

- [`../08-stock-agent-bridge.md`](../08-stock-agent-bridge.md)：完整架构、接口语义、打断/取消、部署、安全、实施阶段与回滚。
- `agent-api-examples.json`：拟定的内部 Agent 接口样例，不是原厂协议。
- `acceptance-matrix.json`：20 个拟定验收场景；全部标记为未执行。
- `sources.json`：核对的来源与仓库提交。
- `validate_design.py`：离线校验文档编号与索引、JSON、接口样例、验收矩阵、来源 URL/固定提交与 Markdown 引用一致性，不访问网络或设备。
- `test_validate_design.py`：反例回归测试，确保丢失来源引用、空 URL、验收表漂移和重复文档编号都会失败。
- `VALIDATION.md`：本轮文档校验记录。
- [`../../services/stock-gateway/`](../../services/stock-gateway/README.md)：受限 passthrough/fixed_reply 服务及离线回归；未改 DNS、设备或 Agent。

## 使用顺序

阅读 [完整方案](../08-stock-agent-bridge.md) 的决策、打断边界及 P0/P1 门槛；先用 P1 服务切片做受限协议回归，再在真实 Agent 接口确定后实现适配器；通过原厂固定回复与打断基线后再启用真实业务。Agent API 样例路径仍只是契约，不是已经部署的 Agent 服务。

## 验证

```bash
python3 docs/stock-agent-bridge/validate_design.py
python3 docs/stock-agent-bridge/test_validate_design.py -v
```

从仓库根目录运行。上述命令仅做资料一致性校验，不验证真机、ASR 上游或 Agent 实现。

本次提交还整合了独立 Android 回归客户端、历史 KWS 探针和只读设备取证工具。这些代码不代表原厂桥接服务已实现；没有更改路由器、设备包状态或权限。
