# 契约：Provider 调用边界（模块内部契约）

对应需求：FR-001 ~ FR-006、FR-009、FR-010。本契约约束 `oryxos-core` 与 `oryxos-provider` 之间的调用方式；
它不是对外 HTTP/CLI 契约，对外契约保持不变（FR-010）。

## 1. 参与者与职责

| 部件 | 模块 | 唯一职责 | 明确不做 |
| --- | --- | --- | --- |
| `ReActLoop` | core | 申请**一次**运行准入，在准入内调用 `ProviderPort.call` | 不做协议转换、不写模型审计 |
| `ProviderPort` | core | 接口：`ProviderReply call(ProviderRequest)` | — |
| `ProviderService`（原 `ProviderRegistry`） | provider | 入口：关闭检查 → 请求校验（一次）→ 按名查表 → 交给该 provider 的审计包装 | 不准入、不映射原始异常 |
| `AuditedProvider` | provider | 写 `STARTED` → 调用适配层 → 写 `SUCCEEDED`/`FAILED`；扣除自身耗时后传递剩余预算 | 不准入、不重复映射（非 `OryxError` 仅防御性归为 `PROVIDER_PROTOCOL`） |
| `SpringAiProvider` | provider | 路由一致性防御检查 → 消息/选项映射 → 一次低层 HTTP 调用 → 回复映射；**唯一**的原始异常映射点 | 不准入、不重复校验、不执行工具、不重试、不 fallback |

## 2. 调用序列

```text
ReActLoop
  └─ ctx.dispatcher().call(              ← 唯一准入（检查停止/暂停/截止；inFlight +1）
       ProviderService.call(request)
         ├─ closed? → PROVIDER_UNAVAILABLE                         （无审计、无请求）
         ├─ validate(request) → CONFIGURATION_ERROR / INVALID_TASK / PROVIDER_TIMEOUT（无审计、无请求）
         ├─ lookup(providerName) 失败 → PROVIDER_UNAVAILABLE        （无审计、无请求）
         └─ AuditedProvider.call(request)
              ├─ audit STARTED（失败 → RECORDING_UNAVAILABLE，无请求）
              ├─ SpringAiProvider.call(request − 已耗时)
              │    └─ 一次 HTTP；任何异常 → ProviderErrorMapper / timeout → OryxError
              ├─ 成功 → audit SUCCEEDED → 返回
              └─ 失败 → audit FAILED(code) → 抛出同一 OryxError
     )                                    ← 准入在此释放（inFlight −1）
```

## 3. 不变量（由测试钉住）

| # | 不变量 | 守护测试 |
| --- | --- | --- |
| P1 | 一次模型调用期间工作区 `inFlight` 恰为 1 | `ProviderAdmissionTest#modelCallHoldsExactlyOneAdmissionWhileInFlight` |
| P2 | `STARTED` 之后的停止/暂停不改变本次结果，审计终态不出现 `EXECUTION_STOPPED`/`EXECUTION_PAUSED`/`DEADLINE` | `ProviderAdmissionTest#stopAfterAdmissionLetsTheCallFinishAndRecordsActualOutcome` |
| P3 | 两个 provider 按名路由，不串台；未知名明确失败 | `ProviderRoutingTest` |
| P4 | 失败调用先写 `FAILED`+分类码，再抛出同码错误；记录不含密钥/私有 URL | `ProviderAuditTest` |
| P5 | provider 主代码对 Spring AI 的引用仅限 `org.springframework.ai.openai.api.` | `ProviderArchitectureGuardTest` |
| P6 | 模型提出的工具调用原样返回，provider 不执行 | `ProviderToolSchemaTest#returnsStructuredCallsAndReplaysReasoningWithoutExecutingTools` |
| P7 | `ProviderRequest` 不含准入句柄 | 编译期保证（组件已删除） |
| P8 | 非法请求（空任务、剩余时间为零、未知 provider）不写审计、不发网络请求 | `ProviderRoutingTest#invalidRequestsNeverWriteAuditOrReachNetwork` |

## 4. 错误码集合

与重构前完全一致，不新增、不删除：`PROVIDER_UNAVAILABLE`、`CONFIGURATION_ERROR`、`INVALID_TASK`、`PROVIDER_TIMEOUT`、
`PROVIDER_AUTHENTICATION`、`PROVIDER_RATE_LIMIT`、`PROVIDER_REQUEST`、`PROVIDER_CONNECTION`、`PROVIDER_PROTOCOL`、
`SESSION_MODEL_CHANGED`、`RECORDING_UNAVAILABLE`、`PARTIAL_RECORDING_FAILURE`。
准入相关的 `EXECUTION_STOPPED`、`EXECUTION_PAUSED`、`DEADLINE` 仍由 `ReActLoop` 的准入抛出，不经过 provider。

## 5. 计时分段

`provider.transport`、`provider.reply`（`SpringAiProvider`）与 `react.provider`（`ReActLoop`）分段名不变；
`kernel-provider-timing` 分组的断言不修改。
