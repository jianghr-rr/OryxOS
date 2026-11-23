---

description: "Task list for 005-provider-boundary-refactor"
---

# Tasks: Provider 边界重构（对齐第 16 节课件与技术方案）

**Input**: Design documents from `specs/005-provider-boundary-refactor/`

**Prerequisites**: [plan.md](./plan.md)、[spec.md](./spec.md)、[research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/provider-port.md](./contracts/provider-port.md)、[quickstart.md](./quickstart.md)

**Tests**: 规格明确要求测试（FR-008、FR-009、SC-001、SC-002、SC-005），且 US1 要求先红后绿，因此包含测试任务。

**Organization**: 按 spec 的三个用户故事分阶段。测试拆分是所有故事共同修改的文件布局，放在 Foundational；基线清零与文档在 Polish。

## Format: `[ID] [P?] [Story] Description`

- **[P]**：可并行（不同文件、无未完成依赖）
- **[Story]**：所属用户故事（US1 / US2 / US3）
- 路径缩写：`P/` = `oryxos-provider/src/main/java/io/github/oryxlabs/oryxos/provider/`，
  `PT/` = `oryxos-provider/src/test/java/io/github/oryxlabs/oryxos/provider/`，
  `C/` = `oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/`，
  `EV/` = `specs/005-provider-boundary-refactor/evidence/`
- 所有构建命令都经 `./tools/dev/mvnw`，先 `export ORYXOS_JAVA_HOME=<JDK21>`；禁止 `-Dtest`、`-Dsurefire.*`、`-DskipTests`

---

## Phase 1: Setup（基线快照）

**Purpose**: 记录重构前的可比数字，供后续“只减不增”“一个不少”核对（plan S0）

- [X] T001 创建 `EV/baseline-snapshot.md`，记录：`git rev-parse HEAD`；`config/quality/baseline.json` 的 `entries` 总数（当前 460）及 `oryxos-provider` 主代码条目 28 条明细；`PT/ProviderContractTest.java` 的 14 个测试方法名（`rg -o 'void (\w+)\(\) throws' -r '$1'`，排序后存为 `EV/contract-methods-before.txt`）；`tools/dev/test-groups.json` 中 `us1-contract` 必跑方法数（当前 15）
- [X] T002 运行 `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract` 与 `-Dgroups=kernel-provider-timing,kernel-provider-resources,kernel-transport`，确认重构前全绿，把 `Test evidence PASS` 行追加到 `EV/baseline-snapshot.md`

---

## Phase 2: Foundational（夹具抽取与纯移动拆分）

**Purpose**: 把 1010 行的契约测试拆成专项类，只移动不改断言，后续各故事在新文件上增改（plan S1，FR-008）

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事；本阶段 diff 中不得出现断言变化

- [X] T003 新建 `PT/ProviderTestSupport.java`：包内 `abstract class`，**无类级 `@Tag`**；从 `PT/ProviderContractTest.java` 原样迁入 `@TempDir Path root`、`JSON`、`SECRET`、`audit`（`CopyOnWriteArrayList<ModelCallRecord>`）、`redactor`、`owned` 列表与 `@AfterEach closeRegistries()`、静态内部类 `Receiver`、`ordinary(...)`、`definition(...)`、`registry(...)`（暂仍返回 `ProviderRegistry`）、两个 `request(...)` 重载、`assertCode(...)`；可见性改为包内 `protected`/包级，供子类使用
- [X] T004 [P] 新建 `PT/ProviderRoutingTest.java`（`@Tag("us1-contract")`，`extends ProviderTestSupport`），原样迁入 `routesTwoProvidersAndAuditsActualModelsWithoutInventingUsage`、`missingCredentialsAndEmptyTasksNeverReachNetwork`
- [X] T005 [P] 新建 `PT/ProviderToolSchemaTest.java`（同上标签与继承），原样迁入 `returnsStructuredCallsAndReplaysReasoningWithoutExecutingTools`、`mapsMcpNamesAndDetectsCollisionsAndUnknownWireNames`（此阶段仍调用 `ToolWireNameMapper`，US2 再替换）
- [X] T006 [P] 新建 `PT/ProviderAuditTest.java`（同上），原样迁入 `classifiesFailuresWithoutRetriesFallbackOrRawResponseLeakage`、`recordingFailuresNeverBecomeSuccessOrTriggerReplay`
- [X] T007 [P] 新建 `PT/ProviderProtocolTest.java`（同上），原样迁入 `budgetsCoverHeadersAndBodyAndExpiredRequestsDoNotStart`、`rejectsRedirectsDisconnectRetriesAndForeignContinuation`
- [X] T008 [P] 新建 `PT/ProviderTransportTest.java`（类级 `@Tag("us1-contract")`，与原类一致），原样迁入 6 个方法并**保留方法级标签**：`kernel-provider-timing` ×3（`transportTimingSeparatesEncodingHeadersAndBodyWithoutChangingReplies`、`hundredConcurrentCallsEncodeInlineWithContentLengthAndReuseApiInstance`、`concurrentWarmupPrewarmsIdleRouteConnectionsForBurstReuse`）、`kernel-provider-resources` ×2（`requestBodySerializationDoesNotAllocatePerCallPlatformWorkers`、`requestWrappersDoNotAllocateUnownedTransportThreads`）、`kernel-transport` ×1（`reusesConnectionsWithoutSharingTimeoutsOrClosingConcurrentCalls`）；只属于这些方法的私有辅助方法一并迁入
- [X] T009 删除 `PT/ProviderContractTest.java`（依赖 T003–T008）；确认无残留私有辅助方法被遗漏（编译通过即证明）
- [X] T010 更新 `tools/dev/test-groups.json`：`us1-contract`、`kernel-transport`、`kernel-provider-resources`、`kernel-provider-timing` 四个分组中 `io.github.oryxlabs.oryxos.provider.ProviderContractTest` 条目按 [data-model.md §4](./data-model.md#4-分组登记-toolsdevtest-groupsjson) 拆为新类条目，**方法名不变**，`us1-contract` 本阶段仍为 15 个
- [X] T011 执行 [quickstart.md](./quickstart.md) §1（预期报告 13，与重构前相同；登记总数仍为 15）、§2（6 个来自 `ProviderTransportTest`）、§3（`comm` 无输出，before 取 `EV/contract-methods-before.txt`），结果写入 `EV/split-verification.md`

**Checkpoint**: 拆分完成，测试数与方法名与重构前一致

---

## Phase 3: User Story 1 - 一次模型调用只有一个准入和一次校验 (Priority: P1) 🎯 MVP

**Goal**: 在途计数从 3 降为 1；准入后被停止的调用跑完并按真实结果记审计；非法请求只在入口被拒一次，且不写审计、不发请求（FR-001 ~ FR-005）

**Independent Test**: `ProviderAdmissionTest` 两个用例经真实 `ReActLoop` + `PromptBuilder` 路径，在旧实现上红、重构后绿；`ProviderRoutingTest#invalidRequestsNeverWriteAuditOrReachNetwork` 重构前后都绿；`ProviderAuditTest`、`ProviderProtocolTest` 保持绿

### Tests for User Story 1（准入两个用例必须先红）

- [X] T012 [US1] 新建 `PT/ProviderAdmissionTest.java`（`@Tag("us1-contract")`，`extends ProviderTestSupport`）。在测试内用 core 主代码自建最小执行上下文（参照 `oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/react/LoopFixture.java` 的组装顺序，但不跨模块引用它）：`RuntimeGate`（`readyAtFirstStartup()`）、`Deadline`、`gate.execution(deadline)` 得到 permit、`new ActionDispatcher(permit)`、`ExecutionContext`、`SessionManager` + 内存 `SessionStore`、`AgentProfile`（provider 名指向回环 `Receiver`），以真实 `new ReActLoop(providerRegistry, 空工具表, ToolExecutor, 记忆函数, new PromptBuilder(new ContextLoader()))` 运行一次。审计 sink 同时收集 `ModelCallRecord`
- [X] T013 [US1] 在 `PT/ProviderAdmissionTest.java` 编写 `modelCallHoldsExactlyOneAdmissionWhileInFlight`：`Receiver` 处理函数内读取 `gate.inFlight()` 存入原子变量后正常回复；断言该值 `== 1`、执行结束后 `gate.inFlight() == 0`
- [X] T014 [US1] 在 `PT/ProviderAdmissionTest.java` 编写 `stopAfterAdmissionLetsTheCallFinishAndRecordsActualOutcome`：审计 sink 收到 `status == "STARTED"` 的模型记录时调用 `permit.stop()`；断言 `Receiver.requests.size() == 1`、该 callId 终态记录 `status == "SUCCEEDED"`、全部模型记录中不存在 `errorCode` 为 `EXECUTION_STOPPED`/`EXECUTION_PAUSED`/`DEADLINE` 的记录（执行随后被 ReAct 结束属预期，不作断言失败）
- [X] T015 [P] [US1] 在 `PT/ProviderRoutingTest.java` 新增 `invalidRequestsNeverWriteAuditOrReachNetwork`（FR-004，契约不变量 P8）：分别发起空任务、`remainingDuration` 为零、未知 provider 名三种请求；断言错误码依次为 `INVALID_TASK`、`PROVIDER_TIMEOUT`、`PROVIDER_UNAVAILABLE`，`audit` 列表为空，`Receiver.requests` 为空。该用例在旧实现上同样为绿，用于钉住 T021 迁移 validate 后的行为
- [X] T016 [US1] 在 `tools/dev/test-groups.json` 的 `us1-contract` 新增 T013、T014、T015 三个方法（登记合计 18；`-pl oryxos-provider -am` 报告值为 16）；运行 `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract`，确认**仅 T013、T014 两个用例失败**（预期实测 `inFlight=3`、出现 `FAILED/EXECUTION_STOPPED`），T015 为绿；把结果摘要写入 `EV/red-green.md`

### Implementation for User Story 1

- [X] T017 [US1] 修改 `C/provider/ProviderRequest.java`：删除 `dispatcher` 组件、10 参数规范构造器与 `withDispatcher(...)`；原 9 参数构造器成为规范构造器，保留紧凑构造器中的 `List.copyOf` 与三个 `requireNonNull`；`withRemainingDuration` 去掉 dispatcher 参数；`toString()` 不变（data-model §1：“请求对象不携带任何运行准入能力”）
- [X] T018 [US1] 修改 `C/context/PromptBuilder.java`：删除第 42 行 `.withDispatcher(context.dispatcher())`；`C/react/ReActLoop.java` 第 58 行的 `ctx.dispatcher().call(...)` **保持不动**（唯一准入）
- [X] T019 [US1] 修改 `P/AuditedProvider.java`：删除 `request.dispatcher()` 分支，直接 `delegate.call(remaining)`；catch 改为 `OryxError error = raw instanceof OryxError known ? known : ProviderErrorMapper.protocol();`，写 `FAILED`（`error.code()`）后 `throw error`；不再调用 `ProviderErrorMapper.failure`；继续使用 `Uuids.v4()`（`HttpCompletionResumptionTest` 扫描其字节码禁止 `randomUUID`）
- [X] T020 [US1] 修改 `P/SpringAiProvider.java`：删除 `call()` 开头的 `validate(request)`，只保留 provider 名与自身定义一致的防御检查；删除 `request.dispatcher()` 分支，transport 闭包直接 `client.chatCompletionEntity(wire).getBody()`；删除第 40–56 行多余的 `{ }` 代码块；保留 `provider.transport`、`provider.reply` 计时段名与 catch 中的超时判定和 `ProviderErrorMapper.failure`（唯一映射点，contracts §1）；把静态 `validate` 移出（见 T021）
- [X] T021 [US1] 修改 `P/ProviderRegistry.java`：把 `validate(ProviderRequest)` 作为私有静态方法迁入，逻辑与错误码不变（`CONFIGURATION_ERROR` model/iteration、`INVALID_TASK`、`PROVIDER_TIMEOUT`），调用顺序为“关闭检查 → validate → 查表 → 审计包装”
- [X] T022 [US1] 修复编译：全仓 `rg -n 'withDispatcher|\.dispatcher\(\)' oryxos-*/src --type java` 中凡作用于 `ProviderRequest` 的调用一并删除（预期只在 T017–T020 涉及的文件）；`ExecutionContext.dispatcher()` 的其它用法不动
- [X] T023 [US1] 运行 `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract` 与 `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=kernel-provider-timing,kernel-provider-resources,kernel-transport,us2-loop,deadline-boundary`（`-am` 带上 core；分组中 tool/boot 模块的用例不在本次反应堆内，由入口按模块过滤），确认 T013、T014 转绿、其余全绿；结果追加到 `EV/red-green.md`

### Story Review for User Story 1（宪章 VII）

- [X] T024 [US1] 故事级复查，写入 `EV/review-us1.md`：对照 `contracts/provider-port.md` 不变量 P1、P2、P7、P8 与 FR-001 ~ FR-005 逐条审查 T017–T022 的 diff（准入只剩 `ReActLoop` 一处、`ProviderRequest` 无准入句柄、validate 只在入口、`SpringAiProvider` 是唯一映射点、错误码集合未增减）；引用 T016、T023 的命令结果作为行为证据；列出未覆盖项或偏差

**Checkpoint**: US1 可独立演示——在途计数 1、准入后停止不误记失败、非法请求零审计、错误码集合不变

---

## Phase 4: User Story 2 - 课件与技术方案中的概念能在代码里一一找到 (Priority: P1)

**Goal**: 入口命名为 `ProviderService`；删除死代码 `ToolWireNameMapper`（FR-006、FR-007）

**Independent Test**: quickstart §4 三条搜索符合预期；boot 装配启动测试通过

### Implementation for User Story 2

- [X] T025 [US2] `git mv P/ProviderRegistry.java P/ProviderService.java`，类名、构造器名改为 `ProviderService`；`implements ProviderPort, AutoCloseable` 与构造参数不变
- [X] T026 [US2] 修改 `oryxos-boot/src/main/java/io/github/oryxlabs/oryxos/boot/config/ProviderConfiguration.java` 第 9、46 行的导入与构造为 `ProviderService`；Bean 返回类型与方法名中的 `Registry` 字样同步改名（若有）
- [X] T027 [P] [US2] 更新测试引用为 `ProviderService`：`PT/ProviderTestSupport.java`（`owned` 列表、`registry(...)` 返回类型，方法名可保留 `registry`）、`PT/ProviderAdmissionTest.java`、`PT/ProviderSmokeTest.java`（含第 75 行演示文案 `explicit ProviderRegistry` → `explicit ProviderService`）、`PT/RealProviderIT.java`、`PT/ProviderConcurrencyProbeIT.java`
- [X] T028 [US2] 删除 `P/ToolWireNameMapper.java`；`PT/ProviderToolSchemaTest.java` 中 `mapsMcpNamesAndDetectsCollisionsAndUnknownWireNames` 的 `ToolWireNameMapper.snapshot(List.of(new ToolWireNameMapper.Name(a, b, c), ...))` 改为 `new ToolNameMap(List.of(new ToolNameMap.Entry(a, ToolNameMap.wireName(a), b, c), ...))`，断言不变
- [X] T029 [US2] 执行 quickstart §4 三条搜索（排除 `specs/001`~`004` 与 `specs/005`），并运行 `./tools/dev/mvnw -B -ntp -pl oryxos-boot -am test -Dgroups=us1-contract,f0-foundation`；结果写入 `EV/rename-verification.md`

### Story Review for User Story 2（宪章 VII）

- [X] T030 [US2] 故事级复查，写入 `EV/review-us2.md`：对照 FR-006、FR-007 与 spec US2 三个验收场景审查 T025–T028 的 diff（只改入口一个类名、`AuditedProvider`/`SpringAiProvider` 未改名、历史证据未改写、无残留旧名与死代码）；引用 T029 结果；逐行核对 spec“课件原则与当前代码对照”表能定位到重构后的类（SC-004）

**Checkpoint**: US2 可独立验证——按课件名可直达入口，无死代码

---

## Phase 5: User Story 3 - 课件点名的每个坑都有独立可定位的回归测试 (Priority: P2)

**Goal**: 补齐失败审计专项测试与“不接入框架自动工具执行”的结构守护；三类故意破坏各自命中对应专项类（FR-008、FR-009、SC-005）

**Independent Test**: `us1-contract` 必跑 20 全绿；三次故意破坏中对应专项类均失败且失败信息指明约束

### Tests for User Story 3

- [X] T031 [P] [US3] 在 `PT/ProviderAuditTest.java` 新增 `failedCallsAreAuditedWithClassifiedCodeBeforeRethrow`：`Receiver` 分别返回 503 与“响应延迟超过预算”两种情况；断言抛出 `PROVIDER_UNAVAILABLE` / `PROVIDER_TIMEOUT`，且同一 `callId` 依次有 `STARTED` 与 `FAILED` 两条记录，`FAILED.errorCode()` 等于抛出的错误码，`audit.toString()` 不含 `SECRET` 与 `private.invalid`
- [X] T032 [P] [US3] 新建 `PT/ProviderArchitectureGuardTest.java`（`@Tag("us1-contract")`，不继承夹具），方法 `providerMainCodeUsesOnlyLowLevelOpenAiApi`：遍历 `oryxos-provider/src/main/java` 下全部 `.java` 文本（路径从测试工作目录解析，模块根为 `Path.of("src/main/java")`）；凡出现 `org.springframework.ai.` 前缀必须以 `org.springframework.ai.openai.api.` 开头；且不得出现标识符 `ChatModel`、`ChatClient`、`ToolCallback`、`ToolCallingManager`、`internalToolExecution`；失败信息包含“Provider 不得接入框架自动工具执行（宪章 II）”与命中文件和行号
- [X] T033 [US3] 在 `tools/dev/test-groups.json` 的 `us1-contract` 新增 T031、T032 两个方法（合计 20）；修改 `tools/dev/README.md` 第 105 行“`us1-contract`有15个必跑用例”为 20，并在同段补一句专项类清单
- [X] T034 [US3] 运行 `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract`，确认 `Test evidence PASS: 18 required cases`（登记总数 20，其中 boot 2 个不在该反应堆内）

### Verification for User Story 3（手工，验证后还原）

- [X] T035 [US3] 依次做三次故意破坏并各运行 T034 的命令，记录失败的测试类后 `git checkout -- <file>` 还原：① `P/ProviderService.java` 查表改为总取第一个 provider；② 在 `P/SpringAiProvider.java` 加入 `import org.springframework.ai.chat.model.ChatModel;`；③ `P/AuditedProvider.java` 失败分支跳过 `FAILED` 写入。按 SC-005 写入 `EV/breakage.md`：对应专项类（`ProviderRoutingTest` / `ProviderArchitectureGuardTest` / `ProviderAuditTest`）必须失败且失败信息指明约束；连带失败的类如实列出（已知错路由时 `ProviderSmokeTest` 也会失败）

### Story Review for User Story 3（宪章 VII）

- [X] T036 [US3] 故事级复查，写入 `EV/review-us3.md`：对照 FR-008、FR-009、契约不变量 P3–P6 与 spec US3 四个验收场景，审查 Foundational（T003–T011）与 T031–T033 的 diff（原方法名一个不少、断言未被削弱、标签与登记一致）；引用 T011、T034、T035 结果

**Checkpoint**: 三个坑各有专项回归测试，结构守护生效

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 质量基线清零（FR-012）、全量与对外契约验证（FR-010、SC-003、SC-006）、文档与课件修订建议（FR-013）

- [X] T037 [P] 清理 `P/ProviderService.java` 的存量违规：通配符导入改为显式导入；全限定名 `java.util.List`、`java.util.concurrent.atomic.AtomicBoolean` 等改为导入；所有单行 `if`/`for` 补花括号（覆盖 checkstyle `AvoidStarImportCheck`、`NeedBracesCheck`，pmd `ControlStatementBraces`）
- [X] T038 [P] 清理 `P/AuditedProvider.java` 同类违规（`AvoidStarImportCheck`、`NeedBracesCheck`、`ControlStatementBraces`）
- [X] T039 [P] 清理 `P/SpringAiProvider.java` 同类违规（`AvoidStarImportCheck`、`NeedBracesCheck`、`ControlStatementBraces`）
- [X] T040 在 `config/pmd/exclude-pmd.properties` 为 `io.github.oryxlabs.oryxos.provider.SpringAiProvider=PreserveStackTrace` 与（若 `clean verify` 后 `AuditedProvider` 仍报告）`io.github.oryxlabs.oryxos.provider.AuditedProvider=PreserveStackTrace` 各登记一条，条目上方写 `# reason: provider errors deliberately drop raw causes so response bodies, private URLs and credentials never reach logs or users (OryxError contract, constitution IV)` 与 `# registered: 2026-11-23`；不在源码写 `@SuppressWarnings`/`NOPMD`
- [X] T041 运行 `./tools/dev/mvnw spotless:apply` 后 `./tools/dev/mvnw clean verify`，全部门禁与已登记分组通过
- [X] T042 运行 `./tools/dev/quality-baseline --prune` 与 `./tools/dev/quality-baseline --check-shrink origin/main`；确认 `rg -c 'ProviderRegistry|ProviderService|SpringAiProvider|AuditedProvider|ToolWireNameMapper' config/quality/baseline.json` 为 0、条目数较 T001 记录少 12（若 `--prune` 顺带删除其它已清零条目，或 `AuditedProvider` 的 `PreserveStackTrace` 已因 T019 改造自然消失，如实记录实际数量）；结果写入 `EV/final-verification.md`
- [X] T043 运行 quickstart §6：`./tools/dev/mvnw -B -ntp test -Dgroups=us5-published-contract,us5-cli,kernel-local,restart-recovery,storage-faults`，确认 OpenAPI 19 操作 / 44 schema 零差异、CLI 与恢复测试通过；结果追加到 `EV/final-verification.md`
- [X] T044 [P] 更新 `specs/001-single-node-agent-runtime/verification.md` 第 38 行 `us1-contract` 分组表中的类清单：`ProviderContractTest` 替换为 `ProviderRoutingTest`、`ProviderToolSchemaTest`、`ProviderAuditTest`、`ProviderProtocolTest`、`ProviderAdmissionTest`、`ProviderArchitectureGuardTest`；`kernel-transport`/`kernel-provider-resources`/`kernel-provider-timing` 各行若写明类名同步为 `ProviderTransportTest`；仅改现行分组表，不改 `evidence/` 下历史记录
- [X] T045 [P] 新建 `specs/005-provider-boundary-refactor/lesson-16-revision.md`：一张表，列为“课件章节 / 课件原写法 / 现行写法 / 代码位置（重构后真实路径）/ 依据”，覆盖 spec“保留的偏差”6 条、入口改名 `ProviderService`、课件第四部分测试类名（`ProfileLoaderTest`、`ProviderServiceTest`、`ToolSchemaAdapterTest`、`LlmCallRepositoryTest`、`ProviderSmokeIT`）与现行测试类的对照；注明仓库外课件原文未修改
- [X] T046 对照 spec 的 FR-001 ~ FR-013、SC-001 ~ SC-007 逐条在 `EV/final-verification.md` 记录证据位置或未验证原因；勾选本文件已完成任务

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup（T001–T002）** → **Foundational（T003–T011）** → **US1（T012–T024）** → **US2（T025–T030）** → **US3（T031–T036）** → **Polish（T037–T046）**
- 串行原因：US1 的红灯测试必须在旧类名、旧实现上运行；US2 改名会改动 US1 新增的测试；US3 的故意破坏针对改名后的 `ProviderService`；Polish 清理的是 US1/US2 已改完的文件

### User Story Dependencies

- **US1 (P1)**：依赖 Foundational；不依赖其它故事
- **US2 (P1)**：依赖 Foundational；与 US1 逻辑独立，但为保证 US1 红灯证据有效，排在 US1 之后
- **US3 (P2)**：依赖 Foundational；T035 ① 依赖 US2 的改名

### Within Each Story

- US1：T012–T015 测试 → T016 红灯确认 → T017–T022 实现 → T023 转绿 → T024 复查
- US2：T025 → T026/T027 → T028 → T029 → T030 复查
- US3：T031/T032 → T033 → T034 → T035 → T036 复查

---

## Parallel Opportunities

- Foundational：T003 完成后，T004–T008 五个专项类可并行创建（不同文件）
- US1：T015（`ProviderRoutingTest`）可与 T012–T014（`ProviderAdmissionTest`）并行
- US2：T026 完成后 T027（只动测试文件）可与 T028 并行
- US3：T031 与 T032 并行
- Polish：T037、T038、T039 并行；T044、T045 并行

```text
# Foundational 并行示例（T003 之后）
T004 ProviderRoutingTest    T005 ProviderToolSchemaTest    T006 ProviderAuditTest
T007 ProviderProtocolTest   T008 ProviderTransportTest

# Polish 并行示例
T037 ProviderService 违规清理   T038 AuditedProvider 违规清理   T039 SpringAiProvider 违规清理
```

---

## Implementation Strategy

### MVP First（US1）

1. Setup + Foundational：拆分完成且数字与重构前一致
2. US1：先红后绿，修正唯一的可观察缺陷，并钉住非法请求零审计
3. **STOP and VALIDATE**：US1 独立测试与复查通过即可单独交付

### Incremental Delivery

1. US1 → 准入与审计正确性
2. US2 → 命名与死代码，课件可直接对照
3. US3 → 坑位专项测试与结构守护
4. Polish → 基线只减、全量契约零差异、课件修订建议

---

## Notes

- 本文件已按 `/speckit-analyze` 2026-11-23 的发现修订（宪章 VII 故事级复查、SC-005 判定口径、FR-004 零审计覆盖、T023 命令明确化）
- 每个阶段的 Checkpoint 都要求跑命令并留 evidence；只编译通过不算完成
- 只用回环假模型服务；真实 DeepSeek/Kimi 联调不是本次验收项，不得把假服务结果写成真实连通
- 不提交、不推送；保留工作区已有改动
