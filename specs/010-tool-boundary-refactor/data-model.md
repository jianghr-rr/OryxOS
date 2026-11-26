# Data Model & Test Mapping: 第 20 节 Tool 体系

## 1. 核心契约与模块归属（保持零破坏）

### 1.1 `oryxos-core`（既有接口，不改动）
- `OryxTool`（`io.github.oryxlabs.oryxos.core.tool.OryxTool`）：
  - `ToolDescriptor descriptor()`
  - `void authorize(Map<String, Object> arguments, ExecutionContext context)`
  - `ToolResult execute(Map<String, Object> arguments, ExecutionContext context)`
- `ToolDescriptor`：`(name, description, inputSchemaJson, safeToRetry, sideEffecting, serverId, remoteName, source)`
- `ToolResult`：`(outcome, content, errorCode, safeMessage, retryClass, sideEffectState, callId, attemptCount, durationMs)`
- `ToolRegistryPort`：`find(String)`、`all()`、`available(AgentProfile)`、`validate(ToolDescriptor, String)`、`authorize(OryxTool, Map<String, Object>, ExecutionContext)`

### 1.2 `oryxos-tool`（既有实现 + `McpClientManager` WARN 日志补齐）
- `ToolRegistry`：统一汇总 `BUILTIN`（7 个 + `oryxos-memory` 2 个）、`NATIVE`（`@Tool` 扩展 via `NativeToolRegistrar` / `AnnotatedToolAdapter`）与 `MCP`（via `McpToolDiscovery` / `McpToolAdapter`）。
- `ReadFileTool` / `WriteFileTool` / `ListDirTool`（`tool.file`）：由 `FileAccessPolicy` 守路径白名单。
- `ShellTool`（`tool.shell`）：由 `ProcessPolicy` 守命令与脚本白名单。
- `HttpGetTool` / `HttpPostTool`（`tool.http`）：由 `ControlledHttpClient` / `HttpTargetPolicy` 守域名与端口白名单。
- `NotifyTool`（`tool.notify`）：第 19 节交付，已注册为统一 `OryxTool` Bean。
- `McpClientManager`（`tool.mcp`）：加载 `.oryxos/mcp_servers.yaml`（`schema_version: 1`、`servers.<name>.transport/command/args/env`），初始化各 MCP server；单个 server 失联/配置失败时记录脱敏 `LOG.warn(...)`、更新不可用 `State`、跳过其工具、继续初始化其他 server。
- `McpToolAdapter`（`tool.mcp`）：将远端 MCP 工具适配为 `OryxTool`，工具名格式 `mcp.<server>.<remote>`，转发时还原为远端 `remoteName`。

---

## 2. 验收 Harness 测试类与回归守点映射（7 类）

全部新增测试类位于 `oryxos-tool`，打 `@Tag("us4-tools")` 并登记至 `tools/dev/test-groups.json` 的 `us4-tools` 分组（原有 29 项，实施时按实际方法/参数化实例数同步精确登记）。

### 2.1 `OryxToolContractTest`
路径：`oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/OryxToolContractTest.java`

| 英文测试方法名 | `@DisplayName`（课件对照） | 守住的验收点 |
| --- | --- | --- |
| `everyRegisteredTool_hasCompleteContract(OryxTool)`（`@ParameterizedTest` + `@MethodSource("allRegisteredTools")`） | 每个工具的契约三件套都不能缺 | 遍历 `ToolRegistry` 内全部三来源工具（7 内置 + 1 `@Tool` + 1 MCP，共 9 项），断言 `name`/`description`/`inputSchemaJson` 均非空非空白且通过离线 JSON Schema 校验 |
| `registry_rejectsToolWithMissingOrInvalidInputSchema` | 缺失或非法 inputSchema 的工具在注册时被拒绝 | 模拟漏写 `inputSchemaJson`（null/非法 JSON/含外部 `$ref`）的工具，断言注册立即报错 |

### 2.2 `ToolRegistryTest`
路径：`oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/ToolRegistryTest.java`

| 英文测试方法名 | `@DisplayName`（课件对照） | 守住的验收点 |
| --- | --- | --- |
| `registersAllThreeSourcesAsUnifiedOryxTool` | 三种来源的工具都以 OryxTool 身份注册进来 | `BUILTIN`、`NATIVE`（`@Tool`）、`MCP` 三类来源统一进入同一 `ToolRegistry`，`find`/`all` 均可见且来源标记准确 |
| `filtersByProfileToolsExactlyWithoutExtraOrMissing` | 按 Profile 的 tools 字段过滤后，子集精确匹配、不多不少 | 跨三来源声明子集时返回集合与声明列表严格相等（不多不少）；空列表返回空；未注册名不会凭空出现 |
| `rejectsDuplicateToolNamesAtRegistration` | 重复工具名在注册时被拒绝 | 两个同名 `OryxTool` 注册时抛出 `OryxError(CONFIGURATION_ERROR)` |

### 2.3 `FileToolsTest`
路径：`oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/file/FileToolsTest.java`

| 英文测试方法名 | `@DisplayName`（课件对照） | 守住的验收点 |
| --- | --- | --- |
| `fileTools_readWriteAndListWithinAllowedPaths` | read_file/write_file/list_dir 在白名单目录内应正常跑通 | `write_file` 写入、`read_file` 读回、`list_dir` 列出全部成功且内容一致 |
| `fileTools_rejectOutOfBoundsPathsTraversalAndSymlinks` | 文件工具命中白名单外路径、路径穿越、控制文件或越界符号链接应被拦下 | `../`、未授权目录、受保护控制文件与逃逸符号链接均在 IO 前抛出 `OryxError` 且外部文件未被修改 |

### 2.4 `ShellToolsTest`
路径：`oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/shell/ShellToolsTest.java`

| 英文测试方法名 | `@DisplayName`（课件对照） | 守住的验收点 |
| --- | --- | --- |
| `shell_executesAllowedCommandAndTrustedScript` | shell 允许的命令与受控脚本应能正常跑通 | 白名单命令 + 允许目录脚本执行返回 `SUCCESS` 与预期 stdout |
| `shell_rejectsDisallowedCommandAndInlineFlag` | shell 命中白名单外命令或禁止参数应被拦下 | 未授权命令、`-c` 内联参数与越界脚本路径均在启动进程前抛出 `OryxError` |

### 2.5 `HttpToolsTest`
路径：`oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/http/HttpToolsTest.java`

| 英文测试方法名 | `@DisplayName`（课件对照） | 守住的验收点 |
| --- | --- | --- |
| `httpGet_fetchesResponseFromAllowedTarget` | http_get 应能取回响应 | 白名单内本地回环目标 `http_get` 返回 `SUCCESS` 与非空响应体 |
| `httpGet_blocksDisallowedDomainWithoutNetworkIo` | http_get 命中白名单外域名应被拦下 | 白名单外域名在发请求前抛出 `OryxError(TARGET_DENIED)`，越界目标零请求；302 重定向到白名单外域名亦被拦截 |
| `httpPost_sendsJsonToAllowedTargetAndBlocksDisallowedDomain` | http_post 白名单内正常发送且白名单外域名被拦下 | 允许目标 POST 返回 `SUCCESS` 并收到 JSON body；白名单外目标前置拒绝零网络 IO |

### 2.6 `McpClientServiceTest`
路径：`oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/mcp/McpClientServiceTest.java`

| 英文测试方法名 | `@DisplayName`（课件对照） | 守住的验收点 |
| --- | --- | --- |
| `disconnectedMcpServer_doesNotBreakStartupOrOtherServers` | 某个 MCP server 失联，不能拖垮启动和其他工具 | `bad`（`init-fail`）与 `good`（`normal`）并存时初始化不抛异常，`good` 工具注册进 `ToolRegistry`，`bad` 工具不注册且状态标记 `MCP_INITIALIZATION_FAILED` |
| `loadsMcpServersYamlAndResolvesEnvSecrets` | 从 mcp_servers.yaml 加载 MCP 配置并解析环境变量引用 | 校验 `mcp_servers.yaml` 解析 `transport`/`command`/`args`/`env`（`${ENV_VAR}` 占位），拒绝不支持的 transport 与敏感 env 泄露 |

### 2.7 `McpToolAdapterTest`
路径：`oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/mcp/McpToolAdapterTest.java`

| 英文测试方法名 | `@DisplayName`（课件对照） | 守住的验收点 |
| --- | --- | --- |
| `adapter_mapsMetadataAndForwardsArgumentsToRemoteTool` | McpToolAdapter 映射 MCP 元数据并原样转发参数返回 ToolResult | 校验 `name`/`description`/`inputSchemaJson` 完整映射、`execute` 以远端工具名原样转发参数并返回 `ToolResult(SUCCESS)` |
| `adapter_rejectsUnauthorizedServerAndWrapsRemoteError` | McpToolAdapter 拒绝未授权 server 并将远端失败封装为不可重试 ToolResult | Agent 未声明 `mcp_servers` 时 `authorize` 抛 `OryxError(TARGET_DENIED)`；远端 `isError=true` 返回 `Outcome.ERROR` 且 `RetryClass.NONE` |
