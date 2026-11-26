# Implementation Plan: Tool 体系边界对齐与验收 harness（对齐第 20 节课件与技术方案）

**Branch**: `010-lesson20-tool-boundary` | **Date**: 2026-11-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/010-tool-boundary-refactor/spec.md`

## Summary

第 20 节覆盖的 Tool 体系核心生产代码（`OryxTool`/`ToolDescriptor`/`ToolResult`/`ToolRegistryPort`、`ToolRegistry`、9 个内置工具含第 19 节 `NotifyTool`、方式三 `NativeToolRegistrar`/`AnnotatedToolAdapter`/`EchoTool`、方式二 `McpClientManager`/`McpToolDiscovery`/`McpToolAdapter`）已在 spec 001~009 落地。对照 `docs/第20节：Tool 体系 原理解析、实现与代码讲解.md` 与 `docs/TechnicalSolution.md`（§6.1~6.6、§10），识别出两类对齐缺口：

1. **MCP 启动故障告警可观测性补齐**：`McpClientManager` 在单个 MCP server 初始化或配置失败时虽已隔离故障并记录状态，但缺少脱敏 `LOG.warn(...)` 告警日志（课件要求“失联的 server 记 WARN 跳过，不能变成自己的启动故障”）。本次在 `McpClientManager` 补齐脱敏 WARN 日志（仅记清洗后的 server 标识与失败状态码，不泄露环境变量与密钥），不改动任何公共方法签名。
2. **课件验收 harness 7 个命名测试类全部补齐**：新增 `OryxToolContractTest`、`ToolRegistryTest`、`FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`、`McpClientServiceTest`、`McpToolAdapterTest`（均落位 `oryxos-tool`，打 `@Tag("us4-tools")`），将课件两个最值钱的回归测试（`@ParameterizedTest @MethodSource("allRegisteredTools")` 契约三件套检查 + MCP 失联不拖垮启动和其他工具）原样落地，英文方法名 + `@DisplayName` 保留课件原文。
3. **测试分组与验证文档同步**：更新 `tools/dev/test-groups.json`（`us4-tools` 分组追加上述 7 类）、`tools/dev/README.md`、`specs/001-single-node-agent-runtime/verification.md`。
4. **第 20 节课件修订建议**：交付 `specs/010-tool-boundary-refactor/lesson-20-revision.md`，逐条记录 7 项保留偏差、真实代码路径与 7 个验收测试类映射。

技术核查见 [research.md](./research.md)，契约与测试映射见 [data-model.md](./data-model.md)，验证命令见 [quickstart.md](./quickstart.md)。

## Technical Context

**Language/Version**: JDK 21（父 POM `maven.compiler.release=21`）+ Spring Boot 3.5.16 + Spring AI（`spring-ai-bom:1.1.8`，已通过 `./tools/dev/mvnw -B -o -pl oryxos-tool dependency:tree` 确认锁定 BOM 依赖存在）、SQLite + Spring Data JPA（手工建表脚本，不依赖 `hibernate.ddl-auto=update`）。凭证走环境变量占位或受控本地配置（`SecretResolver`），不落明文。

**Primary Dependencies**: `oryxos-tool` 锁定使用 `httpclient5:5.6.5`、`oryxos-core`（含 `slf4j-api:2.0.18`）、`io.modelcontextprotocol.sdk:mcp:0.18.3`、`spring-ai-model:1.1.8`、`json-schema-validator:1.5.9`、`junit-jupiter:5.12.2`（含 `junit-jupiter-params`）。不新增、不升级任何第三方依赖（FR-008）。

**Storage**: 无新增数据库表或迁移脚本；工具调用审计沿用既有 `tool_invocations`（`V004__call_audits.sql`）。

**Module Placement（按第 20 节与 TechnicalSolution §10）**:
- `OryxTool` / `ToolResult` / `ToolDescriptor` / `ToolRegistryPort` → `oryxos-core`（已就位，本次不改动）
- `ToolRegistry` / 内置工具（`ReadFileTool`、`WriteFileTool`、`ListDirTool`、`ShellTool`、`HttpGetTool`、`HttpPostTool`、`NotifyTool`）/ 原生扩展（`NativeToolRegistrar`、`AnnotatedToolAdapter`、`EchoTool`）/ MCP（`McpClientManager`、`McpToolDiscovery`、`McpToolAdapter`）→ `oryxos-tool`（本次仅在 `McpClientManager` 补齐脱敏 WARN 日志）
- 7 个验收 harness 测试类 → `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/`（对应子包）

**Testing Strategy（按课件“验收 harness”）**:
- 测试策略按课件“验收 harness”执行：`OryxToolContractTest`、`ToolRegistryTest`、`FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`、`McpClientServiceTest`、`McpToolAdapterTest`（覆盖注册表全工具契约三件套与缺 schema 拦截、三来源统一注册与按 Profile `tools` 精确过滤不多不少、文件/Shell/HTTP 正常跑通与白名单越界拦截、MCP 失联隔离与元数据/参数转发/错误封装）。
- 单测默认跑、集成冒烟打 `@Tag("integration")`（既有 `ConfiguredMcpIT` 属 `real-mcp` 组，CI 默认跳过）；本次新增 7 类均为离线本地单测，纳入 `us4-tools` 分组由 `test_report_gate.py` 强制核对；实现完成的定义是 `./tools/dev/mvnw -B -o clean verify` 全绿。

**Constraints & Static Checks**:
- 避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch 的 `default ->` 写法），静态检查（Spotless + Checkstyle/P3C + SpotBugs + FindSecBugs + PMD + quality-baseline）是构建门禁。
- 测试方法名全部为英文，课件原文保留在 `@DisplayName` 中。
- 质量基线只减不增（`./tools/dev/quality-baseline --check-shrink origin/main`）；修改 `McpClientManager` 时零新增告警（避免 `CRLF_INJECTION_LOGS` 等安全告警）。

## Constitution Check

| 原则 | 检查点 | 结论 |
| --- | --- | --- |
| I 单机内核优先 | 核心阶段聚焦内置工具 + 三档扩展 + 应用层白名单，不做 Tool Policy 引擎/按需加载/容器沙箱/并行工具/MCP Server 暴露 | 通过 |
| II ReAct 与工具执行统一掌控 | `@Tool` 仅在 `AnnotatedToolAdapter` 用于生成 JSON Schema，执行统一走 `ToolExecutor`，关闭 Spring AI 自动执行；未知副作用不隐式重试 | 通过 |
| III 职责分离 | `OryxTool`/`ToolRegistryPort` 留 `oryxos-core`，实现与注册表在 `oryxos-tool`，零循环依赖，不改前序公共接口 | 通过 |
| IV 工具操作受控与凭证边界 | 所有文件/Shell/HTTP/Notify/MCP 调用先过白名单或显式授权；`McpClientManager` WARN 日志脱敏不泄环境变量与密钥 | 通过 |
| V 审计与持久化 | 复用 `ToolExecutor` → `tool_invocations` 既有审计链，不新增表 | 通过 |
| VI 可演示可检查 | 新增 7 个课件命名测试类直接覆盖全部回归点；真实模型+真实 MCP 验证归入人工剩余项 | 通过 |
| VII 一致性 | 7 项保留偏差有宪章/spec 001~009 依据并沉淀至 `lesson-20-revision.md` | 通过 |

## Project Structure

### Documentation (this feature)

```text
specs/010-tool-boundary-refactor/
├── spec.md
├── plan.md                         # 本文件
├── research.md                     # Phase 0 依赖树与现状核查
├── data-model.md                   # Phase 1 契约与 7 类测试映射
├── quickstart.md                   # Phase 1 验证命令清单
├── checklists/requirements.md
├── evidence/                       # 实施阶段验证证据
├── lesson-20-revision.md           # 实施阶段交付（FR-008）
└── tasks.md                        # 任务拆解
```

### Source Code (repository root)

```text
oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/mcp/
└── McpClientManager.java           # 改：启动失败/失联分支补齐脱敏 SLF4J WARN 日志

oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/
├── OryxToolContractTest.java       # 新：参数化遍历全工具契约三件套 + 缺 schema 拦截
├── ToolRegistryTest.java           # 新：三来源统一注册 + Profile 精确过滤 + 重名拒绝
├── file/FileToolsTest.java         # 新：read_file/write_file/list_dir 正常 + 越界拦截
├── shell/ShellToolsTest.java       # 新：shell 正常 + 白名单外命令/参数拦截
├── http/HttpToolsTest.java         # 新：http_get/http_post 正常 + 白名单外域名拦截
└── mcp/
    ├── McpClientServiceTest.java   # 新：MCP 失联隔离回归 + mcp_servers.yaml 加载校验
    └── McpToolAdapterTest.java     # 新：元数据映射 + 参数转发 + 未授权/远端错误封装

tools/dev/test-groups.json          # 改：us4-tools 追加上述 7 类精确方法清单
tools/dev/README.md                 # 改：同步 us4-tools 说明与类/用例计数
specs/001-single-node-agent-runtime/verification.md  # 改：同步 us4-tools 分组表
```

**Structure Decision**: 不新增或合并 Maven 模块，不改动前序第 16~19 节交付的任何模块主代码与公共接口；仅在 `oryxos-tool` 补齐 `McpClientManager` 启动失败脱敏 WARN 日志与 7 个课件验收 harness 命名测试类，保持 100% 行为与源码兼容。

## 实施顺序与依赖

| 阶段 | 内容 | 依赖 | 退出条件 |
| --- | --- | --- | --- |
| S0 基线核查 | 记录 `oryxos-tool` 离线依赖树、当前 `us4-tools` 分组（29 项）与质量基线（444 条）快照 | — | `evidence/baseline-snapshot.md` |
| S1 契约与注册表 harness | 落地 `OryxToolContractTest`（含 `@ParameterizedTest` 遍历）与 `ToolRegistryTest` | S0 | 两类定向单测全绿 |
| S2 内置工具 harness | 并行落地 `FileToolsTest`、`ShellToolsTest`、`HttpToolsTest` | S0 | 三类定向单测全绿 |
| S3 MCP 告警日志与 harness | 补齐 `McpClientManager` 脱敏 WARN 日志，落地 `McpClientServiceTest` 与 `McpToolAdapterTest` | S0 | 两类定向单测 + `McpContractTest` 全绿 |
| S4 分组与文档同步 | 按实测 Surefire XML 登记 `test-groups.json`（`us4-tools`），同步 `tools/dev/README.md`、`verification.md` 与 `lesson-20-revision.md` | S1~S3 | `test -Dgroups=us4-tools` 全绿且 `test_report_gate.py` 零偏差 |
| S5 全量门禁验收 | 运行 `clean verify`、`quality-baseline --check-shrink origin/main`、H4 六条不变量自查并写入验收证据 | S4 | 全绿，证据落入 `evidence/final-verification.md` |
