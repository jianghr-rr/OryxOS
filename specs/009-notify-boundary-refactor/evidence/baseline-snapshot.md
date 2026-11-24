# Baseline Snapshot: 第 19 节 Notify 模块边界对齐

- **Date**: 2026-11-24
- **Branch**: `009-lesson19-notify-boundary`（自 `008-lesson18-cli-boundary` 切出）
- **JDK**: OpenJDK 21.0.13 (`/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home`)

## 1. `oryxos-tool` 离线依赖树核实

通过 `./tools/dev/mvnw -B -o -pl oryxos-tool dependency:tree -DoutputType=text` 核实：
- `org.apache.httpcomponents.client5:httpclient5:5.6.5`
- `io.github.oryx-labs:oryxos-core:1.0.0-SNAPSHOT`
- `io.modelcontextprotocol.sdk:mcp:0.18.3`
- `org.springframework.ai:spring-ai-model:1.1.8`（传递 `jackson-databind:2.21.4`）
- `com.networknt:json-schema-validator:1.5.9`
- `org.junit.jupiter:junit-jupiter:5.12.2`（test）

结论：本次不新增任何第三方依赖（不引入 `spring-web` / `mockito` / `mockwebserver`）。

## 2. `us4-tools` 分组基线（21 项）

- `ToolsContractTest`：7 项
- `NotifyContractTest`：4 项
- `McpContractTest`：5 项
- `ToolIntegrationTest`：4 项
- `AgentCatalogContractTest`：1 项

本次计划在 `us4-tools` 新增 `WebhookNotifyAdapterTest`（4 项）与 `NotifyToolsTest`（4 项），合计 21 → 29 项。

## 3. 前序模块零改动范围

- `oryxos-core` / `oryxos-provider` / `oryxos-storage` / `oryxos-cli` / `oryxos-channel-cli` / `oryxos-boot` 主代码均不改动，公共接口与表结构保持不变。
