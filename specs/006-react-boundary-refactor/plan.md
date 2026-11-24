# Implementation Plan: ReAct 边界对齐重构（对齐第 17 节课件与技术方案）

**Branch**: `006-lesson17-react-boundary` | **Date**: 2026-11-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/006-react-boundary-refactor/spec.md`

## Summary

ReAct 能力已在 spec 001 US2 实现，本次是行为保持型的边界对齐：

1. **补课件交付物 `ProfileContext`**：新增 core `agent.ProfileContext`（`set/current/clear`），由 `AgentService` 在循环开始前设置、
   `finally` 第一句清理，覆盖异步与内联两条执行路径（FR-001 ~ FR-003，research R1/R2）。
2. **测试按课件可定位**：`ReActContractTest` 6 个方法按对象迁入 `ReActLoopTest` / `ContextLoaderTest`，方法名不变；
   新增 `PromptBuilderTest`、`ToolExecutorTest`、`AgentServiceTest`；共新增 14 个回归测试，包括课件写出代码的两个关键测试（FR-004、FR-005）。
3. **登记同步**：`us2-loop` 30 → 44；`test-groups.json`、`tools/dev/README.md`、spec 001 `verification.md` 分组表（FR-006）。
4. **基线清零**：`AgentService` 4 条存量违规清除（显式导入、补花括号、截止调度器显式构造），`--prune` 后 `--check-shrink`（FR-009）。
5. **课件修订建议**：实现完成后写 `lesson-17-revision.md`（FR-010）。

技术决定见 [research.md](./research.md)，新增边界契约见 [contracts/profile-context.md](./contracts/profile-context.md)，
测试归属与课件子项覆盖见 [data-model.md](./data-model.md)，验证步骤见 [quickstart.md](./quickstart.md)。

## Technical Context

**Language/Version**: Java 21（父 POM `maven.compiler.release=21`）

**Primary Dependencies**: Spring Boot 3.5.16、Spring AI BOM 1.1.8（本次不涉及）；JUnit 5。不新增、不升级依赖（FR-008）

**Storage**: SQLite `tool_invocations` / `llm_calls` / 会话表结构与迁移脚本不变

**Testing**: JUnit 5 + Surefire，手写假对象（core 无 Mockito）；统一入口 `tools/dev/mvnw`；分组登记 `tools/dev/test-groups.json`，报告门禁随 `-Dgroups` 自动执行

**Target Platform**: 单机 JVM 服务

**Project Type**: Maven 多模块后端；本次只改 `oryxos-core`（主代码 2 个文件：新增 1、修改 1）

**Performance Goals**: 无新目标；`DeadlineRaceTest`、`kernel-*` 计时分组不变且通过

**Constraints**: 外部契约零差异；不新增迁移/依赖；基线只减不增；避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch 的 `default ->`）；
测试方法名英文，课件中文名放 `@DisplayName`

**Scale/Scope**: 主代码 2 个文件；测试新增 5 个类、删除 1 个；登记与文档 4 处；规格产物 1 份修订建议

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查点 | 结论 |
| --- | --- | --- |
| I 单机内核优先 | 不做并行工具、Agent 委托、流式、上下文压缩 | 通过 |
| II ReAct 与工具执行统一掌控 | 执行仍只在 `ToolExecutor`；新增“恰好 N 轮”“逐轮累积”回归钉死循环边界 | 通过（加强） |
| III 职责分离 | `ProfileContext` 只由统一入口写；循环/上下文/执行职责不变；无新模块依赖 | 通过 |
| IV 凭证与数据边界 | `ProfileContext` 只引用已加载的 `AgentProfile`（不含密钥明文，秘密在 provider 定义中按引用解析）；`finally` 必清防串号 | 通过 |
| V 审计与持久化 | `tool_invocations` 结构与写入不变；新增“审计写入失败必上抛”测试 | 通过 |
| VI 可演示可检查 | 每条 FR 对应测试或命令；两次故意破坏入 evidence；真模型 Demo 列为人工剩余项 | 通过 |
| VII 一致性 | spec 澄清 5 项；保留偏差 9 条有依据；`ProfileContext` 与技术方案 §4 一致 | 通过 |

**Post-design re-check**：Phase 1 未引入新偏差。`ProfileContext` 与 `ExecutionContext` 并存是用户在 Clarifications 中的明确选择，
契约 PC5 限定现有代码不切换读取通道，避免两条通道语义分叉。

## Project Structure

### Documentation (this feature)

```text
specs/006-react-boundary-refactor/
├── spec.md
├── plan.md                         # 本文件
├── research.md                     # Phase 0
├── data-model.md                   # Phase 1：ProfileContext 状态 + 测试归属
├── contracts/profile-context.md    # Phase 1：新增边界契约
├── quickstart.md                   # Phase 1：验证步骤
├── checklists/requirements.md
├── evidence/                       # 实施阶段：基线快照、红绿、故意破坏、全量验证
├── lesson-17-revision.md           # 实施阶段末尾（FR-010）
└── tasks.md                        # /speckit-tasks 生成
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/agent/
├── ProfileContext.java             # 新
└── AgentService.java               # 改：set/clear；显式导入；补花括号；ScheduledThreadPoolExecutor 显式构造

oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/
├── react/ReActLoopTest.java        # 新（迁入 3 + 新增 2）
├── react/ReActContractTest.java    # 删
├── context/PromptBuilderTest.java  # 新（新增 3）
├── context/ContextLoaderTest.java  # 新（迁入 3 + 新增 1）
├── tool/ToolExecutorTest.java      # 新（新增 4）
└── agent/AgentServiceTest.java     # 新（新增 4）
    （react/LoopFixture.java 不改，public 跨包复用）

config/quality/baseline.json        # 改：--prune 删除 AgentService 4 条
tools/dev/test-groups.json          # 改：us2-loop 去 ReActContractTest，加 5 个类
tools/dev/README.md                 # 改：us2-loop 登记数 30 → 44
specs/001-single-node-agent-runtime/verification.md  # 改：us2-loop 行类清单（现行分组表）
```

**Structure Decision**：不新增、不合并模块；依赖方向不变。`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`ContextLoader` 主代码不改。

## 实施顺序与依赖

| 阶段 | 内容 | 依赖 | 退出条件 |
| --- | --- | --- | --- |
| S0 基线快照 | 记录基线总数、`AgentService` 条目、`ReActContractTest` 方法清单、`us2-loop` 登记数 | — | `evidence/baseline-snapshot.md` |
| S1 纯迁移 | 新建 `ReActLoopTest`、`ContextLoaderTest` 迁入 6 方法；删 `ReActContractTest`；更新 `test-groups.json` | S0 | `us2-loop` 全绿；quickstart §3 无差异 |
| S2 红灯 | 新建 `AgentServiceTest`（引用尚不存在的 `ProfileContext`）并登记 | S1 | 编译失败/用例失败记录入 `evidence/red-green.md` |
| S3 `ProfileContext` | 新增类；`AgentService` 接线 | S2 | `AgentServiceTest` 4 项转绿 |
| S4 补 harness | `ReActLoopTest` +2、`PromptBuilderTest` 3、`ToolExecutorTest` 4、`ContextLoaderTest` +1；登记 | S1 | `us2-loop` 44 项全绿 |
| S5 基线清零 | `AgentService` 显式导入、花括号、调度器；`clean verify`；`--prune`；`--check-shrink` | S3 | quickstart §5 |
| S6 全量与破坏 | quickstart §6、§7 | S4、S5 | 全绿；两次破坏入 `evidence/breakage.md` |
| S7 文档 | README 计数、`verification.md`、`lesson-17-revision.md` | S6 | FR-010 覆盖 9 条偏差 + `ProfileContext` + 测试对照 |

S4 中的新增测试针对已实现行为，预期首次即绿；若出现红灯，按“实现错修实现、测试错停下报告”处理，不放宽断言。
每个用户故事完成后（S3、S4、S7）对照 `contracts/profile-context.md` 与本故事 FR 审查 diff，写入 `evidence/review-usN.md`。

## 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 清理放在 `handle.complete` 之后 | 异步路径泄漏不可观察；内联调用方存在竞态 | R2：`finally` 第一句清理；PC3 测试钉住 |
| 补花括号时误改控制流 | 行为回归 | 只加括号不调顺序；Spotless 格式化后 `git diff -w` 审阅；全量 + `DeadlineRaceTest` 覆盖 |
| `ScheduledThreadPoolExecutor` 与原包装行为差异 | 截止计时变化 | 不调整任何参数；`deadline-boundary`、`kernel-*` 分组回归 |
| 新测试与 `LoopFixture` 共享临时目录 | 用例间串扰 | 每个用例独立 `@TempDir` 子目录 |
| 登记数与实际不一致 | 报告门禁失败 | data-model §2 为唯一清单；S4 后跑 quickstart §1 |

## Complexity Tracking

无需要豁免的违规。
