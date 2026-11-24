# Implementation Plan: CLI 与会话层边界对齐（对齐第 18 节课件与技术方案）

**Branch**: `008-lesson18-cli-boundary` | **Date**: 2026-11-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/008-cli-boundary-refactor/spec.md`

## Summary

第 18 节覆盖的 CLI 入口（12 子命令、轻重分流、`CliChannelAdapter`）与会话层（`SessionManager`、`SessionEntity`、`JpaSessionStore`、`V003__session_execution.sql`）已在 spec 001~007 实现。沿用 PR #7（005）/ PR #8（006）的边界对齐模式，本次是**行为保持型**边界对齐与验收 harness 补齐：

1. **补齐课件验收 harness 命名测试类**：
   - 在 `oryxos-core` 新增 `SessionManagerTest`（4 项，带 `@Tag("us5-cli")`），直接覆盖同会话多轮复用、三元组（channel/user/Agent）任一不同即隔离拒绝、ID 由 `SessionManager` 内部唯一生成、以及忙碌/归档边界。
   - 在 `oryxos-storage` 新增 `SessionRepositoryTest`（3 项，带 `@Tag("us5-cli")`），直接覆盖手工迁移脚本 `V001`~`V007` 建出的 `sessions`/`messages` 表存取、多轮结构化消息（含工具调用/结果）JSON 序列化回读完整、以及关闭并重建存储上下文（模拟重启）后历史完整保留。
2. **保留既有契约与测试零回归**：
   - 不改主代码（`SessionManager`、`JpaSessionStore`、`OryxCommandLine`、`OryxOsApplication`、`CliChannelAdapter`、`V003__session_execution.sql` 均保持既有 spec 001~007 生产契约），不删改现有 `SessionContractTest` / `SessionRuntimeContractTest` / `PersistentStorageContractTest` / `SessionReadContractTest` / `CliOperationsTest` / `CliRuntimeContractTest`。
3. **登记与文档同步**：
   - 同步 `tools/dev/test-groups.json`（`us5-cli` 从 15 项增至 22 项）、`tools/dev/README.md`、`specs/001-single-node-agent-runtime/verification.md` 分组表。
4. **课件修订建议**：
   - 交付 `specs/008-cli-boundary-refactor/lesson-18-revision.md`，逐条记录 6 项保留偏差、代码行级定位与验收 harness 映射表。

技术决定见 [research.md](./research.md)，测试归属与课件子项覆盖见 [data-model.md](./data-model.md)，验证步骤见 [quickstart.md](./quickstart.md)。

## Technical Context

**Language/Version**: JDK 21（父 POM `maven.compiler.release=21`）+ Spring Boot 3.5.16 + Spring AI Alibaba（锁定 BOM，本次不涉及模型依赖变更）

**Primary Dependencies**: Picocli、SQLite + Hibernate JPA（由 `StorageResourceFactory` 受控创建，不依赖 `hibernate.ddl-auto=update`，手工建表脚本 `V001`~`V007`）、JUnit 5。凭证走环境变量占位，不落明文。不新增、不升级第三方依赖（FR-006）。

**Storage**: SQLite `sessions` + `messages` 表结构与 `V001`~`V007` 迁移脚本不变。

**Module Placement（按第 18 节与 TechnicalSolution §10）**:
- `OryxOsCli` / `OryxCommandLine` + 12 子命令 → `oryxos-cli`（已就位）
- `CliChannelAdapter`（课件 `CliChannel`）→ `oryxos-channel-cli`（已就位）
- `SessionManager` / `Session` / `SessionStore` → `oryxos-core`（已就位；本次新增 `SessionManagerTest`）
- `SessionEntity` / `MessageEntity` / `JpaSessionStore` / `V003__session_execution.sql` → `oryxos-storage`（已就位；本次新增 `SessionRepositoryTest`）

**Testing Strategy（按课件“验收 harness”）**:
- 测试类清单：`SessionManagerTest`（覆盖同会话多轮复用、channel/user/Agent 三元组隔离、ID 统一内部生成、忙碌/归档边界）与 `SessionRepositoryTest`（覆盖手工建表脚本存取、结构化消息 JSON 序列化回读完整、重建存储上下文模拟重启历史保留）。
- 单测默认跑、集成冒烟打 `@Tag("integration")` CI 跳过（本次新增均为本地可执行单测/临时 SQLite 测试，纳入 `us5-cli` 分组由 `test_report_gate.py` 强制校验）；实现完成的定义是 `./tools/dev/mvnw clean verify` 全绿。

**Constraints & Static Checks**:
- 避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch 的 `default ->` 写法），静态检查（Spotless + Checkstyle/P3C + SpotBugs + FindSecBugs + PMD + quality-baseline）是构建门禁。
- 测试方法名全部为英文，课件原文保留在 `@DisplayName` 中。
- 质量基线只减不增（`./tools/dev/quality-baseline --check-shrink origin/main`）。

## Constitution Check

| 原则 | 检查点 | 结论 |
| --- | --- | --- |
| I 单机内核优先 | 不引入远程 CLI 代理、流式 SSE、多租户或 GraalVM Native Image | 通过 |
| II ReAct 与工具执行统一掌控 | CLI 仅做消息入口，经 `CliChannelAdapter` 调 `AgentService`，不碰模型/工具 | 通过 |
| III 职责分离 | `oryxos-core` 仅持有 `SessionStore` 契约，JPA/SQLite 实现在 `oryxos-storage`，无循环依赖 | 通过 |
| IV 凭证与数据边界 | 测试与配置无明文密钥；CLI 错误输出脱敏不变 | 通过 |
| V 审计与持久化 | 手工迁移脚本 `V001`~`V007` 不变；`SessionRepositoryTest` 实证 SQLite 重启恢复 | 通过 |
| VI 可演示可检查 | 每条 FR 对应可执行测试与命令；人工项（真模型多轮 `chat`、轻命令秒回、`--help`）单独列清 | 通过 |
| VII 一致性 | 6 项保留偏差有宪章/spec 001 依据并落入 `lesson-18-revision.md` | 通过 |

## Project Structure

### Documentation (this feature)

```text
specs/008-cli-boundary-refactor/
├── spec.md
├── plan.md                         # 本文件
├── research.md                     # Phase 0 现状与偏差核查
├── data-model.md                   # Phase 1 测试归属与验收映射
├── quickstart.md                   # Phase 1 验证命令清单
├── checklists/requirements.md
├── evidence/                       # 实施阶段验证记录
├── lesson-18-revision.md           # 实施阶段末尾（FR-008）
└── tasks.md                        # 任务拆解
```

### Source Code (repository root)

```text
oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/session/
└── SessionManagerTest.java         # 新：4 项（@Tag("us5-cli")）

oryxos-storage/src/test/java/io/github/oryxlabs/oryxos/storage/
└── SessionRepositoryTest.java      # 新：3 项（@Tag("us5-cli")）

tools/dev/test-groups.json          # 改：us5-cli 新增上述 2 类共 7 项（15 → 22）
tools/dev/README.md                 # 改：us5-cli 说明同步新增 2 类与用例数
specs/001-single-node-agent-runtime/verification.md  # 改：us5-cli 分组表同步
```

**Structure Decision**: 不新增/合并 Maven 模块，不改动任何主代码与表结构；以新增两个课件命名的 harness 测试类承载第 18 节验收点，零风险保持 spec 001~007 全量外部契约。

## 实施顺序与依赖

| 阶段 | 内容 | 依赖 | 退出条件 |
| --- | --- | --- | --- |
| S0 基线核查 | 确认 `quality-baseline` 当前总数、`us5-cli` 现状（15 项）与主代码零改动范围 | — | `evidence/baseline-snapshot.md` |
| S1 会话管理器 harness | 新增 `oryxos-core` `SessionManagerTest`（4 项），登记入 `test-groups.json` | S0 | `oryxos-core` 单测绿 |
| S2 会话持久化 harness | 新增 `oryxos-storage` `SessionRepositoryTest`（3 项），登记入 `test-groups.json` | S0 | `oryxos-storage` 单测绿 |
| S3 分组与文档同步 | 更新 `tools/dev/README.md`、`specs/001-single-node-agent-runtime/verification.md` 与 `lesson-18-revision.md` | S1、S2 | 6 项偏差与 2 类映射齐全 |
| S4 全量门禁验收 | 运行 `us5-cli`、全量 `clean verify`、`quality-baseline --check-shrink origin/main` 与 H4 六条不变量核查 | S3 | 全绿，证据写入 `evidence/final-verification.md` |
