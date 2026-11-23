# Data Model: Provider 边界重构

**Feature**: `005-provider-boundary-refactor` | **Spec**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

本次不新增持久化实体，不改表结构。这里记录三类会变化或需要钉住的“模型”：内存中的模型请求、
审计记录的状态序列，以及测试资产的归属。

## 1. 模型请求 `ProviderRequest`（`oryxos-core`）

| 组件 | 变更 | 说明 |
| --- | --- | --- |
| `providerName` | 不变 | 必须能在 `ProviderService` 的映射表中找到 |
| `model` | 不变 | 入口校验：非空白 |
| `systemPrompt` | 不变 | 可为空 |
| `orderedMessages` | 不变 | 入口校验：最后一条 USER 消息内容非空白 |
| `tools` / `toolNames` | 不变 | 只做 schema 翻译，不执行 |
| `remainingDuration` | 不变 | 入口校验：大于 0；审计层扣除自身耗时后传给适配层 |
| `executionId` / `iteration` | 不变 | 入口校验：`iteration >= 1`；审计关联键 |
| ~~`dispatcher`~~ | **删除** | 连同 10 参数规范构造器与 `withDispatcher(...)`；现有 9 参数构造器成为规范构造器 |

不变量：
- 请求对象不携带任何运行准入能力；provider 层拿不到 `ActionDispatcher`（FR-003）。
- `toString()` 继续只输出 `executionId` 与 `iteration`，不输出消息内容。

## 2. 模型调用审计记录 `ModelCallRecord` / `llm_calls`

结构不变。本次钉住状态序列：

```text
（入口校验失败）            → 无记录，无网络请求
（写 STARTED 失败）         → 无请求发出，RECORDING_UNAVAILABLE
STARTED → SUCCEEDED         → 返回模型回复
STARTED → FAILED(error_code)→ 抛出同一 error_code 的 OryxError
STARTED →（终态写入失败）    → PARTIAL_RECORDING_FAILURE，禁止自动重放
```

重构后的约束：
- `FAILED` 的 `error_code` 只能来自模型服务侧或协议/传输分类：`PROVIDER_AUTHENTICATION`、`PROVIDER_RATE_LIMIT`、
  `PROVIDER_UNAVAILABLE`、`PROVIDER_REQUEST`、`PROVIDER_TIMEOUT`、`PROVIDER_CONNECTION`、`PROVIDER_PROTOCOL`、
  `SESSION_MODEL_CHANGED`、`CONFIGURATION_ERROR`（路由不一致防御）。
- `STARTED` 之后不得再出现 `EXECUTION_STOPPED`、`EXECUTION_PAUSED`、`DEADLINE`（这些只能在准入时、`STARTED` 之前发生）（FR-002）。
- 同一次调用的 `error_code` 与抛给上层的 `OryxError.code()` 相同。

## 3. 测试拆分映射

共用夹具：新建 `oryxos-provider/src/test/java/io/github/oryxlabs/oryxos/provider/ProviderTestSupport.java`（包内抽象类，无类级标签）。
所有专项类都在同一包、带类级 `@Tag("us1-contract")`，方法级标签原样保留。

| 新测试类 | 迁入的原方法（名字不变） | 方法级标签 | 新增方法 |
| --- | --- | --- | --- |
| `ProviderRoutingTest` | `routesTwoProvidersAndAuditsActualModelsWithoutInventingUsage`、`missingCredentialsAndEmptyTasksNeverReachNetwork` | — | `invalidRequestsNeverWriteAuditOrReachNetwork` |
| `ProviderToolSchemaTest` | `returnsStructuredCallsAndReplaysReasoningWithoutExecutingTools`、`mapsMcpNamesAndDetectsCollisionsAndUnknownWireNames` | — | — |
| `ProviderAuditTest` | `classifiesFailuresWithoutRetriesFallbackOrRawResponseLeakage`、`recordingFailuresNeverBecomeSuccessOrTriggerReplay` | — | `failedCallsAreAuditedWithClassifiedCodeBeforeRethrow` |
| `ProviderProtocolTest` | `budgetsCoverHeadersAndBodyAndExpiredRequestsDoNotStart`、`rejectsRedirectsDisconnectRetriesAndForeignContinuation` | — | — |
| `ProviderTransportTest` | `transportTimingSeparatesEncodingHeadersAndBodyWithoutChangingReplies`、`hundredConcurrentCallsEncodeInlineWithContentLengthAndReuseApiInstance`、`concurrentWarmupPrewarmsIdleRouteConnectionsForBurstReuse` | `kernel-provider-timing` | — |
| `ProviderTransportTest` | `requestBodySerializationDoesNotAllocatePerCallPlatformWorkers`、`requestWrappersDoNotAllocateUnownedTransportThreads` | `kernel-provider-resources` | — |
| `ProviderTransportTest` | `reusesConnectionsWithoutSharingTimeoutsOrClosingConcurrentCalls` | `kernel-transport` | — |
| `ProviderAdmissionTest` | — | — | `modelCallHoldsExactlyOneAdmissionWhileInFlight`、`stopAfterAdmissionLetsTheCallFinishAndRecordsActualOutcome` |
| `ProviderArchitectureGuardTest` | — | — | `providerMainCodeUsesOnlyLowLevelOpenAiApi` |

数量核对：原 14 个方法全部迁出，新增 5 个，provider 模块契约类方法合计 19；`ProviderContractTest.java` 删除。

`invalidRequestsNeverWriteAuditOrReachNetwork` 补的是 FR-004 的缺口：原 `missingCredentialsAndEmptyTasksNeverReachNetwork`
只断言没有网络请求，没有断言审计为空。新方法覆盖空任务、剩余时间为零、未知 provider 三种请求，断言错误码分别为
`INVALID_TASK`、`PROVIDER_TIMEOUT`、`PROVIDER_UNAVAILABLE`，`audit` 列表为空，`Receiver.requests` 为空。
它在旧实现上同样为绿（旧入口也在审计前校验），作用是钉住 S4 重构后的行为，不作为红灯证据。
`mapsMcpNamesAndDetectsCollisionsAndUnknownWireNames` 中对 `ToolWireNameMapper.snapshot(...)` 的调用改为
`new ToolNameMap(... ToolNameMap.Entry(internal, ToolNameMap.wireName(internal), serverId, remoteName) ...)`，断言不变。

SC-005 的故意破坏 → 预期唯一失败的专项类：

| 故意破坏 | 预期失败类 |
| --- | --- |
| `ProviderService` 按名取实例时取错（如总取第一个） | `ProviderRoutingTest` |
| provider 主代码引用 `org.springframework.ai.chat.model.ChatModel` | `ProviderArchitectureGuardTest` |
| `AuditedProvider` 失败分支不写 `FAILED` | `ProviderAuditTest` |

按 SC-005：对应专项类必须失败且失败信息指明约束；其它类连带失败不作要求，但在证据中如实列出。
已知连带：错路由时 `ProviderSmokeTest`（两 provider 各自收到请求）也会失败。

## 4. 分组登记 `tools/dev/test-groups.json`

| 分组 | 原登记 | 重构后登记 |
| --- | --- | --- |
| `us1-contract` | `ProviderContractTest` 8 个 + `ProviderSmokeTest` 1 个（provider 模块部分） | `ProviderRoutingTest` 3、`ProviderToolSchemaTest` 2、`ProviderAuditTest` 3、`ProviderProtocolTest` 2、`ProviderAdmissionTest` 2、`ProviderArchitectureGuardTest` 1、`ProviderSmokeTest` 1 |
| `kernel-transport` | `ProviderContractTest` 1 | `ProviderTransportTest` 1 |
| `kernel-provider-resources` | `ProviderContractTest` 2 | `ProviderTransportTest` 2 |
| `kernel-provider-timing` | `ProviderContractTest` 3 | `ProviderTransportTest` 3 |

`us1-contract` 必跑用例总数：15 → 20（其余模块的 6 个不变）。
