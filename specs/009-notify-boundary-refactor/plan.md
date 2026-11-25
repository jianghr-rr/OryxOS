# Implementation Plan: Notify 通知模块边界对齐与验收 harness（对齐第 19 节课件与技术方案）

**Branch**: `009-lesson19-notify-boundary` | **Date**: 2026-11-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/009-notify-boundary-refactor/spec.md`

## Summary

第 19 节覆盖的出站通知能力（`NotifyTool`、`WebhookNotifyAdapter`、`NotificationTargetResolver`、`notify_channels` 配置解析）已在 spec 001~008 落地了通用 webhook 实现，但缺少课件最核心的“先定接口、后挂实现”渠道中立抽象（`NotifyChannelAdapter` 接口与 `NotifyTarget` 模型），且缺少课件验收 harness 命名的 `WebhookNotifyAdapterTest` 与 `NotifyToolsTest`。沿用 PR #7（005）/ PR #8（006）/ 008 的边界对齐模式，本次以**行为保持、源码兼容**的方式完成四件事：

1. **补齐渠道中立抽象（接口先行）**：
   - 在 `oryxos-tool` 的 `io.github.oryxlabs.oryxos.tool.notify` 新增 `NotifyTarget`（渠道中立配置载体，含 `channelType` 与脱敏 `config`）与 `NotifyChannelAdapter`（声明 `authorize(NotifyTarget)` 与 `send(NotifyTarget, String, ExecutionContext)`）。
   - 让 `WebhookNotifyAdapter` 实现 `NotifyChannelAdapter`，保留既有 `NotificationTargetResolver.Target` 重载方法委托到新接口以保证既有调用方零改动兼容。
   - 让 `NotifyTool` 依赖接口 `NotifyChannelAdapter` 而非具体类 `WebhookNotifyAdapter`；`NotificationTargetResolver.Target` 提供 `toNotifyTarget()` 转换。
2. **补齐课件验收 harness 命名测试类**：
   - 在 `oryxos-tool` 新增 `WebhookNotifyAdapterTest`（4 项，带 `@Tag("us4-tools")`），基于 JDK `HttpServer` 本地假 webhook，验证动态目标 URL（非硬编码）、正文编码与特殊字符安全转义、5xx/业务拒绝明确失败不静默且不重试、断连返回 `UNKNOWN` 且不重试。
   - 在 `oryxos-tool` 新增 `NotifyToolsTest`（4 项，带 `@Tag("us4-tools")`），使用录制事件序列的自定义 `NotifyChannelAdapter` 替身验证：未配置 `notify_channels` 明确报错零发送、缺省目标名取唯一/默认渠道、`authorize`（白名单校验）严格先于 `send` 被调用（`InOrder` 顺序断言，拒绝时绝不触发 `send`）、经由 `ToolExecutor` 执行时成败与拒绝均落审计且可替换为非 webhook 实现。
3. **分组与文档同步**：
   - 同步 `tools/dev/test-groups.json`（`us4-tools` 新增上述 2 类共 8 项，由 21 项增至 29 项）、`tools/dev/README.md`、`specs/001-single-node-agent-runtime/verification.md`。
4. **课件修订建议**：
   - 交付 `specs/009-notify-boundary-refactor/lesson-19-revision.md`，逐条记录保留偏差、代码行级定位与验收 harness 映射表。

技术决定见 [research.md](./research.md)，接口契约与测试映射见 [data-model.md](./data-model.md)，验证步骤见 [quickstart.md](./quickstart.md)。

## Technical Context

**Language/Version**: JDK 21（父 POM `maven.compiler.release=21`）+ Spring Boot 3.5.16 + Spring AI Alibaba（锁定 BOM，已通过 `./tools/dev/mvnw -B -o -pl oryxos-tool dependency:tree` 核实本地依赖树）

**Primary Dependencies**: `oryxos-tool` 锁定使用 `httpclient5:5.6.5`、`oryxos-core`、`jackson-databind:2.21.4`、`junit-jupiter:5.12.2`。凭证走环境变量占位或受控本地配置（`SecretResolver`），不落明文。SQLite 使用手工建表脚本（本节不涉及表结构变更）。不新增、不升级任何第三方依赖（FR-010）。

**Storage**: 无新增表或迁移脚本；工具执行审计沿用既有 `tool_invocations`（`V004__call_audits.sql`）。

**Module Placement（按第 19 节与 TechnicalSolution §10）**:
- `NotifyChannelAdapter` 接口、`NotifyTarget`、`WebhookNotifyAdapter`、`NotificationTargetResolver`、`NotifyTool` → `oryxos-tool`（包 `io.github.oryxlabs.oryxos.tool.notify`）
- `WebhookNotifyAdapterTest`、`NotifyToolsTest` → `oryxos-tool`（包 `io.github.oryxlabs.oryxos.tool.notify`）
- `AgentProfile.Notification`、`AgentDefinitionReader`（`notify_channels` 解析）→ `oryxos-core`（已就位，本次不改动）

**Testing Strategy（按课件“验收 harness”）**:
- 测试类清单：`WebhookNotifyAdapterTest`（动态 URL、正文编码、5xx 失败不静默、断连不重试）与 `NotifyToolsTest`（未配置报错、缺省取默认渠道、`authorize` 严格先于 `send`、统一执行器审计与渠道可替换性）。
- 单测默认跑、集成冒烟打 `@Tag("integration")`（既有 `RealNotificationIT` 属于 `real-notify` 组，CI 默认跳过）；本次新增 2 类共 8 项均为本地离线单测，纳入 `us4-tools` 分组由 `test_report_gate.py` 强制校验；实现完成的定义是 `./tools/dev/mvnw -B -ntp clean verify` 全绿。

**Constraints & Static Checks**:
- 避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch 的 `default ->` 写法），静态检查（Spotless + Checkstyle/P3C + SpotBugs + FindSecBugs + PMD + quality-baseline）是构建门禁。
- 测试方法名全部为英文，课件原文保留在 `@DisplayName` 中。
- 质量基线只减不增（`./tools/dev/quality-baseline --check-shrink origin/main`）。

## Constitution Check

| 原则 | 检查点 | 结论 |
| --- | --- | --- |
| I 单机内核优先 | 核心阶段仅承接通用 webhook，不接入企微/飞书/钉钉专用 SDK 或工作流编排 | 通过 |
| II ReAct 与工具执行统一掌控 | `NotifyTool` 实现 `OryxTool`，经 `ToolExecutor` 统一校验与调度，不启用 Spring AI 自动执行 | 通过 |
| III 职责分离 | 新增 `NotifyChannelAdapter` 与 `NotifyTarget`，`NotifyTool` 依赖接口而非具体 `WebhookNotifyAdapter`，无循环依赖 | 通过 |
| IV 凭证与数据边界 | `NotifyTarget.toString()` 脱敏；授权校验先于网络发送；不暴露 URL/Header 到 Schema 或日志 | 通过 |
| V 审计与持久化 | 复用 `ToolExecutor` → `tool_invocations` 既有审计路径，不新增表或伪造成功状态 | 通过 |
| VI 可演示可检查 | 新增 2 套课件命名测试类直接覆盖全部回归点；真实 webhook 联调列为剩余人工确认项 | 通过 |
| VII 一致性 | 5 项保留偏差有宪章/spec 001 依据并落入 `lesson-19-revision.md` | 通过 |

## Project Structure

### Documentation (this feature)

```text
specs/009-notify-boundary-refactor/
├── spec.md
├── plan.md                         # 本文件
├── research.md                     # Phase 0 现状、依赖树与接口设计核查
├── data-model.md                   # Phase 1 接口契约与测试归属映射
├── quickstart.md                   # Phase 1 验证命令清单
├── checklists/requirements.md
├── evidence/                       # 实施阶段验证记录
├── lesson-19-revision.md           # 实施阶段末尾（FR-010）
└── tasks.md                        # 任务拆解
```

### Source Code (repository root)

```text
oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/
├── NotifyChannelAdapter.java       # 新：渠道中立出站通知接口
├── NotifyTarget.java               # 新：渠道中立通知目标 record（toString 脱敏）
├── NotificationTargetResolver.java # 改：Target 新增 toNotifyTarget() 转换
├── WebhookNotifyAdapter.java       # 改：implements NotifyChannelAdapter，保留旧重载兼容
└── NotifyTool.java                 # 改：依赖 NotifyChannelAdapter 接口而非具体类

oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/
├── WebhookNotifyAdapterTest.java   # 新：4 项（@Tag("us4-tools")）
└── NotifyToolsTest.java            # 新：4 项（@Tag("us4-tools")）

tools/dev/test-groups.json          # 改：us4-tools 新增上述 2 类共 8 项（21 → 29）
tools/dev/README.md                 # 改：us4-tools 说明同步新增 2 类与用例数
specs/001-single-node-agent-runtime/verification.md  # 改：us4-tools 分组表同步
```

**Structure Decision**: 不新增/合并 Maven 模块，不改动前序第 16/17/18 节交付的任何模块与公共接口；仅在 `oryxos-tool` 的 `notify` 包内补齐渠道中立抽象与两套验收 harness，对 `oryxos-boot` 和既有测试保持 100% 源码与行为兼容。

## 实施顺序与依赖

| 阶段 | 内容 | 依赖 | 退出条件 |
| --- | --- | --- | --- |
| S0 基线核查 | 核查 `oryxos-tool` 依赖树、`us4-tools` 现状（21 项）与 `quality-baseline` 基线 | — | `evidence/baseline-snapshot.md` |
| S1 渠道中立抽象与适配 | 新增 `NotifyTarget`、`NotifyChannelAdapter`；更新 `NotificationTargetResolver`、`WebhookNotifyAdapter`、`NotifyTool` | S0 | `oryxos-tool` 编译通过且既有 `NotifyContractTest` 全绿 |
| S2 Webhook 适配器 harness | 新增 `WebhookNotifyAdapterTest`（4 项），登记入 `test-groups.json` | S1 | `WebhookNotifyAdapterTest` 全绿 |
| S3 通知工具 harness | 新增 `NotifyToolsTest`（4 项，含 `authorize` 先于 `send` 顺序断言与 `ToolExecutor` 审计校验），登记入 `test-groups.json` | S1 | `NotifyToolsTest` 全绿 |
| S4 分组与文档同步 | 更新 `tools/dev/README.md`、`specs/001-single-node-agent-runtime/verification.md` 与 `lesson-19-revision.md` | S2、S3 | 5 项保留偏差与 2 类映射齐全 |
| S5 全量门禁验收 | 运行 `us4-tools`、全量 `clean verify`、`quality-baseline --check-shrink origin/main` 与 H4 六条不变量核查 | S4 | 全绿，证据写入 `evidence/final-verification.md` |
