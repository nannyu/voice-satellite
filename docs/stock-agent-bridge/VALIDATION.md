# 设计资料校验记录

日期：2026-09-20。

校验范围：连续文档编号与 README 索引、Markdown 围栏、JSON 语法、接口样例 ID/代次/取消关联、验收表双份一致性、来源元数据/HTTPS URL/固定提交与 Markdown 引用完整性。

校验结果：

```text
PASS: Numbered docs are unique, contiguous, heading-matched, and linked
PASS: Markdown fences and embedded API JSON are valid
PASS: Request/result/read/cancel IDs, generation, and capability defaults agree
PASS: 20 proposed acceptance cases match Markdown and remain not_run
PASS: S1-S13 metadata, HTTPS URLs, pinned revisions, and Markdown references agree
Scope: document consistency only; no device/network/service tests were run.
```

反例回归：`test_validate_design.py` 6/6 通过，覆盖正常资料包、正文漏引来源、空来源 URL、来源标题漂移、验收表漂移和重复文档编号。

其他本地验证：

- `tools/stock-frontend` 无设备单测 5/5 通过。
- Android `testDebugUnitTest assembleDebug` 从独立工作树完整执行，39 个 Gradle task 成功；仅有 ARMv7/Play 商店与上游 Opus debug 编译警告。
- `bootstrap-kws-probe.sh --aar-only` 在干净工作树成功获取编译所需的 sherpa-onnx AAR；AAR 保持 gitignored。

尚未执行：原厂 ASR 可用性、3448 声学/播放/打断、Agent 对接、部署和稳定性验收。`acceptance-matrix.json` 中 20 个场景均为 `not_run`。

本报告不将资料一致性检查、历史实验代码构建或只读工具单测视为新网关服务或真机业务验收。来源对应阅读时的仓库提交；接口为拟定草案。
