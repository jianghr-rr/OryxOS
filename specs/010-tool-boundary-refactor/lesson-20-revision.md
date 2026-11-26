# 第 20 节课件修订建议（Tool 体系边界对齐）

**对应规格**：`specs/010-tool-boundary-refactor/spec.md`
**对齐基线**：宪章 v1.0.0、spec 001~009、`docs/TechnicalSolution.md` §6.1~6.6 / §10

---

## 1. 保留偏差与代码对照表

| 编号 | 课件原写法 | 现行生产实现 | 代码位置 | 保留依据 |
| --- | --- | --- | --- | --- |
| D1 | `OryxTool` 直接声明 `getName()` / `getDescription()` / `getInputSchema()` / `execute(JsonNode)`；`ToolResult` 为四字段模型（成功/内容/错误/是否可重试） | `OryxTool` 暴露 `ToolDescriptor descriptor()`（含 `name`/`description`/`inputSchemaJson`/`safeToRetry`/`sideEffecting`/`serverId`/`remoteName`/`source`）、前置 `authorize(Map, ExecutionContext)` 与 `execute(Map, ExecutionContext)`；`ToolResult` 区分 `SUCCESS`/`ERROR`/`DENIED`/`UNKNOWN`/`NOT_EXECUTED` 及 `RetryClass`、`SideEffectState` | `oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/tool/OryxTool.java`、`ToolDescriptor.java`、`ToolResult.java` | 宪章 II/IV 要求两阶段授权与副作用诚实表达（防止不确定副作用重放）；不改动前序公共接口 |
| D2 | 内置工具以 Spring AI `@Tool` 注解聚合在 `FileTools` / `ShellTools` / `HttpTools` / `NotifyTools` 四类中由框架扫描生成 schema | 9 个内置工具各自独立实现 `OryxTool`（`ReadFileTool`、`WriteFileTool`、`ListDirTool`、`ShellTool`、`HttpGetTool`、`HttpPostTool`、`NotifyTool`、`SaveMemoryTool`、`RecallMemoryTool`），显式声明严格离线 JSON Schema 与独立 `authorize`；仅方式三 Java 扩展（如 `EchoTool`）使用 Spring AI `@Tool` 注解经 `NativeToolRegistrar` + `AnnotatedToolAdapter` 生成 schema 并走统一 `ToolExecutor` | `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/{file,shell,http,notify,nativeext}/`、`oryxos-memory/src/main/java/io/github/oryxlabs/oryxos/memory/tool/`、`oryxos-boot/src/main/java/io/github/oryxlabs/oryxos/boot/config/ToolConfiguration.java` | 宪章 II 禁止 Spring AI 自动工具执行；拆分独立工具类便于精细化 `safeToRetry`/`sideEffecting` 分类与离线严格校验 |
| D3 | `ToolRegistry` 提供可变 `register(OryxTool)` 与 `contains(name)` | `ToolRegistry` 实现 `ToolRegistryPort`，在启动装配期一次性接收全部三来源工具（`BUILTIN`/`NATIVE`/`MCP`），立即完成离线 Schema 注册校验、重名与 `ToolNameMap` 协议名冲突检测后冻结为不可变映射；对外暴露 `find`、`all`、`available(AgentProfile)`、`validate`、`authorize` | `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/ToolRegistry.java`、`oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/tool/ToolRegistryPort.java` | 不可变注册表消除运行期并发竞争，确保任何未通过离线 Schema 校验或命名冲突的工具无法进入运行时 |
| D4 | MCP 管理类名为 `McpClientService`（`@PostConstruct connectAll()`），支持 stdio 或 SSE，调用失败返回可重试失败 | 现行实现为 `McpClientManager` + `McpToolDiscovery` + `McpToolAdapter`；配置遵循 `.oryxos/mcp_servers.yaml`（`schema_version: 1`），核心阶段仅开放严格环境变量清洗与有界管道的 `stdio` 传输（SSE 留扩展阶段）；单 server 失败记脱敏 `LOG.warn` 跳过且不拖垮启动；已发出的 MCP 调用断连/超时返回 `UNKNOWN` 且 `RetryClass.NONE`，远端 `isError=true` 返回 `ERROR` 且不重试 | `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/mcp/McpClientManager.java`、`McpToolDiscovery.java`、`McpToolAdapter.java`、`SanitizedStdioTransport.java` | 宪章 II/IV 禁止不确定副作用隐式重试；stdio 环境隔离与有界管道是生产级安全底线 |
| D5 | 工具方法首行直接调用 `sandbox.enforce(new SandboxAction(...))` 并抛 `SandboxViolationException` | 统一 `Sandbox` 接口与 `WhitelistSandbox` 实现归第 23/24 节；本节保留既有前置白名单策略（`FileAccessPolicy`、`ProcessPolicy`、`HttpTargetPolicy`、`NotifyChannelAdapter.authorize`），越界在 IO 前抛出 `OryxError`（`TARGET_DENIED`/`TOOL_DENIED`），第 24 节再统一收敛 | `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/file/FileAccessPolicy.java`、`shell/ProcessPolicy.java`、`http/HttpTargetPolicy.java`、`policy/ToolPolicy.java` | 不提前发明第 24 节对外接口，同时通过前置 `authorize` 严格守住“涉外 IO 前必过白名单”不变量 |
| D6 | 方式一（零代码）在 `.oryxos/skills/` 写 `SKILL.md` 并在 Profile 的 `skills` 字段引用 | 依据技术方案 §6.3 / §11.1 与宪章 III：采用“一个目录 = 一个 Agent”（`.oryxos/agents/<name>/AGENT.md` + 可选 `skills/*.md` 子指令），由 `ContextLoader` 注入正文、子指令由 Agent 按指引经 `read_file` 按需读取，不做跨 Agent 共享技能索引 | `oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/context/ContextLoader.java`、`oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/agent/AgentDefinitionReader.java` | 与已确认技术方案 §11.1 及第 17 节 `ContextLoader` 口径一致 |
| D7 | 验收测试使用 Mockito mock `McpClient` 与静态外部域名样例 | `oryxos-tool` 不引入 Mockito；所有验收测试使用真实临时目录、JDK `HttpServer` 本地回环服务与受控 stdio MCP 夹具（`oryxos-tool/src/test/resources/mcp/fixture-server.py`），离线、确定、零新增依赖 | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/` 下 7 个新测试类 | 遵循“不新增 plan 未列明第三方依赖”与宪章 VI 可重复离线验证约束 |

---

## 2. 验收 Harness 映射表

全部纳入 `tools/dev/test-groups.json` 的 `us4-tools` 分组（29 → 53 项）：

| 课件测试类 | 仓库路径 | 用例数 | 覆盖回归点 |
| --- | --- | --- | --- |
| `OryxToolContractTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/OryxToolContractTest.java` | 10（9 参数化 + 1 拦截） | `@ParameterizedTest @MethodSource("allRegisteredTools")` 遍历全部三来源注册工具验证 `name`/`description`/`inputSchemaJson` 非空合法；缺失/非法 Schema 注册拒绝 |
| `ToolRegistryTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/ToolRegistryTest.java` | 3 | `BUILTIN`/`NATIVE`/`MCP` 三来源统一注册；按 Profile `tools` 过滤不多不少精确匹配（含空列表与未知工具名）；重复工具名拒绝 |
| `FileToolsTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/file/FileToolsTest.java` | 2 | `read_file`/`write_file`/`list_dir` 允许目录正常跑通；`../`、未授权目录、控制文件与越界符号链接前置拦截零副作用 |
| `ShellToolsTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/shell/ShellToolsTest.java` | 2 | 白名单命令 + 允许脚本目录正常执行；未授权命令、`-c` 参数与越界脚本路径前置拒绝 |
| `HttpToolsTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/http/HttpToolsTest.java` | 3 | `http_get` 白名单内取回响应；白名单外域名与 302 越界重定向拦截零网络 IO；`http_post` 正常发送与越界拦截 |
| `McpClientServiceTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/mcp/McpClientServiceTest.java` | 2 | 失联 MCP server 不拖垮启动、记脱敏 WARN、好 server 工具照常注册、坏 server 工具不注册；`mcp_servers.yaml` 加载、`${ENV_VAR}` 解析脱敏与非法 transport 拒绝 |
| `McpToolAdapterTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/mcp/McpToolAdapterTest.java` | 2 | 元数据（`name`/`description`/`inputSchemaJson`）映射与远端原始工具名参数转发返回 `ToolResult`；未授权 server 拒绝与远端 `isError` 封装为不可重试 `ERROR` |
| 既有 `ToolsContractTest` / `McpContractTest` / `NotifyContractTest` / `WebhookNotifyAdapterTest` / `NotifyToolsTest` / `ToolIntegrationTest` | `oryxos-tool` & `oryxos-boot` | 29（零改动） | 保持全部既有安全/副作用/跨工具合流回归全绿 |
