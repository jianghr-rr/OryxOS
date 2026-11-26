# Research: 第 20 节 Tool 体系现状、依赖树与设计核查

## 1. 本地依赖树核实

已运行 `./tools/dev/mvnw -B -o -pl oryxos-tool dependency:tree -DoutputType=text`（JDK 21.0.13）：
- `org.apache.httpcomponents.client5:httpclient5:5.6.5`（传递 `org.slf4j:slf4j-api:2.0.18`）
- `io.github.oryx-labs:oryxos-core:1.0.0-SNAPSHOT`（直接依赖 `org.slf4j:slf4j-api`）
- `io.modelcontextprotocol.sdk:mcp:0.18.3`（传递 `mcp-core:0.18.3`、`mcp-json-jackson2:0.18.3`）
- `org.springframework.ai:spring-ai-model:1.1.8`（提供 `@Tool` 与 `ToolDefinitions.from(Method)`，传递 `jackson-databind:2.21.4`）
- `com.networknt:json-schema-validator:1.5.9`（离线 JSON Schema 校验）
- `org.junit.jupiter:junit-jupiter:5.12.2`（test，含 `junit-jupiter-params:5.12.2`，支持 `@ParameterizedTest` + `@MethodSource`）

结论：
- `oryxos-tool` 已具备 SLF4J API（`slf4j-api:2.0.18`）、MCP Java SDK（`0.18.3`）、Spring AI `@Tool` Schema 生成（`spring-ai-model:1.1.8`）与 JUnit 5 参数化测试（`junit-jupiter-params:5.12.2`）。
- `oryxos-tool` 未引入 Mockito；遵循“不新增 plan 未列明的第三方依赖”原则，测试使用真实临时目录、JDK `com.sun.net.httpserver.HttpServer` 本地回环服务与现有可控 stdio MCP 夹具脚本（`oryxos-tool/src/test/resources/mcp/fixture-server.py`），不新增任何第三方依赖。

## 2. 既有代码与第 20 节课件逐项对照

### 2.1 `oryxos-core` 统一抽象层（已就位，本次不改动）
- `OryxTool`（`oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/tool/OryxTool.java`）：定义 `ToolDescriptor descriptor()`、`void authorize(Map<String, Object>, ExecutionContext)`、`ToolResult execute(Map<String, Object>, ExecutionContext)`。
- `ToolDescriptor`：提供 `name()`、`description()`、`inputSchemaJson()`、`safeToRetry()`、`sideEffecting()`、`serverId()`、`remoteName()`、`source()`（`BUILTIN`/`NATIVE`/`MCP`）。参数 Schema 已完整暴露且供 `ProviderOptionsMapper` 与 `ToolArgumentValidator` 消费。
- `ToolResult`：提供 `outcome`（`SUCCESS`/`ERROR`/`DENIED`/`UNKNOWN`/`NOT_EXECUTED`）、`content`、`errorCode`、`safeMessage`、`retryClass`（`NONE`/`SAFE_TRANSIENT`）、`sideEffectState`（`NONE`/`CONFIRMED`/`UNKNOWN`）、`callId`、`attemptCount`、`durationMs`。
- `ToolRegistryPort`：定义 `find`/`all`/`available`/`validate`/`authorize`。

### 2.2 `oryxos-tool` 与 `oryxos-memory` 九个内置工具及 `NotifyTool` 注册（已就位）
- 文件 3 个：`ReadFileTool`（`read_file`）、`WriteFileTool`（`write_file`）、`ListDirTool`（`list_dir`），均在 `authorize`/`execute` 入口调用 `FileAccessPolicy.authorize(...)`。
- Shell 1 个：`ShellTool`（`shell`），在 `authorize`/`execute` 入口调用 `ProcessPolicy.authorize(...)`。
- HTTP 2 个：`HttpGetTool`（`http_get`）、`HttpPostTool`（`http_post`），在 `authorize`/`execute` 入口调用 `ControlledHttpClient.authorize(...)` / `exchange(...)`（内部首行过 `HttpTargetPolicy` 白名单）。
- Notify 1 个：`NotifyTool`（`notify`，第 19 节交付），已在 `ToolConfiguration.notifyTool(...)` 注册为 `OryxTool` Bean 进入 `ToolRegistry`。
- Memory 2 个：`SaveMemoryTool`（`save_memory`）、`RecallMemoryTool`（`recall_memory`），位于 `oryxos-memory` 并在 `MemoryToolConfiguration` 注册为 `OryxTool` Bean 进入 `ToolRegistry`（第 22 节负责其专项边界与测试）。

### 2.3 三档 Plugin Tool 接入与 `ToolRegistry`（已就位）
- 方式一（零代码）：`.oryxos/agents/<name>/AGENT.md` + 可选 `skills/*.md` + `mcp_servers` 引用，由第 17 节 `ContextLoader`/`AgentDefinitionReader` 处理（按技术方案 §6.3 / §11.1 不作为可执行工具）。
- 方式二（MCP）：`McpClientManager`（加载 `.oryxos/mcp_servers.yaml`、stdio 传输初始化与分页 `tools/list`、单 server 故障隔离）、`McpToolDiscovery`（包装为 `McpToolAdapter`）、`McpToolAdapter`（实现 `OryxTool`，校验 Agent `mcpServers` 授权并转发 `tools/call`）。
- 方式三（Java `@Tool` Spring Bean）：`NativeToolRegistrar` + `AnnotatedToolAdapter` + `EchoTool`——使用 Spring AI `@Tool` 注解与 `ToolDefinitions.from(method)` 生成离线 JSON Schema，包装成 `OryxTool` 后由 `ToolExecutor` 执行，绝不开启 Spring AI 自动工具执行。
- `ToolRegistry`：一次性接纳上述三类 `OryxTool`，调用 `ToolArgumentValidator.register(d.inputSchemaJson())` 做离线 Schema 校验、拒绝重名与 `ToolNameMap` wireName 冲突；`available(AgentProfile)` 按 `agent.tools()` 精确过滤。

## 3. 识别出的真实缺口与设计决策

### 3.1 `McpClientManager` 启动失败分支补齐脱敏 WARN 日志
- **现状**：`McpClientManager` 构造器第 136~142 行捕获单 server 初始化/配置异常后，正确地将 `connection.disabled = true`、关闭进程、记录 `states.put(d.id(), new State(..., false, code, closed))`，并继续初始化后续 server，但**未输出 SLF4J WARN 日志**。
- **决策**：在 `McpClientManager` 引入 `org.slf4j.Logger`（已在 `oryxos-tool` 编译依赖中），在 catch 分支记录脱敏 WARN 日志：仅输出经安全清洗的 server 标识与分类状态码（`MCP_INITIALIZATION_FAILED` / `MCP_CONFIGURATION_ERROR`），绝不打印 `d.environment()` 或未脱敏异常内容；同时清理改动触及处避免引入任何 Checkstyle/PMD/SpotBugs/FindSecBugs 告警（特别是避免 `CRLF_INJECTION_LOGS`）。
- **收益**：满足课件“失联的 server 记 WARN 跳过，不能变成自己的启动故障”与 `oryxos-lesson-dev` H1/H5“异常不吞（catch 必落审计/日志或上抛）”纪律，且零破坏既有公共 API。

### 3.2 补齐 7 个课件验收 harness 命名测试类（均落位 `oryxos-tool`）
- **决策**：在 `oryxos-tool` 新增 7 个与课件第 20 节完全同名的测试类，统一标注 `@Tag("us4-tools")` 并纳入 `tools/dev/test-groups.json`：
  1. `OryxToolContractTest`（`io.github.oryxlabs.oryxos.tool`）：`@ParameterizedTest` + `@MethodSource("allRegisteredTools")` 遍历 `ToolRegistry` 全部三来源工具（`oryxos-tool` 全部 7 个内置工具 + `@Tool` 原生扩展 `EchoTool` + `McpToolAdapter`，共 9 个工具），验证 `name`/`description`/`inputSchemaJson` 非空且为合法离线 JSON Schema；另设常规 `@Test` 验证缺失或非法 `inputSchemaJson` 的工具在注册阶段即被拒绝。
  2. `ToolRegistryTest`（`io.github.oryxlabs.oryxos.tool`）：验证三来源工具统一以 `OryxTool` 注册、按 `AgentProfile.tools` 过滤不多不少精确匹配（含空列表与含未知工具名边界）、重名注册被拒。
  3. `FileToolsTest`（`io.github.oryxlabs.oryxos.tool.file`）：验证 `read_file`/`write_file`/`list_dir` 正常读写列目录与越界（`../`、未授权目录、控制文件、越界符号链接）拦截零副作用。
  4. `ShellToolsTest`（`io.github.oryxlabs.oryxos.tool.shell`）：验证 `shell` 允许命令+受控脚本正常跑通与白名单外命令/禁止 `-c` 参数被拦截。
  5. `HttpToolsTest`（`io.github.oryxlabs.oryxos.tool.http`）：验证 `http_get` 正常取回响应与白名单外域名拦截零网络 IO（含 302 跳转至白名单外域名被拦），以及 `http_post` 白名单内发送与白名单外拦截。
  6. `McpClientServiceTest`（`io.github.oryxlabs.oryxos.tool.mcp`）：原样落地课件最值钱回归测试——坏 MCP server（`init-fail` / 不可达）与好 MCP server（`normal`）共存时，启动不抛异常、好 server 工具照常注册进 `ToolRegistry`、坏 server 工具不注册且状态标记失败；另测 `mcp_servers.yaml` 加载与 `${ENV_VAR}` 密钥引用解析及脱敏。
  7. `McpToolAdapterTest`（`io.github.oryxlabs.oryxos.tool.mcp`）：验证 `McpToolAdapter` 将 MCP 元数据映射为 `ToolDescriptor`（`name`/`description`/`inputSchemaJson`）、`execute` 以远端原始工具名原样转发参数并返回 `ToolResult`，以及未授权 server 拒绝与远端 `isError=true` 封装为不可重试 `ERROR`。
- **关于 `check-test-reports.py` 对 `@ParameterizedTest` 的核对**：
  `tools/dev/check-test-reports.py` 按 Surefire XML 的 `<testcase name="...">` 精确匹配 `test-groups.json` 中的 `methods` 列表。实施阶段运行 `OryxToolContractTest` 后，按 Surefire 实际产出的 `<testcase name="...">` 登记进 `test-groups.json`，确保 `test_report_gate.py` 100% 精确核对且零偏差。
