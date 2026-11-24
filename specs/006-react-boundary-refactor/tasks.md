---

description: "Task list for 006-react-boundary-refactor"
---

# Tasks: ReAct 边界对齐重构（对齐第 17 节课件与技术方案）

**Input**: Design documents from `specs/006-react-boundary-refactor/`

**Prerequisites**: [plan.md](./plan.md)、[spec.md](./spec.md)、[research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/profile-context.md](./contracts/profile-context.md)、[quickstart.md](./quickstart.md)

**Tests**: 规格明确要求测试（FR-004、FR-005、SC-001 ~ SC-005），US1 要求先红后绿，因此包含测试任务。

**Organization**: 纯迁移是后续测试文件的共同布局，放 Foundational；US1 = `ProfileContext`；US2 = 补齐课件 harness；US3 = 课件修订建议；基线清零放 Polish。

## Format: `[ID] [P?] [Story] Description`

- **[P]**：可并行（不同文件、无未完成依赖）
- **[Story]**：所属用户故事（US1 / US2 / US3）
- 路径缩写：`C/` = `oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/`，
  `CT/` = `oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/`，
  `EV/` = `specs/006-react-boundary-refactor/evidence/`
- 所有构建命令都经 `./tools/dev/mvnw`，先 `export ORYXOS_JAVA_HOME=<JDK21>`；禁止 `-Dtest`、`-Dsurefire.*`、`-DskipTests`
- 新测试类一律 `@Tag("us2-loop")`、每个用例独立 `@TempDir` 子目录、复用 `CT/react/LoopFixture.java`（不改夹具）；方法名英文，课件中文名放 `@DisplayName`

---

## Phase 1: Setup（基线快照）

**Purpose**: 记录改动前的可比数字（plan S0）

- [X] T001 创建 `EV/baseline-snapshot.md`，记录：`git rev-parse HEAD`；`config/quality/baseline.json` 的 `entries` 总数（当前 448）及 `C/agent/AgentService.java` 的 4 条明细；`CT/react/ReActContractTest.java` 的 6 个方法名（排序后存为 `EV/react-contract-methods-before.txt`）；`tools/dev/test-groups.json` 中 `us2-loop` 登记数（当前 30）
- [X] T002 运行 `./tools/dev/mvnw -B -ntp test -Dgroups=us2-loop` 与 `-Dgroups=deadline-boundary`，确认改动前全绿，把 `Test evidence PASS` 行追加到 `EV/baseline-snapshot.md`

---

## Phase 2: Foundational（纯迁移）

**Purpose**: 把 `ReActContractTest` 按被测对象拆到课件命名类，只移动不改断言（plan S1，FR-004，research R5）

**⚠️ CRITICAL**: 本阶段 diff 中不得出现断言变化

- [X] T003 [P] 新建 `CT/react/ReActLoopTest.java`（`@Tag("us2-loop")`），原样迁入 `directReplyStopsWithoutToolIo`、`toolsRunSequentiallyAndHistoryContainsPairedMessagesOnce`、`iterationLimitPairsUnexecutedProposalsAndStopsNewIo`
- [X] T004 [P] 新建 `CT/context/ContextLoaderTest.java`（`@Tag("us2-loop")`），原样迁入 `bodyAndBootstrapAreReloadedWithoutChangingStartupConfiguration`、`missingOversizedAndSecretBootstrapFailBeforeModelIo`、`referencesAreReadOnDemandAndOnlyScriptOutputEntersHistory`；`LoopFixture` 的静态工厂改用显式 `import static io.github.oryxlabs.oryxos.core.react.LoopFixture.*` 引用
- [X] T005 删除 `CT/react/ReActContractTest.java`（依赖 T003、T004）
- [X] T006 更新 `tools/dev/test-groups.json` 的 `us2-loop`：去掉 `ReActContractTest` 条目，新增 `ReActLoopTest`（3 方法）与 `ContextLoaderTest`（3 方法），登记数仍为 30
- [X] T007 运行 `./tools/dev/mvnw -B -ntp test -Dgroups=us2-loop` 全绿；按 quickstart §3 比对方法名，结果写入 `EV/split-verification.md`

**Checkpoint**: 迁移完成，后续故事在新文件上增量修改

---

## Phase 3: User Story 1 - 工具执行期间能取到当前 Agent，且不串号 (Priority: P1) 🎯 MVP

**Goal**: 新增 `ProfileContext`，由 `AgentService` 设置与清理（FR-001 ~ FR-003，contracts PC1 ~ PC5）

**Independent Test**: `AgentServiceTest` 4 项全绿；quickstart §4 搜索符合预期

### Tests for User Story 1（先红）

- [X] T008 [US1] 新建 `CT/agent/AgentServiceTest.java`（`@Tag("us2-loop")`），按 `CT/runtime/DeadlineRaceTest.java` 方式用 `LoopFixture` 的 `catalog()`、`sessions`、`loop()`、`registry`、`gate`、`clock` 装配真实 `AgentService`，手写 `ExecutionStore`；四个用例：
  - `profileContextIsAvailableDuringToolExecution`：工具体内记录 `ProfileContext.current()` 与 `ctx.agent()`，断言 `assertSame`（PC1）
  - `profileContextIsClearedWhenProcessingThrows`（`@DisplayName("处理中抛异常_ProfileContext也必须被清掉")`）：provider 函数抛 `RuntimeException("boom")`；经 `AgentService.withInlineWorker` 在当前线程执行；断言快照 `FAILED` 且 `errorCode == EXECUTION_FAILED`，`assertNull(ProfileContext.current())`（PC2，research R3）
  - `profileContextIsClearedBeforeCompletionOnWorkerThread`：异步 `start(request, s -> {}, cleanup)`，`cleanup` 中记录 `ProfileContext.current()`；同样让 provider 抛异常；`await()` 后断言记录值为 `null`（PC3）
  - `sessionMessagesArePersistedWhenProcessingEnds`：一轮工具 + 最终答复；结束后 `LoopFixture.store.messages` 中本次 USER、ASSISTANT（含工具调用）、TOOL、ASSISTANT 四条按序存在，且该轮被标记完成
- [X] T009 [US1] 在 `tools/dev/test-groups.json` 的 `us2-loop` 登记 `AgentServiceTest` 4 方法（30 → 34）；运行 `./tools/dev/mvnw -B -ntp -pl oryxos-core -am test-compile` 记录编译失败（`ProfileContext` 不存在）到 `EV/red-green.md`

### Implementation for User Story 1

- [X] T010 [US1] 新建 `C/agent/ProfileContext.java`：`public final class`、私有构造器、`private static final ThreadLocal<AgentProfile>`；`set(AgentProfile)`、`current()`、`clear()`（`remove()`）；类注释只写约束：仅 `AgentService` 写入、处理结束前必清（research R1）
- [X] T011 [US1] 修改 `C/agent/AgentService.java` 的 `workerTask`：`loop.run(ctx, lease)` 所在 `try` 块第一句 `ProfileContext.set(agent)`，对应 `finally` 块第一句 `ProfileContext.clear()`；不改其余逻辑（research R2）
- [X] T012 [US1] 运行 `./tools/dev/mvnw -B -ntp test -Dgroups=us2-loop`，`AgentServiceTest` 4 项转绿；把红→绿记录追加到 `EV/red-green.md`；按 quickstart §4 搜索并写入 `EV/review-us1.md`（对照 PC1 ~ PC5 审查 diff）

**Checkpoint**: US1 可独立验证

---

## Phase 4: User Story 2 - 课件验收 harness 每点可按名定位 (Priority: P1)

**Goal**: 补齐课件 harness 缺失的回归测试（FR-005、FR-006，data-model §2/§3）

**Independent Test**: `us2-loop` 44 项全绿；data-model §3 每个子项都有对应测试

- [X] T013 [P] [US2] 在 `CT/react/ReActLoopTest.java` 新增：
  - `modelAlwaysRequestingToolsStopsAtMaxIterations`（`@DisplayName("模型一直要调工具_转满最大轮数强制停")`）：`LoopFixture(root, 10)`，provider 函数每轮返回一个新 ID 的 `first` 工具调用；断言 `requests.size() == 10`、`stopReason == "ITERATION_LIMIT"`、`finalContent` 含“达到迭代上限”、`effects.size() == 9`（research R4）
  - `everyRoundAccumulatesReplyAndToolResultsInSession`：三轮（工具、工具、答复）；断言 `store.messages` 本轮消息依次为 USER、ASSISTANT、TOOL、ASSISTANT、TOOL、ASSISTANT，且每个 TOOL 的 `toolCallId` 与前一条 ASSISTANT 的调用 ID 一致
- [X] T014 [P] [US2] 新建 `CT/context/PromptBuilderTest.java`（`@Tag("us2-loop")`），直接调用 `new PromptBuilder(new ContextLoader()).build(fixture.context, memory, tools, 1)`：
  - `systemPromptOrdersBodyBootstrapDateTimeThenMemory`：`ContextMemory` 的 core/archival 用唯一标记；断言 `systemPrompt` 中正文标记 < Bootstrap 标记 < `Current workspace time` 且含 `2026-11-13` < CORE 标记 < ARCHIVAL 标记
  - `historyPrecedesCurrentTurnAndToolsAreSeparate`：`recent` 两条、`current` 一条；断言 `orderedMessages` 为 recent 后接 current，工具 schema 只出现在 `tools` 字段、不进入 `systemPrompt`
  - `requestHistoryIsBoundedByMaxHistoryTurns`：按 `SessionRuntimeContractTest` 方式写入 25 个完整轮次后跑 `fixture.run()`（单次直答）；断言首个请求中历史 USER 消息为 20 条、首条为 `turn5`，`store.messages` 条数未减少
- [X] T015 [P] [US2] 新建 `CT/tool/ToolExecutorTest.java`（`@Tag("us2-loop")`），用 `LoopFixture` 的 `registry`、`context` 与自定义 `AuditSink` 构造 `ToolExecutor`：
  - `successfulToolWritesStartedThenSuccessAudit`：审计依次 `STARTED`、`SUCCESS`，同一 `toolCallId`
  - `failedToolWritesFailureAuditWithErrorCode`：工具返回 `ToolResult.failure(ERROR, "BOOM", ...)`；终态审计 `ERROR` 且 `errorCode == "BOOM"`
  - `throwingToolIsAuditedNotSwallowed`：只读工具抛 `RuntimeException`；返回结果 `ERROR`/`TOOL_FAILED`，终态审计同码，且审计中不含异常原文
  - `auditWriteFailureIsRethrownAsRecordingFailure`：`AuditSink.toolCall` 抛异常；`assertThrows(OryxError)` 且 `code == "RECORDING_FAILURE"`，工具未执行
- [X] T016 [P] [US2] 在 `CT/context/ContextLoaderTest.java` 新增 `loadRereadsFilesOnEveryCall`：同一 `ContextLoader` 连续两次 `load(fixture.context)`，中间改写 `AGENT.md` 正文与 `USER.md`；第二次结果含新内容、不含旧内容
- [X] T017 [US2] 在 `tools/dev/test-groups.json` 的 `us2-loop` 登记 T013 ~ T016 新增的 10 个方法（34 → 44）
- [X] T018 [US2] 运行 `./tools/dev/mvnw -B -ntp test -Dgroups=us2-loop`，44 项全绿；若有红灯按“实现错修实现、测试错停下报告”处理；按 data-model §3 逐子项对号，写入 `EV/review-us2.md`

**Checkpoint**: 课件 harness 全部可定位

---

## Phase 5: User Story 3 - 课件差异有据可查 (Priority: P2)

**Goal**: FR-010

- [X] T019 [US3] 新建 `specs/006-react-boundary-refactor/lesson-17-revision.md`：一、正文写法对照（spec“保留的偏差”9 条 + `ProfileContext` 新增，每条含课件原写法 / 现行写法 / 代码位置 / 依据）；二、第四部分测试类名对照（data-model §2）；三、课件可直接沿用的部分；逐条核对代码位置真实存在，写入 `EV/review-us3.md`

---

## Phase 6: Polish & Cross-Cutting

- [X] T020 修改 `C/agent/AgentService.java` 清基线（research R6）：展开星号导入；全文件单行 `if/else` 补花括号；`Executors.newSingleThreadScheduledExecutor(factory)` → `new ScheduledThreadPoolExecutor(1, factory)`；运行 `./tools/dev/mvnw spotless:apply`；用 `git diff -w` 确认除括号/导入/构造外无语义变化
- [X] T021 运行 `./tools/dev/mvnw clean verify` 全绿；`./tools/dev/quality-baseline --prune`；`--check-shrink origin/main`；`rg -c 'AgentService.java' config/quality/baseline.json` 为 0；数字写入 `EV/baseline-snapshot.md` 末尾（448 → 444）
- [X] T022 按 quickstart §7 做两次故意破坏并恢复，失败用例与信息写入 `EV/breakage.md`
- [X] T023 [P] 更新 `tools/dev/README.md` 的 `us2-loop` 登记数与说明（30 → 44，补 `ProfileContext`、课件五测试类）
- [X] T024 [P] 更新 `specs/001-single-node-agent-runtime/verification.md` 第 39 行 `us2-loop` 的类清单（`ReActContractTest` → `ReActLoopTest`、`ContextLoaderTest`、`PromptBuilderTest`、`ToolExecutorTest`、`AgentServiceTest`）
- [X] T025 最终 `./tools/dev/mvnw clean verify` 全绿；按 quickstart §1 ~ §6 逐节核对并记录到 `EV/final-verification.md`

---

## Dependencies & Execution Order

- Setup（T001–T002）→ Foundational（T003–T007）→ US1（T008–T012）与 US2（T013–T018）可交替，但 `test-groups.json` 的修改（T009、T017）串行
- US3（T019）依赖 US1、US2 完成（需要真实代码位置与测试名）
- Polish：T020 依赖 T011（同一文件，避免冲突）；T021 依赖 T020；T022 依赖 T012、T018；T023、T024 可并行；T025 最后

## Parallel Example

```text
T003 ReActLoopTest 迁移 ‖ T004 ContextLoaderTest 迁移
T013 ReActLoopTest 新增 ‖ T014 PromptBuilderTest ‖ T015 ToolExecutorTest ‖ T016 ContextLoaderTest 新增
T023 README ‖ T024 verification.md
```

## Implementation Strategy

先完成 Foundational 纯迁移并单独验证（便于审查 diff 只有移动），再做 US1（唯一主代码新增，先红后绿），随后补齐 US2 harness，
最后 Polish 清基线与全量回归。全程不自动 commit / push。
