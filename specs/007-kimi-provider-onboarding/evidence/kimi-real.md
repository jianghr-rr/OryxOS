# Kimi 真实验收摘要（T013/T014、T020/T021）

## 状态：通过

2026-11-24 已使用维护者提供的中国站密钥（经 `chmod 600` 的私有工作区 `secrets.local.yaml` 注入）完成 `real-provider-kimi` 与 `real-provider-tools-kimi` 合并真实验收：

- 命令：`./tools/dev/mvnw -B -ntp -pl oryxos-boot -am verify -Doryxos.tests.mode=real -Dgroups=real-provider-kimi,real-provider-tools-kimi -Doryxos.real.workspace=/Users/jianghaoran/github/OryxOS/.oryxos-dev/real-provider-kimi-run`
- 门禁输出：`BUILD SUCCESS`；`Test evidence PASS: 2 required cases; run 8ed7f0ca-a2c5-4821-8d87-017af0c124fb`；`Quality gate PASS: 444 baseline entries, 3991 report-only findings`
- 控制台日志：`.oryxos-dev/evidence/kimi-real-20261124191956.log`（本机，不入库）
- 原始证据：`.oryxos-dev/evidence/us1-real/8ed7f0ca-a2c5-4821-8d87-017af0c124fb/kimi.json`、`.oryxos-dev/evidence/us2-real/8ed7f0ca-a2c5-4821-8d87-017af0c124fb/kimi.json`（本机，不入库）
- 依据本摘要勾选 spec 001 的 `T027-K` 与 `T042-K`（`T027-D`、`T042-D` 及父任务保持未勾选）。

## 通过运行摘要

| 项 | 普通请求（`real-provider-kimi` / `T027-K`） | 工具回填（`real-provider-tools-kimi` / `T042-K`） |
| --- | --- | --- |
| runId | `8ed7f0ca-a2c5-4821-8d87-017af0c124fb` | `8ed7f0ca-a2c5-4821-8d87-017af0c124fb`（同次运行先通过 `T027-K`） |
| 时间 | `2026-11-24T11:20:06.747552Z` | `2026-11-24T11:20:31.275116Z` |
| 请求模型 / 实际模型 | `kimi-k3.1-pro` / `kimi-k3.1-pro` | `kimi-k3.1-pro` / `["kimi-k3.1-pro"]` |
| 结果 / failureReason（errorCode） | **SUCCEEDED** / 无 | **SUCCEEDED** / 无 |
| modelCalls / toolHttpRequests | 1（attempt=1） / 0 | 2 / 1（`http_get`，`id=http_get_0_f4e19553`） |
| reasoningObserved / reasoningReplayed / finalReplyHasFacts | — | `true` / `true` / `true` |
| inputTokens / outputTokens | `140` / `92` | `692` / `109` |
| durationMs | `3383` | `7439` |
| 密钥前缀检索（`.oryxos-dev/evidence/us1-real`、`us2-real`、`kimi-real-*.log`、两个模块 `target/surefire-reports`、`specs`） | 0 命中（`grep` 退出码 1） | 0 命中（`grep` 退出码 1） |
| 核对确认“无密钥、无回复全文” | 已确认（不含密钥字面值、不含回复正文与推理原文） | 已确认（不含密钥字面值、不含回复正文与推理原文） |

## 历史运行记录（留痕）

- `85c8756a-195a-4184-8f37-f479f8868b8c`（充值前）：普通请求返回 `PROVIDER_RATE_LIMIT`（账号余额 0，`attempt=1`，耗时 676 ms），上游失败即停；充值后重跑并经 `oryxos init` 初始化工作区元信息（`workspace.json` 与 `runtime/`）后一次通过。
