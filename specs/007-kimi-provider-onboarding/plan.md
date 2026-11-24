# Implementation Plan: Kimi Provider 独立接入与真实验收

**Branch**: `007-kimi-provider-onboarding`（规格目录名；未创建 Git 分支） | **Date**: 2026-11-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/007-kimi-provider-onboarding/spec.md`

## Summary

Kimi 在当前代码中已可经 OpenAI 兼容协议调用（多轮 `reasoning_content` 回传、不发送 temperature 均已具备），但真实验收入口强制 DeepSeek 与 Kimi 同时就绪，只有 Kimi 凭证时无法取得任何真实证据。本计划**不改生产调用路径**，只做四件事：

1. **Kimi 专属真实验收**：新增两个 real 分组与两个薄测试类，与原双家入口共用抽取出的断言实现；补齐证据字段（耗时、token、失败原因）；构建入口对 Kimi 分组只转发 `KIMI_API_KEY`。
2. **Kimi 单家验收工作区模板**：`tools/dev/examples/real-provider-kimi/`。
3. **用户侧可发现**：init 模板与用户示例加入注释状态的规范 Kimi 块；用户文档写明启用步骤、推荐模型、选项限制与密钥注入方式；新增本地 `kimi-onboarding` 分组从 CLI 入口验证。
4. **spec 001 记账**：T027、T042 拆分 Kimi / DeepSeek 子项，验收口径不变。

技术决定与依据见 [research.md](./research.md)。

## Technical Context

**Language/Version**: Java 21（编译目标）；Python 3（`tools/dev` 构建入口与其单元测试）

**Primary Dependencies**: Spring Boot 3.5.16、Spring AI 1.1.8（`spring-ai-openai` 低层 `OpenAiApi`，仅协议适配）、JUnit 5、Picocli；不新增依赖

**Storage**: 无新增持久化；审计沿用现有 SQLite `llm_calls`/`tool_invocations`；证据为本机 JSON + 仓库 Markdown 摘要

**Testing**: `./tools/dev/mvnw` 分组门禁（`tools/dev/test-groups.json` + `check-test-reports.py`）；`python3 tools/dev/test_build_environment.py`

**Target Platform**: 本机 macOS / Linux，JDK 21；真实验收需直连 `https://api.moonshot.cn`

**Project Type**: 多模块 Maven 单体（CLI + Web Service），本特性主要触及 provider/boot 测试、cli 资源、构建脚本与文档

**Performance Goals**: 真实单次请求在 60 秒预算内完成（`reasoning_effort: low`）；不新增性能目标

**Constraints**: 不新增协议/适配；不放宽双家验收；凭证不入仓库/参数/日志/证据；无自动重试；不修改质量基线（只减不增）；保留工作区内 006 等未提交改动（`tools/dev/test-groups.json`、`tools/dev/README.md` 已有修改，编辑时增量合并）

**Scale/Scope**: 新增约 3 个测试类 + 2 个测试支持类、1 套示例工作区（3 文件）、2 处模板注释块、1 处构建脚本映射、文档 4–5 处、spec 001 任务拆分

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查 | 结果 |
| --- | --- | --- |
| I 单机内核优先 | 只补 Provider 验收与配置体验，不引入 fallback/竞速/新入口 | PASS |
| II ReAct 与工具执行统一掌控 | 不改 ReAct/ToolExecutor；工具回填验收断言“恰一次执行、ID 配对、无自动重试” | PASS |
| III 能力职责分离 | Kimi 仍通过 Provider 名称显式映射；不按 Provider 名称在通用校验中开特例（research R3） | PASS |
| IV 凭证与数据边界 | 只用 `${KIMI_API_KEY}` 引用；Kimi 分组只转发 Kimi 凭证；证据不含凭证与推理原文；SC-003 全文检索 | PASS |
| V 审计 | 真实调用走现有 `AuditedProvider`；证据补 token/耗时，未知标 null，不伪造 | PASS |
| VI 可演示、可检查 | 本地 `kimi-onboarding` 从 CLI 主入口验证；真实验收区分“前置失败 / 真实失败 / 通过”；不以模拟替代真实 | PASS |
| VII 一致性 | spec 001 T027/T042 拆分子项、口径不变；新增分组登记 verification.md；无宪章修改 | PASS |
| 范围约束表 | 运行配置文件维护、重启加载；不改管理台、调度、数据库位置 | PASS |

无违规，Complexity Tracking 留空。

**Post-design re-check（Phase 1 后）**: 契约与数据模型未引入新持久化、新协议或 Provider 特例；凭证转发表最小化；双家入口不变量写入契约 §6。仍全部 PASS。

## Project Structure

### Documentation (this feature)

```text
specs/007-kimi-provider-onboarding/
├── spec.md
├── plan.md                      # 本文件
├── research.md                  # Phase 0
├── data-model.md                # Phase 1
├── quickstart.md                # Phase 1
├── contracts/
│   ├── kimi-provider-config.md  # 规范配置块、注释形态、失败语义
│   └── kimi-real-acceptance.md  # 真实验收命令、输入、判定、输出、凭证转发
├── checklists/requirements.md
├── evidence/
│   ├── baseline.md              # 基线与双家回归验证
│   ├── kimi-preflight.md        # Kimi 专用分组负向前置验证
│   ├── kimi-real.md             # 实施后由维护者填写的真实验收摘要
│   └── final-verification.md    # 最终本地验证汇总
└── tasks.md                     # $speckit-tasks 生成
```

### Source Code (repository root)

```text
oryxos-provider/src/test/java/io/github/oryxlabs/oryxos/provider/
├── RealProviderAcceptance.java       # 新：前置检查(有序 List<String>) + 一次普通请求 + 证据（含 durationMs/completionsPath）
├── RealProviderIT.java               # 改：薄壳，Tag/类名/方法名不变，委托上者，检查 List.of("deepseek", "kimi")
└── RealKimiProviderIT.java           # 新：@Tag real-integration + real-provider-kimi；kimiOrdinaryRequest，只检查 List.of("kimi")

oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/
├── RealToolLoopAcceptance.java       # 新：前置检查 + 回环夹具 + 断言 + 证据（含 failureReason/token/durationMs）
├── RealProviderToolLoopIT.java       # 改：薄壳，Tag/类名/方法名不变
├── RealKimiToolLoopIT.java           # 新：@Tag real-integration + real-provider-tools-kimi；kimiToolResultRoundTrip
└── KimiOnboardingLocalIT.java        # 新：@Tag kimi-onboarding；init 模板 → 取消注释 → 回环假 Kimi → CLI 对话

oryxos-cli/src/main/resources/templates/runtime.yaml   # 改：加入注释状态 Kimi 块
examples/runtime.example.yaml                          # 改：加入注释状态 Kimi 块（生效内容不变，KernelFixture 不受影响）
tools/dev/examples/real-provider-kimi/                 # 新：runtime.yaml（仅 kimi + 回环白名单）、agents/real-kimi、agents/real-tools-kimi
tools/dev/build.py                                     # 改：分组 → 转发凭证显式映射
tools/dev/test_build_environment.py                    # 改：Kimi 分组只转发 KIMI_API_KEY
tools/dev/test-groups.json                             # 改：新增 real-provider-kimi、real-provider-tools-kimi、kimi-onboarding

docs/CliGuide.md                                       # 改：启用 Kimi 小节（步骤、推荐模型、选项限制、密钥注入、provider list）
examples/README.md                                     # 改：指向注释 Kimi 块
tools/dev/README.md                                    # 改：Kimi 单家真实验收入口
specs/001-single-node-agent-runtime/tasks.md           # 改：T027/T042 拆 -K/-D 子项
specs/001-single-node-agent-runtime/verification.md    # 改：分组表登记新分组
specs/001-single-node-agent-runtime/evidence/US1-real-models.md, US2-real-tools.md  # 改：链接 007 摘要
```

**Structure Decision**: 生产代码（`oryxos-provider/src/main`、`oryxos-core/src/main`）不改。变更集中在测试、资源模板、构建脚本与文档，复用现有模块边界；无模块增减。

## 实施分段与验证

| 段 | 内容 | 对应 | 验证 |
| --- | --- | --- | --- |
| S1 | 抽取 `RealProviderAcceptance` / `RealToolLoopAcceptance`，原两类改薄壳（行为不变） | R5、FR-007 | 编译 + `-Doryxos.tests.mode=real -Dgroups=real-provider` 在无 DeepSeek 时仍以 deepseek 缺失失败（前置失败，不发请求） |
| S2 | 新 Kimi 类、证据字段、`failureReason`、分组登记 | FR-002/003/004/006 | 无凭证运行 Kimi 分组 → 前置失败且信息指向 kimi；占位模型 → 指向模型 |
| S3 | `build.py` 映射 + Python 测试 | FR-011 | `python3 tools/dev/test_build_environment.py` |
| S4 | `real-provider-kimi` 示例工作区 | R9 | `KimiOnboardingLocalIT` 中加载该模板解析为可用 kimi（夹具密钥） |
| S5 | init 模板 / 用户示例注释块 + `KimiOnboardingLocalIT` | FR-009/009a/009b/010/012、US3 | `-Dgroups=kimi-onboarding`；`kernel-local` 仍通过 |
| S6 | 文档与 spec 001 拆分 | FR-008/010 | 文档引用检查；`rg` 校验无密钥字面值 |
| S7 | 全量回归 | SC-006 | `./tools/dev/mvnw clean verify` |
| S8 | 真实 Kimi 验收（维护者有凭证时） | SC-001/002/003 | quickstart B/C；未执行则如实标未验证，不勾选 T027-K/T042-K |

## 风险

- **工作区已有未提交改动**：`tools/dev/test-groups.json`、`tools/dev/README.md` 含 006 的修改；实施时只做增量追加，不覆盖。
- **真实模型非确定性**：工具回填可能出现 `NO_TOOL_CALL`；按 Q5 判失败、手动重跑，不改提示词去“迎合”通过以外的断言。
- **18473 端口冲突**：沿用原夹具端口；前置失败信息需指明端口占用。
- **Kimi 协议演进**：若真实联调暴露映射缺陷，另开缺陷修复，不在本计划内预改生产代码。

## Complexity Tracking

无（Constitution Check 无违规）。
