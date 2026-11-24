# Data Model: Kimi Provider 独立接入与真实验收

**Feature**: [spec.md](./spec.md) | **Date**: 2026-11-24

本特性不新增运行时持久化实体、数据库表或迁移；以下是配置、测试分组与证据的结构约定。

## 1 Kimi Provider 定义（运行配置 `providers.kimi`）

复用现有 `ProviderDefinition`，无新字段。

| 字段 | 值 | 校验（现有 `RuntimeConfigLoader`） |
| --- | --- | --- |
| name | `kimi`（Agent frontmatter `provider.name` 引用） | Provider 名称唯一 |
| protocol | `openai_chat_completions` | 只允许该值 |
| endpoint | `https://api.moonshot.cn` | 合法 URI；真实验收另断言 HTTPS + 主机 |
| completions_path | `/v1/chat/completions` | 以 `/` 开头；真实验收另断言等值 |
| api_key | `${KIMI_API_KEY}`（引用，不是明文） | 引用必须可解析（环境变量或 600 权限 `secrets.local.yaml`），否则该 Provider 不可用 |
| connect_timeout_seconds | `5` | 正整数 |
| options.reasoning_effort | `low` | 现有集合 `low/medium/high`；Kimi 只接受 `low/high`（文档说明） |
| options.temperature / thinking | 不出现 | Kimi 固定 temperature；K3 始终思考 |

状态：`CONFIGURED`（可用）/ `UNAVAILABLE`（引用 Kimi 的 Agent 不可用，其他 Agent 不受影响）。重启加载。

## 2 Kimi 验收 Agent

| Agent | provider | model | tools | 用途 |
| --- | --- | --- | --- | --- |
| `real-kimi` | `kimi` | `REPLACE_WITH_ACCOUNT_ENABLED_KIMI_MODEL` → 维护者填写 | `[]` | 普通请求（FR-002） |
| `real-tools-kimi` | `kimi` | `REPLACE_ACCOUNT_ENABLED_REASONING_TOOL_MODEL` → 维护者填写 | `[http_get]` | 工具回填（FR-003） |

前置失败条件（FR-004，均在网络请求前）：Provider 缺失/不可用、主机或协议或路径不符、模型名以 `REPLACE_` 开头、工具集合不符、回环夹具 URL 未在 HTTP 白名单。

## 3 测试分组（`tools/dev/test-groups.json`）

| 分组 | mode | 模块 / 类 | 方法 | 转发凭证 |
| --- | --- | --- | --- | --- |
| `real-provider`（不变） | real | provider / `RealProviderIT` | `deepseekOrdinaryRequest`、`kimiOrdinaryRequest` | DeepSeek + Kimi |
| `real-provider-tools`（不变） | real | boot / `RealProviderToolLoopIT` | `deepseekToolResultRoundTrip`、`kimiToolResultRoundTrip` | DeepSeek + Kimi |
| `real-provider-kimi`（新） | real | provider / `RealKimiProviderIT` | `kimiOrdinaryRequest` | 仅 Kimi |
| `real-provider-tools-kimi`（新） | real | boot / `RealKimiToolLoopIT` | `kimiToolResultRoundTrip` | 仅 Kimi |
| `kimi-onboarding`（新） | local | boot / `KimiOnboardingLocalIT` | 见 quickstart | 无（夹具密钥） |

## 4 Kimi 真实验收证据（本机，不入库）

路径：`.oryxos-dev/evidence/us1-real/<runId>/kimi.json`、`.oryxos-dev/evidence/us2-real/<runId>/kimi.json`。

普通请求字段：`kind=REAL_MODEL`、`time`、`provider`、`requestedModel`、`actualModel`、`completionsPath`、`request`、`response`（脱敏）、`inputTokens`/`outputTokens`（不可得为 null）、`durationMs`、`executionId`、`status`（`SUCCEEDED`/`FAILED`）、`errorCode`、`safeMessage`、`auditEvents`、`java`、`springAi`。

工具回填字段：`kind=REAL_MODEL_WITH_LOOPBACK_TOOL_DATA`、`time`、`provider`、`requestedModel`、`executionId`、`executionStatus`、`modelCalls`、`toolHttpRequests`、`actualModels`、`calls`（id + wireName）、`reasoningObserved`、`reasoningReplayed`、`finalReplyHasFacts`、`inputTokens`/`outputTokens`（各次求和，任一未知则 null）、`durationMs`、`status`、`failureReason`（取值见 research R7）。

构建控制台输出另存为 `.oryxos-dev/evidence/kimi-real-<runId>.log`，与证据目录、`target/surefire-reports` 一起纳入密钥字面值检索范围（SC-003）。

不得出现：凭证字面值、`reasoning_content` 原文。

状态：`FAILED`（初始）→ `SUCCEEDED`（全部断言通过后）。手动重跑产生新 runId，旧文件保留。

## 5 Kimi 真实验收摘要（入库）

路径：`specs/007-kimi-provider-onboarding/evidence/kimi-real.md`。

| 字段 | 说明 |
| --- | --- |
| runId | 一个或多个；普通请求与工具回填可为同一或不同 runId |
| 时间 / 请求模型 / 实际模型 | 来自证据 |
| 结果 | 通过 / 失败 + `failureReason` 或 `errorCode` |
| token / 耗时 | 来自证据，未知照写“未知” |
| 核对人与核对项 | 维护者确认无密钥、无回复全文 |

关系：摘要是 spec 001 `T027-K`、`T042-K` 的唯一勾选依据（FR-008）；`T042-K` 必须同时引用一个 `T027-K` 通过的 runId（research R6）。
