# Research: ReAct 边界对齐重构

本特性不引入新依赖、不涉及外部协议，Technical Context 无 NEEDS CLARIFICATION。以下记录实施前必须定下的技术决定。

## R1 `ProfileContext` 的形态与位置

- **Decision**：新增 `oryxos-core` 的 `io.github.oryxlabs.oryxos.core.agent.ProfileContext`，`final` 工具类、私有构造器，
  内含 `private static final ThreadLocal<AgentProfile>`；对外三个静态方法逐字沿用课件字面量：`set(AgentProfile)`、`current()`、`clear()`。
  `current()` 不在处理中时返回 `null`；`clear()` 调用 `ThreadLocal.remove()`。
- **Rationale**：课件与技术方案 §4 都把它写成 `AgentService` 的职责；Profile 在本仓库的类型是 `AgentProfile`（spec 001 FR-002，见 spec“保留的偏差”），
  与它同包可让读者在 `agent` 包内一次看到 Agent 定义、目录、入口与当前上下文。
- **Alternatives considered**：
  - 放 `execution` 包：该包表达执行记录与准入，`ProfileContext` 只关心 Agent 定义，归属不符。
  - 使用 `InheritableThreadLocal`：会把 Profile 带进处理内创建的子线程（如 HTTP 客户端的读取线程），扩大泄漏面；课件不要求。
  - Java 21 `ScopedValue`：在 21 中仍是预览 API，需要 `--enable-preview`，违背宪章技术基线。

## R2 设置与清理的位置

- **Decision**：在 `AgentService.start(...)` 的 `workerTask` 内，`loop.run(ctx, lease)` 所在 `try` 块的**第一句**调用
  `ProfileContext.set(agent)`，在对应 `finally` 块的**第一句**调用 `ProfileContext.clear()`。
- **Rationale**：
  1. `workerTask` 同时承载两条执行路径：异步（虚拟线程执行器）与内联（`withInlineWorker`，HTTP 在调用方线程上执行）。
     放在这里一处即可覆盖两者，满足 FR-002“只在统一入口一处”。
  2. `finally` 第一句先清理，保证清理发生在 `handle.complete(cleanup)` 之前；因此调用方注册的 `cleanup` 回调与等待者被唤醒时，
     当前线程已读不到 Profile。这让“异步路径也已清理”可以被测试观察（在 `cleanup` 回调里读取应为 `null`）。
  3. 会话获取失败、准入拒绝等在 `workerTask` 之前发生的失败从未设置 Profile，无需清理。
- **Alternatives considered**：
  - 包在 `tracedTask` 外层：清理会晚于 `handle.complete`，异步路径的清理无法被观察，且内联调用方在 `await()` 返回前后存在竞态窗口。
  - 恢复先前值而不是 `remove()`：当前不存在嵌套处理（课件明确不做 Agent 互相委托），恢复语义没有使用者，按课件 `clear()`。

## R3 课件“异常时也必须清掉”测试的等价表达

- **Decision**：不引入 Mockito。用 `LoopFixture` 的可控 provider 函数在模型调用时抛出 `RuntimeException("boom")`，
  通过真实 `ReActLoop` 传到 `AgentService` 的意外异常分支；断言执行以 `FAILED` / `EXECUTION_FAILED` 结束，并断言 `ProfileContext.current()` 为 `null`。
- **Rationale**：core 测试依赖中没有 Mockito，历史测试一律用手写假对象；`ReActLoop` 是 `final` 类。`AgentService.process` 按 spec 001
  把意外异常收敛为失败的执行记录而不是向调用方抛出，这是保留偏差；课件断言的本质——“异常路径也清理”——逐条保留。
- **Alternatives considered**：为测试新增 Mockito 依赖——违反 FR-008“不新增第三方依赖”。

## R4 “恰好转满 N 轮”的断言口径

- **Decision**：上限 10，模型每轮都回一个工具调用；断言 provider 被调用恰好 10 次、`stopReason` 为 `ITERATION_LIMIT`、
  回复含“达到迭代上限”；附加断言实际执行的工具恰为 9 次（第 10 轮提议以 `NOT_EXECUTED` 配对回填）。
- **Rationale**：课件 `times(10)` 逐条保留；课件文案“达到最大轮数”在本仓库是已定字面量“达到迭代上限”（FR-007 禁止改动），属保留偏差。
  附加的 9 次断言钉住“最后一轮不执行”的已定行为。

## R5 测试迁移归属

- **Decision**：`ReActContractTest` 的 6 个方法按被测对象拆分：
  `directReplyStopsWithoutToolIo`、`toolsRunSequentiallyAndHistoryContainsPairedMessagesOnce`、`iterationLimitPairsUnexecutedProposalsAndStopsNewIo` → `ReActLoopTest`；
  `bodyAndBootstrapAreReloadedWithoutChangingStartupConfiguration`、`missingOversizedAndSecretBootstrapFailBeforeModelIo`、
  `referencesAreReadOnDemandAndOnlyScriptOutputEntersHistory` → `ContextLoaderTest`。迁空后删除 `ReActContractTest`。
  `FailurePolicyContractTest`（失败纠正策略，同时属 `deadline-boundary` 分组）、`SessionRuntimeContractTest`（会话身份与历史选择）保持原位。
- **Rationale**：Clarifications 确认“迁到课件命名类、方法名保留”；后两类在课件 harness 中无对应行，迁移只会打散其分组归属。
- **Alternatives considered**：把 `safeRetrySuccessResolvesItsAttemptsButUnknownNeverRetries` 迁入 `ToolExecutorTest`——它同时登记在 `deadline-boundary`，
  且断言的是失败策略整体（重试 + 纠正解析），保持原位并在课件对照表中引用。

## R6 `AgentService` 质量基线清零手法

- **Decision**：
  - `AvoidStarImportCheck`：把 `execution.*`、`model.*`、`runtime.*`、`session.*`、`java.time.*`、`java.util.*`、`java.util.concurrent.*` 展开为显式导入。
  - `NeedBracesCheck` / `ControlStatementBraces`：全文件单行 `if/else` 补花括号（只加括号，不改条件与顺序）。
  - `OryxThreadPoolCreation`：`Executors.newSingleThreadScheduledExecutor(factory)` 改为 `new ScheduledThreadPoolExecutor(1, factory)`，
    字段类型仍为 `ScheduledExecutorService`；线程工厂（平台守护线程、名 `oryx-execution-deadlines`）不变。虚拟线程执行器为规则明确允许，不动。
  - 门禁通过后运行 `./tools/dev/quality-baseline --prune` 删除 4 条，再 `--check-shrink origin/main`。
- **Rationale**：`newSingleThreadScheduledExecutor` 只是 `ScheduledThreadPoolExecutor(1, factory)` 的不可重配置包装；该执行器从不对外暴露，
  不存在被重配置的路径，行为等价。不修改 `setRemoveOnCancelPolicy` 等参数，避免引入行为差异。
- **Alternatives considered**：把截止时间调度改为虚拟线程 + `sleep`——改变计时语义与线程数，超出“只清基线”的范围。
