# Baseline Snapshot: 第 20 节 Tool 体系边界对齐

- **Date**: 2026-11-26
- **Branch**: `010-lesson20-tool-boundary`（自已合入 PR #12 的 `origin/main` `ff7c4ac` 切出）
- **JDK**: OpenJDK 21.0.13 (`/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home`)

## 1. `oryxos-tool` 离线依赖树核实

通过 `./tools/dev/mvnw -B -o -pl oryxos-tool dependency:tree -DoutputType=text` 核实（BUILD SUCCESS, 0.510s）：
- `org.apache.httpcomponents.client5:httpclient5:5.6.5`（传递 `org.slf4j:slf4j-api:2.0.18`）
- `io.github.oryx-labs:oryxos-core:1.0.0-SNAPSHOT`（直接依赖 `org.slf4j:slf4j-api`）
- `io.modelcontextprotocol.sdk:mcp:0.18.3`（传递 `mcp-core:0.18.3`、`mcp-json-jackson2:0.18.3`）
- `org.springframework.ai:spring-ai-model:1.1.8`（传递 `jackson-databind:2.21.4`、`spring-ai-commons:1.1.8`）
- `com.networknt:json-schema-validator:1.5.9`
- `org.junit.jupiter:junit-jupiter:5.12.2`（test，含 `junit-jupiter-params:5.12.2`）

结论：本次不新增、不升级任何第三方依赖（SLF4J 与 JUnit 5 `@ParameterizedTest` 均已在锁定 BOM 中）。

## 2. `us4-tools` 分组基线（29 项）

- `ToolsContractTest`：7 项
- `NotifyContractTest`：4 项
- `WebhookNotifyAdapterTest`：4 项（spec 009 新增）
- `NotifyToolsTest`：4 项（spec 009 新增）
- `McpContractTest`：5 项
- `ToolIntegrationTest`：4 项
- `AgentCatalogContractTest`：1 项

本次计划在 `us4-tools` 新增第 20 节课件命名的 7 个测试类：`OryxToolContractTest`、`ToolRegistryTest`、`FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`、`McpClientServiceTest`、`McpToolAdapterTest`。

## 3. 质量基线与前序模块零改动范围

- `origin/main` 质量基线：`config/quality/baseline.json` 共 444 条（其中 `McpClientManager.java` 存量 6 条）。
- 前序模块 `oryxos-core` / `oryxos-provider` / `oryxos-memory` / `oryxos-storage` / `oryxos-cli` / `oryxos-channel-cli` / `oryxos-web` / `oryxos-boot` 主代码均不改动，公共接口与表结构保持不变。
