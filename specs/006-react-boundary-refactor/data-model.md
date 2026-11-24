# Data Model: ReAct 边界对齐重构

本特性不新增、不修改任何持久化实体与表结构（FR-007、FR-008）。这里记录唯一新增的运行时概念，以及测试归属这一“数据”。

## 1. 当前 Agent 上下文（`ProfileContext`）

| 属性 | 说明 |
| --- | --- |
| 值类型 | `AgentProfile`（只读引用，与 `ExecutionContext.agent()` 为同一实例） |
| 作用域 | 当前线程；只在一次处理的 `ReActLoop.run` 期间有值 |
| 写入者 | 仅 `AgentService` 的处理工作单元（R2） |
| 读取者 | 任意代码，通过 `ProfileContext.current()`；不在处理中返回 `null` |

**状态迁移**

```text
空 ──set(agent)（循环开始前）──▶ 有值 ──clear()（finally 第一句；正常/OryxError/RuntimeException/Error 均经过）──▶ 空
```

不变量：
- C1：处理期间 `current() == ctx.agent()`。
- C2：`handle.complete(...)` 触发任何回调或唤醒等待者之前，执行线程上 `current()` 已为 `null`。
- C3：`workerTask` 之外的任何代码不调用 `set` / `clear`。

## 2. 测试归属（课件 harness → 现行测试）

| 课件测试类 | 现行测试类（模块 `oryxos-core`，标签 `us2-loop`） | 方法（迁入 / 新增） |
| --- | --- | --- |
| `ReActLoopTest` | `core.react.ReActLoopTest` | 迁入：`directReplyStopsWithoutToolIo`、`toolsRunSequentiallyAndHistoryContainsPairedMessagesOnce`、`iterationLimitPairsUnexecutedProposalsAndStopsNewIo`；新增：`modelAlwaysRequestingToolsStopsAtMaxIterations`（显示名“模型一直要调工具_转满最大轮数强制停”）、`everyRoundAccumulatesReplyAndToolResultsInSession` |
| `PromptBuilderTest` | `core.context.PromptBuilderTest` | 新增：`systemPromptOrdersBodyBootstrapDateTimeThenMemory`、`historyPrecedesCurrentTurnAndToolsAreSeparate`、`requestHistoryIsBoundedByMaxHistoryTurns` |
| `ToolExecutorTest` | `core.tool.ToolExecutorTest` | 新增：`successfulToolWritesStartedThenSuccessAudit`、`failedToolWritesFailureAuditWithErrorCode`、`throwingToolIsAuditedNotSwallowed`、`auditWriteFailureIsRethrownAsRecordingFailure` |
| `AgentServiceTest` | `core.agent.AgentServiceTest` | 新增：`profileContextIsAvailableDuringToolExecution`、`profileContextIsClearedWhenProcessingThrows`（显示名“处理中抛异常_ProfileContext也必须被清掉”）、`profileContextIsClearedBeforeCompletionOnWorkerThread`、`sessionMessagesArePersistedWhenProcessingEnds` |
| `ContextLoaderTest` | `core.context.ContextLoaderTest` | 迁入：`bodyAndBootstrapAreReloadedWithoutChangingStartupConfiguration`、`missingOversizedAndSecretBootstrapFailBeforeModelIo`、`referencesAreReadOnDemandAndOnlyScriptOutputEntersHistory`；新增：`loadRereadsFilesOnEveryCall` |

合计：迁入 6、新增 14；删除 `core.react.ReActContractTest`（6 个方法全部迁出）。`us2-loop` 登记数 30 → 44。

`LoopFixture` 位于 `core.react` 包且为 `public`，新测试类跨包直接复用，不复制夹具。

## 3. 课件 harness 子项覆盖

| 课件行 | 子项 | 覆盖测试 |
| --- | --- | --- |
| ReActLoopTest | 无工具调用一轮收尾 | `directReplyStopsWithoutToolIo` |
| | 有工具调用则执行并回填进下一轮 | `toolsRunSequentiallyAndHistoryContainsPairedMessagesOnce` |
| | 转满最大轮数强制停 | `modelAlwaysRequestingToolsStopsAtMaxIterations`、`iterationLimitPairsUnexecutedProposalsAndStopsNewIo` |
| | 每轮响应和工具结果累积进 Session | `everyRoundAccumulatesReplyAndToolResultsInSession` |
| PromptBuilderTest | 四部分顺序 | `systemPromptOrdersBodyBootstrapDateTimeThenMemory`、`historyPrecedesCurrentTurnAndToolsAreSeparate` |
| | 历史超 N 轮被截断 | `requestHistoryIsBoundedByMaxHistoryTurns` |
| | system prompt 含当前日期时间（位于正文与 Bootstrap 之后） | `systemPromptOrdersBodyBootstrapDateTimeThenMemory` |
| ToolExecutorTest | 成功写审计 | `successfulToolWritesStartedThenSuccessAudit` |
| | 失败也写审计带原因 | `failedToolWritesFailureAuditWithErrorCode` |
| | 异常不吞 | `throwingToolIsAuditedNotSwallowed`、`auditWriteFailureIsRethrownAsRecordingFailure` |
| AgentServiceTest | 处理期间可取当前 Profile | `profileContextIsAvailableDuringToolExecution` |
| | 抛异常时 finally 也清掉 | `profileContextIsClearedWhenProcessingThrows`、`profileContextIsClearedBeforeCompletionOnWorkerThread` |
| | 结束后 Session 被持久化 | `sessionMessagesArePersistedWhenProcessingEnds` |
| ContextLoaderTest | 改文件后下一次立即读到 | `loadRereadsFilesOnEveryCall`、`bodyAndBootstrapAreReloadedWithoutChangingStartupConfiguration` |
| | 显式引用缺失报错 | `missingOversizedAndSecretBootstrapFailBeforeModelIo` |
| | Bootstrap 缺失 WARN | 保留偏差：缺失即失败（更严格），同上测试覆盖 |
