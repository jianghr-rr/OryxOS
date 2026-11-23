# Research: Provider 边界重构

**Feature**: `005-provider-boundary-refactor` | **Date**: 2026-11-23 | **Spec**: [spec.md](./spec.md)

本文件收敛 plan 阶段的技术决定。全部事实于 2026-11-23 核对当前检出（分支 `005-provider-boundary-refactor`），
不采信历史文档中的“已交付”描述。

## R1. 运行准入只在 ReAct 循环申请一次

- **现状事实**：
  - `ReActLoop` 第 58 行：`ctx.dispatcher().call(() -> provider.call(request))`。
  - `PromptBuilder` 第 42 行把同一个 `ActionDispatcher` 塞进 `ProviderRequest.withDispatcher(...)`。
  - `AuditedProvider` 第 33–35 行与 `SpringAiProvider` 第 48–52 行再各自 `request.dispatcher().call(...)`。
  - `RuntimeGate.ExecutionPermit.admit()` 每次调用都会重新检查停止/暂停/截止，并把工作区 `inFlight` 与执行级 `active` 各加 1。
  - 工作区 `inFlight` 被 `StorageLifecycle`（第 46 行）和 `RuntimeStartupCheck`（第 229、246、287 行）用作“可安全暂停/关闭”的判断依据。
- **Decision**：只保留 `ReActLoop` 的那一次准入；`AuditedProvider`、`SpringAiProvider` 删除 dispatcher 分支；
  `ProviderRequest` 删除 `dispatcher` 组件与 `withDispatcher`，`PromptBuilder` 不再设置它（FR-001、FR-003）。
- **Rationale**：`ActionDispatcher` 的类注释已写明“准入即交接边界，已准入动作持续在途直到退出”。外层准入覆盖了
  `provider.call` 的全部时长，内层准入只会多计数，并在“审计已写开始、请求尚未发出”的窗口把停止误记为失败。
  Clarifications Q1 选择“继续跑完”，与这一语义一致。
- **Alternatives considered**：
  - 保留请求里的句柄、只删内层调用：调用方仍会误以为 provider 负责准入，且以后很容易被重新加回，否决。
  - 把准入下沉到 provider、删掉 ReAct 那一层：provider 之外的模型调用（若将来出现）会失去统一准入，且违背“循环负责流程控制”的职责划分，否决。
- **对其他模块的影响**：`ExecutionContext.dispatcher()` 继续被 memory、tool 模块使用，不受影响；只删除 `ProviderRequest` 上的副本。

## R2. 请求校验与错误映射各只做一次

- **现状事实**：`ProviderRegistry.call` 先调 `SpringAiProvider.validate(request)`，`SpringAiProvider.call` 第 32 行再校验一次；
  `SpringAiProvider` 的 catch 调 `ProviderErrorMapper.failure`，`AuditedProvider` 的 catch 再调一次（对 `OryxError` 幂等直通）。
- **Decision**：
  - 校验函数从 `SpringAiProvider` 移入 `ProviderService`（私有静态），只在入口、审计之前执行一次；`SpringAiProvider` 只保留
    “请求的 provider 名与自身定义一致”的防御检查（FR-004）。
  - 原始异常到 `OryxError` 的映射只在 `SpringAiProvider` 进行，因为只有它知道传输开始时间与剩余预算（超时判定依赖它）。
    `AuditedProvider` 收到 `OryxError` 时原样记录并原样抛出；收到非 `OryxError`（防御分支，正常不会发生）时归为
    `PROVIDER_PROTOCOL`，不复制原始消息（FR-005）。
- **Rationale**：入口校验必须早于审计写入，才能保证“非法请求不写审计”；映射放在掌握计时信息的一层，避免两层各判一次超时。
- **Alternatives considered**：把映射集中到 `AuditedProvider`：它拿不到传输起点，超时与“流被关闭导致的 JSON EOF”无法区分（`SpringAiProvider` 第 58 行注释说明的场景），否决。

## R3. 入口改名 `ProviderRegistry` → `ProviderService`

- **现状事实**：主代码引用只有 `oryxos-boot/.../config/ProviderConfiguration.java`（第 9、46 行）；
  测试引用在 `ProviderContractTest`、`ProviderSmokeTest`（含第 75 行输出文案）、`RealProviderIT`、`ProviderConcurrencyProbeIT`；
  质量基线 3 条；现行文档（`CLAUDE.md`、`docs/`、`website/`、`tools/dev/README.md`）中没有该类名。
- **Decision**：只改这一个类名（Clarifications Q2），用 `git mv` 保留历史；Spring Bean 方法名与类型随之更新；
  `specs/001` ~ `004` 的历史证据不改写。
- **Alternatives considered**：保留旧名并新增 `ProviderService` 接口包装：多一层无行为的类型，否决。

## R4. 质量基线：清零而不是改路径

- **现状事实**：
  - `config/quality/baseline.json` 共 460 条，按 `(tool, rule, file)` 登记。
  - `tools/dev/quality-gate.py` 的 `--check-shrink` 用“当前集合 − 基线分支集合”判定增长。也就是说，只把路径改成新文件名，
    会被当作新增条目，CI 的 “Baseline must not grow” 步骤会失败。因此 Clarifications Q3 的选项 A 也是唯一能通过 CI 的做法。
  - 本次触及的 provider 主代码文件，在基线中共 12 条：

    | 文件 | 条目 |
    | --- | --- |
    | `ProviderRegistry.java`（改名） | checkstyle `AvoidStarImportCheck`、`NeedBracesCheck`；pmd `ControlStatementBraces` |
    | `SpringAiProvider.java` | checkstyle `AvoidStarImportCheck`、`NeedBracesCheck`；pmd `ControlStatementBraces`、`PreserveStackTrace` |
    | `AuditedProvider.java` | checkstyle `AvoidStarImportCheck`、`NeedBracesCheck`；pmd `ControlStatementBraces`、`PreserveStackTrace` |
    | `ToolWireNameMapper.java`（删除） | checkstyle `AvoidStarImportCheck` |

- **Decision**：
  - 通配符导入改为显式导入；所有单行 `if`/`for` 补花括号。
  - `PreserveStackTrace`：provider 层故意不把原始异常挂为 cause，因为原始异常消息可能包含响应体、私有 URL 或凭证
    （`OryxError` 类注释：“never attach raw external/config exceptions”；宪章 IV）。用项目已有的登记例外机制处理：
    在 `config/pmd/exclude-pmd.properties` 为 `SpringAiProvider`、`AuditedProvider`（若改造后仍触发）各登记一条，
    带 `# reason:` 和 `# registered: 2026-11-23`；`quality-gate.py` 第 108–110 行会据此过滤。不使用源码内 `@SuppressWarnings`
    或 `NOPMD` 注释（quality-gates 契约 FR-018 要求例外只写在登记文件里）。
  - 完成后运行 `./tools/dev/quality-baseline --prune` 删除已清零条目，再用 `--check-shrink origin/main` 确认只减不增。
- **Rationale**：这里的 PreserveStackTrace 是安全约束，不是遗漏；登记例外会留下可审查的理由，比把条目留在“待清理”基线里更准确。
- **Alternatives considered**：给 `OryxError` 增加带 cause 的构造器：会让原始异常进入日志堆栈，违反宪章 IV，否决。

## R5. 测试拆分与共用夹具

- **现状事实**：
  - `ProviderContractTest` 共 1010 行、14 个测试方法；类级 `@Tag("us1-contract")`，其中 6 个方法另带 `kernel-*` 方法级标签。
  - `tools/dev/test-groups.json` 中该类登记在 4 个分组：`us1-contract`（8 个方法）、`kernel-transport`（1）、
    `kernel-provider-resources`（2）、`kernel-provider-timing`（3）。
  - `tools/dev/check-test-reports.py` 只校验已登记的类+方法存在且通过，并按 `testcase@classname` 精确匹配，
    不拒绝未登记的额外用例。
  - 共用夹具是 `Receiver`（回环 HTTP 假服务）、`ordinary`、`definition`、`registry`、`request`、`assertCode`，以及
    `@TempDir root`、`audit` 列表、`@AfterEach` 关闭。
- **Decision**：
  - 新建包内抽象基类 `ProviderTestSupport`，承载上述夹具与生命周期；不带类级标签，避免改变分组。
  - 各专项类 `extends ProviderTestSupport`，保留类级 `@Tag("us1-contract")`（与原类一致），方法原样迁移，方法级 `kernel-*` 标签不动。
  - 拆分方案见 [data-model.md §3](./data-model.md#3-测试拆分映射)。
  - `test-groups.json` 只改 `class` 字段与方法归属，方法名不变（Clarifications Q4）。
- **Rationale**：JUnit 5 会继承父类的 `@TempDir` 字段与 `@AfterEach` 方法；测试方法声明在子类里，因此报告中的 `classname` 是子类名，
  与登记文件一致。
- **Alternatives considered**：用 JUnit 扩展（`@RegisterExtension`）做夹具：Receiver 需要按测试定制状态，基类写法更直接，否决。

## R6. 新增的 5 个测试

| 测试 | 证明什么 | 做法要点 |
| --- | --- | --- |
| `ProviderAdmissionTest#modelCallHoldsExactlyOneAdmissionWhileInFlight` | SC-001：在途计数恰为 1 | 必须走真实路径：用 core 主代码组装 `RuntimeGate`、`ExecutionContext`、真实 `ReActLoop` + `PromptBuilder`（组装方式参照 core 测试的 `LoopFixture`，但在 provider 测试内自建，因为 core 未发布 test-jar），provider 端口为真实 `ProviderService` 指向回环假服务；在假服务处理函数里读 `gate.inFlight()` |
| `ProviderAdmissionTest#stopAfterAdmissionLetsTheCallFinishAndRecordsActualOutcome` | FR-002 / SC-002 | 同一真实路径；审计 sink 收到该次调用的 `STARTED` 记录时调用 `permit.stop()`；断言假服务收到 1 次请求、模型审计终态 `SUCCEEDED`、无 `EXECUTION_STOPPED` 的模型审计（执行本身随后由 ReAct 的下一次检查结束，属预期） |

- **为什么必须走真实路径**：旧实现中准入句柄是由 `PromptBuilder` 塞进请求的；如果测试直接手工构造 `ProviderRequest`，
  旧实现也不会重复准入，测试在重构前就是绿的，无法证明修复。走 `ReActLoop` + `PromptBuilder`，同一份测试代码在旧实现上
  得到 `inFlight=3` 与 `FAILED/EXECUTION_STOPPED`（红），重构后得到 1 与 `SUCCEEDED`（绿）。
- **红绿顺序**：该测试先以旧类名 `ProviderRegistry` 编写并运行出红，再做改名与准入重构（改名时同步改测试中的类名）。
| `ProviderRoutingTest#invalidRequestsNeverWriteAuditOrReachNetwork` | FR-004：非法请求零审计、零网络 | 空任务、剩余时间为零、未知 provider 三种请求；断言错误码 `INVALID_TASK` / `PROVIDER_TIMEOUT` / `PROVIDER_UNAVAILABLE`，`audit` 与 `Receiver.requests` 均为空。旧实现同样为绿，用于钉住 validate 迁移后的行为 |
| `ProviderAuditTest#failedCallsAreAuditedWithClassifiedCodeBeforeRethrow` | 课件“最容易漏”的失败审计 | 假服务返回 503 与超时两种情况；断言抛出分类错误码，且同一 callId 先有 `STARTED`、后有 `FAILED` + 同一错误码，记录中无密钥与私有 URL |
| `ProviderArchitectureGuardTest#providerMainCodeUsesOnlyLowLevelOpenAiApi` | FR-009：结构守护 | 扫描 `oryxos-provider/src/main/java` 全部源码文本，凡出现 `org.springframework.ai.` 前缀，必须以 `org.springframework.ai.openai.api.` 开头；另显式禁止 `ChatModel`、`ChatClient`、`ToolCallback`、`ToolCallingManager`、`internalToolExecution` 标识符。失败信息写明“Provider 不得接入框架自动工具执行（宪章 II）”及命中文件 |

- **守护测试的事实依据**：当前 provider 主代码只引用 `org.springframework.ai.openai.api.OpenAiApi` 及其内部类型（3 处），允许清单不会误伤现状。
- **Alternatives considered**：ArchUnit 字节码规则：要新增依赖，违反 FR-011，否决；只禁 import 语句：本仓库存在大量全限定名写法，会漏检，否决。
- **登记**：5 个新方法全部登记到 `us1-contract`，`tools/dev/README.md` 的必跑用例数由 15 改为 20。

## R7. 课件修订建议文档（FR-013）

- **Decision**：交付 `specs/005-provider-boundary-refactor/lesson-16-revision.md`，在实现完成后编写，确保其中引用的类名、行号、测试名都是重构后的真实位置。
- 内容为一张表：课件章节 / 课件原写法 / 现行写法 / 代码位置 / 依据（spec 001 条款或本规格 FR）；覆盖 spec“保留的偏差”6 条
  + 入口改名 + 课件第四部分的测试类名与现行测试类名对照。仓库外课件原文不改。

## R8. 不做的事（范围护栏）

- 不改 `OpenAiClientFactory`、`TimedHttpRequestFactory`、`ProviderMessageMapper`、`ProviderOptionsMapper`、`ProviderErrorMapper` 的行为；
  因此它们的基线条目本次保留（FR-012 只约束本次改动的文件）。
- 不改 `ChatCompletionRequest` 的 33 参数位置构造（`ProviderOptionsMapper`）；它脆弱但有 Kimi temperature 的回归测试保护，改造另议。
- 不改 `llm_calls` 结构、迁移脚本、HTTP/CLI 契约、`runtime.yaml` 格式；不引入依赖。
