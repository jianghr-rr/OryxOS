# Feature Specification: ReAct 边界对齐重构（对齐第 17 节课件与技术方案）

**Feature Branch**: `006-lesson17-react-boundary`（规格目录名 `006-react-boundary-refactor`；分支从已提交的 005 成果 `37cd098` 切出）

**Created**: 2026-11-23

**Status**: Draft

**Input**: User description: "/oryxos-lesson-dev docs/第17节：ReAct 原理解析、实现与代码讲解.md —— 第 17 节的 ReAct 能力已在 spec 001 US2 实现；沿用 spec 005 的做法，对照课件做边界重构：按课件新增 `ProfileContext`，把现有测试迁到课件命名的测试类（原方法名保留），补齐课件验收 harness 中缺失的回归测试，并交付课件修订建议。"

## 背景与现状（规格输入）

本规格覆盖五大核心能力中的 **ReAct**（含其编排者与上下文供给者），是一次**不改变外部可观察行为**的边界对齐。
输入是仓库内 `docs/第17节：ReAct 原理解析、实现与代码讲解.md`（下称“课件”）与 2026-11-23 当前检出的
`oryxos-core` 代码。课件是教学示意，凡与已确认的 spec 001 / plan 001 / spec 005 冲突处，以后者为准（见“保留的偏差”）。

### 课件原则与当前代码对照

| 课件要求 | 当前实现 | 结论 |
| --- | --- | --- |
| 循环只做调度：转圈、判停、累积 | `ReActLoop` 只编排：上下文交给 `PromptBuilder`，模型交给 Provider 端口，工具交给 `ToolExecutor` | 已满足 |
| 坑一：最大轮数兜底，默认 10 | `max_iterations` 默认 10、上限 100；转满即以 `ITERATION_LIMIT` 结束 | 已满足；**缺“恰好转满 N 轮”的回归测试**（现有仅测 1 轮上限） |
| 坑二：历史只留最近 N 轮，默认 20 | `max_history_turns` 默认 20，历史按完整轮次截取且不删除持久化记录 | 已满足；截断测试在会话测试类中，**`PromptBuilder` 侧无“进入请求的历史受上限约束”的测试** |
| 坑三：每轮响应与工具结果累积回 Session | 每条 assistant / tool 消息即时追加到会话存储，tool-call ID 配对 | 已满足；**缺“每一轮都累积”的逐轮断言** |
| `PromptBuilder` 四部分顺序：system（含末尾日期时间）→ 长期记忆 → 会话历史 → 工具 | 正文 → Bootstrap → 当前时间 → CORE/ARCHIVAL 记忆 → 近期历史 → 当前轮 → 工具 schema | 已满足；**无专项测试钉住顺序** |
| `ToolExecutor` 唯一执行点，成功失败都审计，异常不吞 | 先记 STARTED，再记终态；审计写入失败上抛 `RECORDING_FAILURE` | 已满足；**无以 `ToolExecutor` 为对象的专项测试** |
| `AgentService` 统一入口，结束后持久化 Session | CLI / HTTP / 定时均进 `AgentService`；会话与执行即时落库 | 已满足 |
| `ProfileContext`（ThreadLocal）：处理期间可取当前 Profile，finally 必清 | **不存在**；当前 Agent 通过 `ExecutionContext` 显式传给工具 | **课件交付物缺失，需新增**（Clarifications 已确认） |
| `ContextLoader`：每次重读、不缓存；显式引用缺失报错；Bootstrap 缺失至少 WARN | 每次重读；frontmatter 引用缺失在加载阶段报配置错误，运行时缺失以 `CONTEXT_UNAVAILABLE` 在模型调用前失败 | 已满足（比课件更严格）；测试位于 `ReActContractTest`，**不按课件可定位** |
| 测试类：`ReActLoopTest` / `PromptBuilderTest` / `ToolExecutorTest` / `AgentServiceTest` / `ContextLoaderTest` | 五个类都不存在；覆盖散在 `ReActContractTest`、`FailurePolicyContractTest`、`SessionRuntimeContractTest` 与 tool 模块测试 | **可定位性不足，需重组** |

### 保留的偏差（不按课件改动）

| 课件写法 | 保留的已确认依据 |
| --- | --- |
| `ReActLoop.run(Session, String, Profile)` 返回字符串 | 循环接收执行上下文与会话租约，结果写入执行记录（状态、停止原因、回复），CLI/HTTP 从执行记录取回复；spec 001 US2 |
| 每轮都执行模型提出的工具，转满返回“达到最大轮数，已停止” | 最后一轮提出的工具**不执行**，以 `NOT_EXECUTED`/`ITERATION_LIMIT` 配对回填，因为其结果再无机会交给模型；结束文案为“达到迭代上限；已确认完成 N 个工具请求……”；spec 001 US2-A5 |
| `providerService.chat(sessionId, profile, prompt)` | `ProviderPort.call(ProviderRequest)`，审计按 execution 关联（spec 005 保留偏差第 4 条） |
| `toolExecutor.execute(sessionId, call)` | `execute(ExecutionContext, ToolCall)`，审计按 execution 关联，session 经 execution 关联 |
| `tool_invocations.success` / `error_message` 列，`ToolInvocationRepository` | `status` + `error_code`，不落原始错误文本；实体 `ToolInvocationEntity` 经审计落库组件写入，无独立 Repository 接口（与 spec 005 对 `llm_calls` 同一口径） |
| `AgentService.process(Session, String)`，结束后 `sessionManager.save(session)` | `process(ProcessRequest)`，会话在入口按 channel+user+Agent 身份获取并校验；消息逐条即时落库，不在结束时整体保存（spec 001 会话身份决定） |
| `Profile` / `ProfileRegistry` | `AgentProfile` / `AgentCatalog`，唯一定义源是 `AGENT.md` frontmatter（spec 001 FR-002） |
| `ContextLoader` 按 Profile 的 `skills` 字段读 SKILL.md | 不存在共享技能索引；一个目录就是一个 Agent，子指令经 `read_file` 按需读取（技术方案 §11） |
| Bootstrap 缺失至少 WARN | 显式引用的 Bootstrap 缺失、超限或越界即失败，且在任何模型调用之前（更严格，防“人格悄悄丢失”） |

## Clarifications

### Session 2026-11-23

- Q: 课件的 `ProfileContext` 与现有 `ExecutionContext` 显式传参如何取舍？ → A: 按课件新增 `ProfileContext`，由 `AgentService` 在处理入口设置、出口清理；显式传参保持不变。
- Q: 本节是从零开发还是对齐重构？ → A: 沿用 spec 005 做法，作为对照课件的边界重构，新开 006 规格。
- Q: 分支如何处理 005？ → A: 现在新建 006 分支。（实际核查：005 已在 `37cd098` 提交并推送，分支切出时工作区干净，无改动带入。）
- Q: 现有测试如何对齐课件五个测试类？ → A: 迁移到课件命名的测试类，原方法名保留，再补缺失的回归测试，同步测试分组登记（同 spec 005 口径）。
- Q: 本次改到的 `AgentService` 在质量基线中的 4 条存量违规怎么处理？ → A: 全部清除并删除基线条目，含截止时间调度器的创建方式（改为显式构造、线程数/守护/命名不变）；基线总数只减不增。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 工具执行期间能取到“当前是哪个 Agent”，且不会串到下一次处理 (Priority: P1)

工具或其辅助代码在一次处理过程中，不经参数传递也能读到当前 Agent 的配置（例如通知渠道）；
处理结束后，无论正常、失败还是意外异常，同一线程上都读不到这次的 Agent，下一次处理不会拿到别人的配置。

**Why this priority**: 课件把它列为本节交付物，并指出 ThreadLocal 泄漏只在并发复用时串号、单请求测试永远不报错，
必须在 harness 中显式钉死；这是本节唯一的新增对外概念。

**Independent Test**: 用可控假模型让模型调用一个测试工具，工具内读取当前 Agent；再让循环抛出异常，处理结束后在同一线程读取当前 Agent。

**Acceptance Scenarios**:

1. **Given** 一次处理正在执行工具，**When** 工具读取当前 Agent，**Then** 读到的与本次执行上下文中的 Agent 相同。
2. **Given** 处理过程中循环抛出意外异常，**When** 处理结束，**Then** 同一线程读取当前 Agent 为空，执行以失败结束、会话租约被释放。
3. **Given** 同步（调用方线程内联）与异步（工作线程）两种执行方式，**When** 处理结束，**Then** 两种方式下当前 Agent 均被清空。
4. **Given** 不在任何处理之中，**When** 读取当前 Agent，**Then** 返回空，不抛异常。

---

### User Story 2 - 课件验收 harness 的每个点都有可按名定位的测试 (Priority: P1)

学员或维护者按课件“验收 harness”表找到 `ReActLoopTest`、`PromptBuilderTest`、`ToolExecutorTest`、`AgentServiceTest`、
`ContextLoaderTest` 五个测试类，表中每个验收点都能在对应类中找到测试；课件写出代码的两个关键回归测试原样落地。

**Why this priority**: 本项目按课件驱动开发，测试不能按课件定位会让“验收 harness”无法对号；
其中“恰好转满 N 轮”“异常时也清理当前 Agent”两条是课件点名的最值钱回归，目前缺失或无对应。

**Independent Test**: 逐行对照课件 harness 表，在五个测试类中查找对应测试；分别故意破坏“轮数上限”“finally 清理”两条约束，对应测试必然失败。

**Acceptance Scenarios**:

1. **Given** 模型每一轮都要求调工具、最大轮数为 10，**When** 运行一次处理，**Then** 模型恰好被调用 10 次，结束原因为迭代上限。
2. **Given** 模型连续多轮调工具后给出答复，**When** 运行结束，**Then** 每一轮的模型响应与工具结果都按顺序累积在会话中，且 tool-call ID 一一配对。
3. **Given** 会话已有超过上限轮数的历史，**When** 组装请求，**Then** 进入请求的历史只含最近 N 个完整轮次，持久化历史不被删除。
4. **Given** 正文、Bootstrap、记忆、历史与工具都存在，**When** 组装请求，**Then** 系统提示中正文先于 Bootstrap、Bootstrap 先于当前日期时间、日期时间先于记忆，历史在当前轮之前，工具 schema 单独提供。
5. **Given** 工具执行成功或失败（含工具自身抛异常），**When** 执行结束，**Then** 均先写“开始”再写终态审计，失败审计带分类错误码；审计写入失败时错误上抛而不是被吞掉。
6. **Given** 拆分前后的测试集，**When** 对比方法名清单，**Then** 被迁移的每个原方法名在新类中原样存在、数量不减；按既有分组命令运行时，报告核对通过且用例数等于登记数。

---

### User Story 3 - 课件与现行实现的差异有据可查 (Priority: P2)

课件作者或学员读到课件中与现行实现不一致的写法时，能在一份修订建议中查到现行写法、代码位置和依据。

**Why this priority**: 课件是教学示意，差异多但都已有依据；不记录会让每个读者重复踩一次“代码对不上课件”。

**Independent Test**: 对照“保留的偏差”表与修订建议文档，每条都有课件原写法、现行写法、代码位置与依据。

**Acceptance Scenarios**:

1. **Given** 修订建议文档，**When** 逐条核对“保留的偏差”表，**Then** 每条都在文档中出现，且代码位置真实存在。
2. **Given** 课件第四部分测试类名，**When** 查文档的测试对照表，**Then** 每个课件测试类都映射到现行测试类。

### Edge Cases

- 当前 Agent 在处理开始前已被同线程残留（理论上不应发生）：处理入口覆盖设置，出口清空。
- 会话获取失败、执行在准入前被拒：未进入处理，当前 Agent 从未被设置，读取为空。
- 截止时间先于循环结束到达、调用方提前返回：工作线程仍在运行时当前 Agent 仍可读；工作线程真正退出后清空。
- 最大轮数为 1：唯一一轮提出的工具不执行，模型被调用 1 次（现状保留）。
- 历史中存在不完整的 tool 配对：仍按现状失败关闭，不把半截轮次送入模型。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**：新增线程级“当前 Agent 上下文”，提供设置、读取、清理三个操作；不在处理中读取返回空。
- **FR-002**：统一入口在一次处理的 ReAct 循环开始前设置当前 Agent，在该处理所有退出路径（正常结束、业务失败、记录失败、意外异常）上清理；
  同步内联与异步工作线程两种执行方式行为一致。设置与清理只发生在统一入口一处。
- **FR-003**：新增的当前 Agent 上下文与执行上下文中的 Agent 始终是同一个；现有工具与记忆后端继续通过执行上下文取 Agent，本次不改其取值方式。
- **FR-004**：新增五个课件命名的测试类，并把现有测试按被测对象迁入：循环行为类迁入 `ReActLoopTest`，上下文热读取与 Bootstrap 校验类迁入 `ContextLoaderTest`；
  原方法名一律保留，迁空的原测试类删除；与课件 harness 无对应的失败策略测试类、会话测试类保持原位。
- **FR-005**：补齐课件 harness 缺失的回归测试，至少覆盖：恰好转满 N 轮（N=10）、逐轮累积、请求各部分顺序与日期时间位置、
  进入请求的历史受轮数上限约束、工具成功/失败/抛异常均审计、审计写入失败上抛、处理期间可取当前 Agent、异常时清理当前 Agent、
  处理结束会话消息已落库、改文件后下一次组装立即生效、显式引用缺失在模型调用前失败。
  课件写出代码的两个测试原样落地：方法名用英文，课件原中文名保留为显示名。
- **FR-006**：新增与迁移的测试登记进既有的 `us2-loop` 测试分组，分组登记文件、开发入口说明中的用例计数与 spec 001 verification 的分组表同步更新。
- **FR-007**：不得改变任何外部可观察契约：HTTP 接口与 OpenAPI、CLI 输出与退出码、Agent 定义与 `runtime.yaml` 格式、
  `tool_invocations` / `llm_calls` 表结构与写入内容、会话消息内容、错误码集合、迭代上限结束文案。
- **FR-008**：不新增数据库迁移脚本、不新增第三方依赖，不实现课件明确延后的并行工具调用、Agent 互相委托、流式输出、上下文压缩。
- **FR-009**：本次修改的主代码文件，必须清除其在质量基线中登记的全部存量违规并删除对应条目；其中截止时间调度器改为显式构造，
  线程数（1）、守护属性与线程名保持不变，执行模型不变。基线不得新增条目、不得出现指向不存在文件的条目，总条目数只减不增。
- **FR-010**：在本规格目录交付第 17 节课件修订建议，覆盖“保留的偏差”全部条目与本次新增的当前 Agent 上下文，
  每条给出课件原写法、现行写法、代码位置与依据；并给出课件五个测试类到现行测试类的对照。仓库内课件副本不修改。

### Key Entities

- **当前 Agent 上下文**：线程级、只在一次处理期间有效的“当前 Agent 配置”引用；由统一入口写入与清理，其他代码只读。
- **工具调用审计记录**：每次工具尝试一条，从“开始”到终态，带分类错误码与副作用状态；结构不变。
- **会话消息**：用户、assistant、tool 三类消息逐条落库并保持 tool-call ID 配对；结构不变。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**：课件 harness 表 5 行验收点及其全部子项在五个课件命名测试类中都能找到对应测试，对照结果写入证据文件。
- **SC-002**：模型持续要工具、上限 10 轮的场景下，模型调用次数恰为 10，由自动化测试断言。
- **SC-003**：处理以异常结束后，同线程读取当前 Agent 为空的断言在同步与异步两种执行方式下各通过一次。
- **SC-004**：全量本地构建与测试（含格式、规约、安全门禁）通过，失败数为 0；迁移前后被迁移的原测试方法数不减少。
- **SC-005**：两类故意破坏（取消轮数上限、去掉出口清理）各自让对应课件测试类失败，失败信息指明被破坏的约束，结果记入证据文件。
- **SC-006**：HTTP 契约零差异测试与 CLI 集成测试结果与重构前一致。

## Assumptions

- 课件中的类名、方法签名、表列名是教学示意；已确认的 spec 001 / plan 001 / spec 005 决定优先，差异列于“保留的偏差”。
- 当前 Agent 上下文只是课件要求的补充读取通道；不把现有工具迁到该通道，迁移如有需要另起规格。
- spec 005 已在 `37cd098` 提交；本规格以其完成态（`ProviderService`、单次准入）为基线，不重复验证 005 的验收项。
- 真实模型跑通 Demo 一（天气对话版）不是本次验收前提，作为课件“做完怎么验”的人工剩余项单独报告。
- 依赖 spec 004 已接入的格式、规约与安全门禁作为构建通过的判定标准。
