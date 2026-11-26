# Final Verification Evidence: 第 20 节 Tool 体系边界对齐

- **Date**: 2026-11-26
- **Branch**: `010-lesson20-tool-boundary`（自已合入 PR #12 的 `origin/main` `ff7c4ac` 切出）
- **JDK**: OpenJDK 21.0.13 (`/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home`)

---

## 1. 全量构建与静态门禁证据（DoD #1）

### 1.1 `us4-tools` 分组门禁
命令：
```bash
ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home \
  ./tools/dev/mvnw -B -o test -Dgroups=us4-tools
```
结果：
- 10/10 模块 `BUILD SUCCESS`（18.370 s）
- `Test evidence PASS: 53 required cases; run 211d11ab-2031-4070-a35b-a7073e960a51`

### 1.2 全量 `clean verify`（含 Spotless / Checkstyle-P3C / PMD / SpotBugs-FindSecBugs / 全量回归）
命令：
```bash
ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home \
  ./tools/dev/mvnw -B -o clean verify
```
结果：
- 10/10 模块 `BUILD SUCCESS`（10:58 min）
- `You have 0 Checkstyle violations.`（全模块）
- 各模块实测用例数：`oryxos-core` 61/61、`oryxos-provider` 20/20、`oryxos-storage` 30/30、`oryxos-memory` 13/13、`oryxos-tool` 54/54、`oryxos-cli` 10/10、`oryxos-boot` 123/123（合计 311 项唯一用例，0 失败、0 错误、0 跳过）
- `Test evidence PASS: 397 required cases; run 09688b6d-a8ed-48a8-8a53-788f6e65b09c`
- `Quality gate PASS: 444 baseline entries, 4003 report-only findings`

### 1.3 质量基线只减不增
命令：
```bash
./tools/dev/quality-baseline --check-shrink origin/main
```
结果：`Baseline did not grow against origin/main: 444 entries`

---

## 2. 课件 Harness 映射核对（DoD #2）

| 测试类 | 仓库路径 | 用例数 | 关键回归点 |
| --- | --- | --- | --- |
| `OryxToolContractTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/OryxToolContractTest.java` | 10 | `@ParameterizedTest @MethodSource("allRegisteredTools")` 遍历全部三来源注册工具（9 个工具）断言 `name`/`description`/`inputSchemaJson` 非空合法；缺失/非法 Schema 注册即拒 |
| `ToolRegistryTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/ToolRegistryTest.java` | 3 | `BUILTIN`/`NATIVE`/`MCP` 三来源统一注册；按 `AgentProfile.tools` 过滤不多不少精确匹配（含空列表与未知工具名）；重复名注册拒绝 |
| `FileToolsTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/file/FileToolsTest.java` | 2 | `read_file`/`write_file`/`list_dir` 白名单目录正常跑通；`../`、未授权目录、控制文件、越界符号链接前置拒绝且外部文件零改动 |
| `ShellToolsTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/shell/ShellToolsTest.java` | 2 | 白名单命令 + 允许脚本正常执行；未授权命令、`-c` 参数、越界脚本前置拒绝 |
| `HttpToolsTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/http/HttpToolsTest.java` | 3 | `http_get` 白名单内正常响应；白名单外域名与 302 越界重定向拦截零网络 IO；`http_post` 正常发送与越界拦截 |
| `McpClientServiceTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/mcp/McpClientServiceTest.java` | 2 | 失联 MCP server 不拖垮启动、记脱敏 WARN、好 server 工具照常注册、坏 server 工具不注册；`mcp_servers.yaml` 加载与 `${ENV_VAR}` 脱敏 |
| `McpToolAdapterTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/mcp/McpToolAdapterTest.java` | 2 | MCP 元数据映射与远端原始工具名转发返回 `ToolResult`；未授权 server 拒绝与远端 `isError` 封装为不可重试 `ERROR` |

---

## 3. 本节交付物存在性核对（DoD #3）

- 代码：
  - `oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/tool/OryxTool.java`（含 `ToolDescriptor.inputSchemaJson()`，存在）
  - `oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/tool/ToolResult.java`（存在）
  - `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/ToolRegistry.java`（存在）
  - `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/file/{ReadFileTool,WriteFileTool,ListDirTool}.java`（3 个，存在）
  - `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/shell/ShellTool.java`（存在）
  - `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/http/{HttpGetTool,HttpPostTool}.java`（2 个，存在）
  - `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyTool.java`（已于 `ToolConfiguration` 完成统一注册，存在）
  - `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/mcp/{McpClientManager,McpToolDiscovery,McpToolAdapter}.java`（存在，`McpClientManager` 已补齐启动失败脱敏 `LOG.warn`）
  - `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/nativeext/{NativeToolRegistrar,AnnotatedToolAdapter,EchoTool}.java`（方式三 `@Tool` 扩展，存在）
- 测试（7 类均存在且非空）：`OryxToolContractTest`、`ToolRegistryTest`、`FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`、`McpClientServiceTest`、`McpToolAdapterTest`
- 配置：`mcp_servers.yaml` 解析（`McpClientManager.definitions`）与 Profile `tools` 过滤（`ToolRegistry.available`）均已核验通过

---

## 4. 前序节全部测试回归（DoD #4）

第 16 节（Provider，`oryxos-provider` 20/20）、第 17 节（ReAct/ToolExecutor/AgentService/PromptBuilder/ContextLoader，`oryxos-core` 61/61）、第 18 节（CLI/SessionManager/SessionRepository，`oryxos-cli` 10/10 + `oryxos-storage` 30/30）、第 19 节（Notify，`WebhookNotifyAdapterTest` 4/4 + `NotifyToolsTest` 4/4 + `NotifyContractTest` 4/4）及 `oryxos-memory`（13/13）、`oryxos-boot`（123/123）全部回归通过，0 失败、0 错误、0 跳过。

---

## 5. H4 六条全局不变量自查（DoD #5）

1. **涉外 IO 首行过白名单/Sandbox 校验**：通过。文件（`FileAccessPolicy`）、Shell（`ProcessPolicy`）、HTTP（`ControlledHttpClient`/`HttpTargetPolicy`）、Notify（`NotifyChannelAdapter.authorize`）、MCP（`McpToolAdapter.authorize` + `SanitizedStdioTransport`）均在 IO 前执行授权校验；统一 `Sandbox` 接口留待第 24 节收敛。
2. **LLM/工具调用成败均落审计**：通过。所有内置、`@Tool` 扩展与 MCP 工具均由 `ToolExecutor` 统一调度写入 `tool_invocations`（`ToolIntegrationTest`、`ToolsContractTest`、`NotifyToolsTest` 覆盖）。
3. **无明文密钥**：通过。`grep -rnE "sk-|Bearer |secret-key|api_key=|token=" oryxos-tool/src/main/` 返回 `SECRETS_CLEAN`；`McpClientManager` 新增的 WARN 日志仅输出经 `sanitizeServerId` 清洗的 server 标识与固定错误码，不输出环境变量或异常原文。
4. **`session_id` 只在 `SessionManager` 内生成**：通过。本节未触碰 `oryxos-core` 会话层。
5. **无 Reactor/`CompletableFuture`/自建线程池**：通过。本次改动未引入任何 Reactor、`CompletableFuture` 或新线程池；`grep -rnE "CompletableFuture|new Thread" oryxos-tool/src/main/` 返回 `THREADS_CLEAN`（既有 MCP Java SDK `StdioClientTransport` 覆写签名的 `Mono` 为 SDK 接口固有，本次未改动）。
6. **无 Spring AI 自动工具执行路径**：通过。`grep -rnE "toolCallbacks|internalToolExecutionEnabled" oryxos-tool/src/main/ oryxos-provider/src/main/` 返回 `NO_AUTO_EXEC_CLEAN`；`@Tool` 仅在 `AnnotatedToolAdapter` 经 `ToolDefinitions.from(method)` 生成离线 Schema。

---

## 6. 剩余人工验收项（DoD #6）

1. **方式一真跑一次**：在独立工作区编写 Agent 目录（`AGENT.md` + 可选 `skills/*.md`）并配置真实外部 MCP server（`.oryxos/mcp_servers.yaml`），使用真实 LLM 跑通一次对话，确认 Agent 读懂意图并调用外部 MCP 工具完成任务。
2. **方式三真跑一次**：验证 `@Tool` 原生扩展示例（`echo`）在 `oryxos tool list` 中可见，且真模型对话链路可调通。
3. **`tool_invocations` 实链目检**：在真跑链路后检查 SQLite `tool_invocations` 表，确认每次内置/扩展/MCP 工具调用均有审计记录且无敏感明文。
