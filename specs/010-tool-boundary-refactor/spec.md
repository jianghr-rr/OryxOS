# Feature Specification: Tool 体系边界对齐与验收 harness（对齐第 20 节课件与技术方案）

**Feature Branch**: `010-lesson20-tool-boundary`（规格目录名 `010-tool-boundary-refactor`；分支从已合入 PR #12 的 `origin/main` `ff7c4ac` 切出）

**Created**: 2026-11-26

**Status**: Draft

**Input**: User description: "第20节需求：Tool 体系——为 Agent 提供统一、受控、可扩展的外部执行能力（“Agent 的手”）。背景与价值：模型本身只能生成文本，必须通过统一工具抽象屏蔽内置工具、本地扩展与外部 MCP 工具的来源差异，由运行时统一执行安全前置校验与审计，并通过三档接入方式兼顾零代码复用、轻代码跨语言扩展与进程内深度集成。沿用 PR #7（005）/ PR #8（006）/ 008 / 009 的边界对齐规则：保留已确认的宪章 v1.0.0 与 spec 001~009 生产契约与偏差记录，补齐 MCP server 启动连接失败的 WARN 告警日志，按课件交付物与验收 harness 落地 7 个课件命名测试类及关键回归守点，同步测试分组并交付第 20 节课件修订建议。"

## 背景与现状（规格输入）

本规格覆盖 OryxOS 的 **Tool 体系（统一工具抽象、9 个内置工具、Plugin Tool 三档接入、ToolRegistry 统一注册与 Profile 过滤、MCP 接入与隔离）**，是一次**保持既有外部行为不变**的边界对齐、可观测性补齐与验收 harness 补齐。
输入是仓库内 `docs/第20节：Tool 体系 原理解析、实现与代码讲解.md`（下称“课件”）、`docs/TechnicalSolution.md`（§6.1~6.6、§10）与 2026-11-26 当前检出的代码。课件是教学示意，凡与已确认宪章 v1.0.0、spec 001~009 冲突处，以后者为准（见“保留的偏差”）。

### 课件原则与当前代码对照

| 课件要求 | 当前实现 | 结论 |
| --- | --- | --- |
| 统一工具抽象 `OryxTool`（含名称、描述、参数 Schema、执行返回 `ToolResult`），屏蔽来源差异；特别检查是否存在参数 Schema 暴露 | `OryxTool` 通过 `descriptor()` 暴露 `name`/`description`/`inputSchemaJson`/`safeToRetry`/`sideEffecting`/`source`，并约定 `authorize` 与 `execute` 返回 `ToolResult`（含结果态、内容、错误码、可重试分类、副作用状态） | 已满足；**缺课件命名的参数化契约遍历测试 `OryxToolContractTest`** |
| 九个内置 Tool（文件 3 + Shell 1 + HTTP 2 + Memory 2 + Notify 1）均注册进入统一注册表，执行前先过白名单校验 | 全部 9 个内置工具已实现并经统一入口注册，执行前均先过路径/命令/域名/目标白名单授权校验，第 19 节 `notify` 已接入统一工具注册 | 已满足；**缺课件命名的 `FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`** |
| Plugin Tool 三档接入：方式一（零代码 Agent 目录 + 复用 MCP）、方式二（外部 MCP server）、方式三（Java `@Tool` 注解自动扫描注册且不触发框架自动执行） | 三档接入均已就位：方式一归上下文层；方式二由 MCP 客户端管理、发现与适配器完成；方式三由注解扫描与适配器生成离线 JSON Schema 并包装为统一工具进入同一执行链 | 已满足；**缺 `McpClientServiceTest` 与 `McpToolAdapterTest`** |
| `ToolRegistry` 汇总三种来源工具为统一工具对象，并按 Profile 的 `tools` 字段精确过滤可用子集（不多不少） | `ToolRegistry` 统一接纳 `BUILTIN` / `NATIVE` / `MCP` 三种来源，注册时校验离线 Schema 与重名/协议名冲突，`available(AgentProfile)` 按声明列表精确过滤 | 已满足；**缺课件命名的 `ToolRegistryTest` 钉死三来源统一注册与“不多不少”过滤** |
| MCP server 连接/初始化失败时只记 WARN 日志并跳过该 server，不拖垮 OryxOS 启动、不影响其他正常 server 工具注册 | MCP 管理器在单个 server 失败时已将其置为不可用、记录失败状态且不影响其他 server 与启动；**但异常分支仅写状态表，缺少显式 WARN 告警日志** | **需补齐：在 MCP 启动连接失败分支增加脱敏 WARN 日志，并新增 `McpClientServiceTest` 钉死失联隔离回归点** |
| 验收 harness：`OryxToolContractTest`、`ToolRegistryTest`、`FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`、`McpClientServiceTest`、`McpToolAdapterTest`（共 7 类） | 7 个课件命名测试类全部不存在；相关断言散落在 `ToolsContractTest`、`ToolSafetyContractTest`、`McpContractTest` 与 `ToolIntegrationTest` | **可定位性不足，需新增 7 个课件命名测试类，并将课件写出代码的两个最值钱回归测试原样落地** |

### 保留的偏差（不按课件改动）

| 课件写法 | 保留的已确认依据 |
| --- | --- |
| `OryxTool` 直接定义 `getName()`/`getDescription()`/`getInputSchema()`/`execute(JsonNode)`；`ToolResult` 为四字段模型 | 现行 `OryxTool`（`oryxos-core`）通过 `descriptor()` 提供完整 `ToolDescriptor`（含 `name`/`description`/`inputSchemaJson`/`safeToRetry`/`sideEffecting`/`serverId`/`remoteName`/`source`），并拆分两阶段 `authorize` + `execute`；`ToolResult` 区分 `SUCCESS`/`ERROR`/`DENIED`/`UNKNOWN`/`NOT_EXECUTED` 及 `SideEffectState` 以防止副作用重放（宪章 II / IV / spec 001）。不改动前序公共接口 |
| 内置工具以 `@Tool` 注解写在 `FileTools`/`ShellTools`/`HttpTools`/`NotifyTools` 四个聚合类中，由 Spring AI 扫描生成 schema | 宪章 II 禁止 Spring AI 自动工具执行；本仓库将 9 个内置工具拆为独立类实现 `OryxTool`（`ReadFileTool`/`WriteFileTool`/`ListDirTool`、`ShellTool`、`HttpGetTool`/`HttpPostTool`、`SaveMemoryTool`/`RecallMemoryTool`、`NotifyTool`），以手写严格离线 JSON Schema 和独立 `authorize` 保证安全边界；仅方式三（Java 扩展工具，如 `EchoTool`）使用 Spring AI `@Tool` 注解生成 schema，且经 `AnnotatedToolAdapter` 纳入统一 `ToolExecutor` |
| `ToolRegistry` 提供 `register(...)`/`contains(name)`，运行期可变注册 | 现行 `ToolRegistry` 实现 `ToolRegistryPort`，在装配启动期一次性汇总内置、原生扩展与 MCP 三种来源、完成离线 Schema 校验与重名/协议名冲突校验后冻结为不可变映射，对外提供 `find`/`all`/`available`/`validate`/`authorize`；避免运行期并发竞争与未校验工具绕过注册门禁 |
| MCP 管理类名为 `McpClientService`（`@PostConstruct connectAll()`），支持 stdio 或 SSE，调用失败默认返回可重试失败 | 现行实现为 `McpClientManager` + `McpToolDiscovery` + `McpToolAdapter`：通过 `mcp_servers.yaml`（`schema_version: 1`）配置显式 `stdio` 传输（SSE 延后至扩展阶段），使用严格环境变量清洗与有界管道；遵循宪章 II/IV“未知副作用绝不隐式重试”原则，已发出的 MCP 调用断连/超时返回 `UNKNOWN` 且不重试，显式 `isError` 返回 `ERROR` 且标记 `RetryClass.NONE` |
| 所有内置工具首行直接调用 `sandbox.enforce(new SandboxAction(...))` 并抛 `SandboxViolationException` | 统一 `Sandbox` 抽象属于第 24 节范畴；本节保留既有前置授权策略（`FileAccessPolicy`/`ProcessPolicy`/`HttpTargetPolicy`/`NotifyChannelAdapter.authorize`），越界在 IO 之前抛出受控 `OryxError`（`TARGET_DENIED`/`TOOL_DENIED`），第 24 节再统一收敛，不在本节提前发明第 24 节对外接口 |
| 方式一（零代码）使用 `.oryxos/skills/` 下的 `SKILL.md` 并在 Profile 中用 `skills` 引用 | 依据技术方案 §6.3 / §11.1 与宪章 III：本仓库采用“一个目录 = 一个 Agent”（`.oryxos/agents/<name>/AGENT.md` + 可选 `skills/*.md` 子指令），子指令由 Agent 按正文指引通过 `read_file` 按需读取，无跨 Agent 共享技能库 |
| 验收测试使用 Mockito mock `McpClient` 与静态外部域名示例 | `oryxos-tool` 不引入 Mockito 依赖；本地测试使用可控本地 HTTP 接收器、真实临时目录与受控本地 stdio MCP 夹具脚本，零公网依赖、零新增第三方依赖 |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 统一工具契约完整性与三来源注册过滤（Priority: P1）

平台维护者与 Agent 引擎需要确保所有已注册工具（内置工具、本地 Java 扩展工具、外部 MCP 工具）均以统一工具抽象注册到同一注册表，每个工具都完整提供名称、描述与合法的离线参数 Schema，且每个 Agent 仅能看到并调用其配置中明确声明的工具子集（不多不少）。

**Why this priority**: 统一抽象与完整参数 Schema 是模型协议翻译和工具调度的地基——任何一个工具缺失参数 Schema 都会导致模型调用失败；按 Profile 精确过滤工具子集是核心阶段最基础的权限边界。

**Independent Test**: 运行 `OryxToolContractTest`（参数化遍历注册表中全部工具）与 `ToolRegistryTest`，验证三来源工具统一注册、全部契约三件套非空且合法、缺失 Schema 被注册拒绝、按 Profile 过滤结果不多不少精确匹配。

**Acceptance Scenarios**:

1. **Given** 工具注册表已汇总内置工具、本地注解扩展工具与外部 MCP 工具，**When** 参数化契约测试遍历注册表中的每一个工具，**Then** 每个工具的名称、描述与参数 Schema 均非空且参数 Schema 为合法的离线 JSON Schema。
2. **Given** 某个待注册工具缺失参数 Schema 或提供非法 Schema，**When** 尝试注册到工具注册表，**Then** 注册立即失败并明确报错，阻止残缺工具进入运行时。
3. **Given** 注册表中同时包含来自内置、注解扩展与 MCP 三种来源的多个工具，**When** 按某个 Agent Profile 声明的 `tools` 列表请求可用工具子集，**Then** 返回的工具名集合与声明列表完全一致（不多一个、不少一个），未声明的工具不可见。
4. **Given** 两个工具声明了相同的工具名，**When** 尝试注册进同一注册表，**Then** 注册表明确拒绝重复名称，防止调用歧义。

---

### User Story 2 - 内置文件、Shell 与 HTTP 工具的正常执行与越界拦截（Priority: P1）

当 Agent 执行文件读写列目录、受控命令执行或 HTTP 请求时，合法范围内的操作必须正常返回预期结果，任何越出白名单范围的请求（路径穿越、控制文件、符号链接逃逸、未授权命令或非法参数、白名单外域名或非法重定向）必须在产生任何外部副作用之前被拦截。

**Why this priority**: 文件、Shell、HTTP 是最直接操作宿主与外部网络的基础能力，课件明确要求“功能对、且踩不出边界”作为每类工具最先固化的双保险测试。

**Independent Test**: 分别运行 `FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`，验证每类工具的“正常能跑通 + 越界会被拦且零副作用”两条主线。

**Acceptance Scenarios**:

1. **Given** 工作区配置了允许读写的数据目录，**When** 通过文件工具写入、读取并列出该目录内的文件，**Then** 均返回成功且内容与条目真实匹配。
2. **Given** 文件路径指向工作区外（如 `../` 路径穿越）、未授权目录、受保护控制文件或指向工作区外的符号链接，**When** 执行授权校验，**Then** 在发生任何文件读写前被拦截拒绝且外部文件保持不变。
3. **Given** Shell 策略配置了允许的命令与受控脚本目录，**When** 执行允许的命令与受控脚本，**Then** 正常返回标准输出；**When** 尝试执行未在白名单内的命令或禁止的内联执行参数，**Then** 在启动子进程前被明确拒绝。
4. **Given** HTTP 策略配置了允许访问的本地回环目标，**When** 对允许目标发起 `http_get` / `http_post` 请求，**Then** 成功返回响应状态与内容；**When** 对白名单之外的域名发起请求，**Then** 在发出任何网络请求前被拦截拒绝，外部接收器收到 0 次请求。

---

### User Story 3 - MCP 外部工具适配与失联 server 启动隔离（Priority: P1）

业务方通过 `.oryxos/mcp_servers.yaml` 接入一个或多个外部 MCP server 时，正常 server 暴露的工具必须被包装为统一工具注册进注册表并在调用时原样转发参数、把结果包装返回；当某个 MCP server 启动失联、初始化失败或配置异常时，系统必须记录脱敏 WARN 日志并仅跳过该 server，不得拖垮启动过程或影响其他正常 server 的工具注册。

**Why this priority**: 课件将“失联 MCP server 不拖垮启动和其他工具”列为本节第二项最值钱回归测试——外部依赖的可用性绝不能等同于 OryxOS 自身的可用性。

**Independent Test**: 运行 `McpClientServiceTest` 与 `McpToolAdapterTest`，验证失联 server 与正常 server 共存时启动不抛异常、记录脱敏 WARN、正常 server 工具照常注册而失联 server 工具不注册，以及适配器元数据映射、参数转发与错误结果封装。

**Acceptance Scenarios**:

1. **Given** 同时配置了一个会连接/初始化失败的 MCP server 与一个正常的 MCP server，**When** 初始化 MCP 客户端管理器并执行工具发现注册，**Then** 启动不抛出未捕获异常、输出不含敏感信息的 WARN 日志、正常 server 的工具成功注册进注册表，而失联 server 的工具不出现在注册表中且状态标记为不可用。
2. **Given** 一个正常运行的 MCP server 暴露了带名称、描述与参数 Schema 的工具，**When** 通过 MCP 工具适配器包装为统一工具并执行调用，**Then** 工具元数据完整映射，调用时以远端原始工具名原样转发输入参数并将返回内容封装为成功结果。
3. **Given** MCP 工具执行返回远端业务错误（`isError=true`）或调用所属 server 未被当前 Agent 授权，**When** 执行授权或调用，**Then** 未授权 server 在转发前被拒绝；远端错误封装为失败结果且不隐式重试。

---

### User Story 4 - 既有工具契约零回归与课件差异可追溯（Priority: P2）

维护者与学员在对齐第 20 节后，需确认全部既有工具、Provider、ReAct、CLI 与 Notify 测试保持绿色，且能在一份修订建议文档中清楚查到第 20 节课件写法与现行生产实现之间的每一处保留偏差、真实代码定位与 7 个验收测试类的映射关系。

**Why this priority**: 避免教学示意写法与生产级安全/副作用契约口径分裂，并为后续第 22 节（Memory）、第 24 节（Sandbox）与第 31 节（Demo）提供准确基线。

**Independent Test**: 运行 `us4-tools` 分组测试与全量构建门禁全部通过；逐条核对第 20 节修订建议文档与真实代码文件路径。

**Acceptance Scenarios**:

1. **Given** 既有工具契约测试、第 16~19 节全部回归测试与工程质量门禁，**When** 运行分组测试与全量构建验证，**Then** 全部测试通过、分组登记数与实测数一致、质量基线不增长。
2. **Given** 第 20 节课件修订建议文档，**When** 逐项对照“保留的偏差”表与课件验收 harness 表，**Then** 每处差异均给出课件原写法、现行实现、真实代码路径与设计依据，7 个测试类全部可追溯。

---

### Edge Cases

- MCP server 配置包含敏感环境变量（如 API Token）且初始化失败：WARN 日志只记录 server 标识与分类状态码，严禁把环境变量值或未脱敏异常堆栈输出到日志。
- Agent Profile 的 `tools` 声明列表为空：按 Profile 过滤返回空集合，不泄露任何已注册工具。
- Agent Profile 的 `tools` 声明了注册表中不存在的工具名：过滤结果不包含该不存在项（启动配置校验会隔离该 Agent，不拖垮其他健康 Agent）。
- `http_get` 访问允许目标但服务端返回指向白名单外域名的 302 重定向：重定向目标同样必须被白名单拦截，越界目标零请求。
- 文件工具传入空路径或含路径穿越的嵌套路径、Shell 工具传入合法命令但脚本不在允许目录：均在 IO 或进程启动前被拦截。
- MCP server 暴露的工具名在不同 server 间重名（如两个 server 均暴露 `echo`）：注册进统一注册表时通过 server 命名空间区分（`mcp.<server>.<remote>`），转发给远端时还原为原始工具名。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 保持统一工具抽象契约完整性：注册表中的每一个工具（覆盖内置工具、`@Tool` 原生扩展工具与 MCP 适配工具）均 MUST 提供非空且非空白的名称、描述与合法的离线 JSON 参数 Schema；缺失或非法 Schema MUST 在注册阶段被拒绝。
- **FR-002**: 工具注册表 MUST 支持将内置工具、本地注解扩展工具与 MCP 适配工具统一注册为同一种工具抽象，拒绝重复工具名，并按 Agent Profile 声明的 `tools` 列表精确过滤出不多不少的可用工具子集。
- **FR-003**: 内置文件工具（`read_file`、`write_file`、`list_dir`）、Shell 工具（`shell`）与 HTTP 工具（`http_get`、`http_post`）MUST 全部具备“合法范围内正常执行 + 越界访问在 IO/进程启动前被白名单拦截且零副作用”的行为保证，且第 19 节 `notify` 工具已注册在统一工具注册表中。
- **FR-004**: MCP 客户端初始化阶段遇到单个 server 配置无效、进程启动失败、初始化超时或连接/发现失败时，MUST 记录脱敏 WARN 日志（仅含 server 标识与失败分类，不含敏感环境变量或密钥）、将其状态标为不可用并跳过，不得抛出未捕获异常阻断启动或影响其他正常 MCP server 的工具注册。
- **FR-005**: MCP 工具适配器 MUST 将远端 MCP `tools/list` 返回的元数据映射为统一工具描述，在授权阶段校验当前 Agent 已显式声明该 MCP server，在执行阶段使用远端原始工具名原样转发参数并将返回内容或错误封装为统一工具结果，且对不确定的失败不隐式重试。
- **FR-006**: 系统 MUST 补齐课件验收 harness 指定的 7 个命名测试类——`OryxToolContractTest`、`ToolRegistryTest`、`FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`、`McpClientServiceTest`、`McpToolAdapterTest`；其中参数化遍历契约三件套检查与 MCP 失联隔离检查 MUST 原样落地，全部测试方法名使用英文并通过 `@DisplayName` 保留课件原文语义。
- **FR-007**: 新增的 7 个测试类 MUST 全部纳入既有的 `us4-tools` 测试分组清单并同步更新相关验证文档，确保测试报告门禁核对用例数零偏差。
- **FR-008**: 本次变更 MUST 不修改前序第 16~19 节的公共接口与数据库表结构，不新增第三方依赖，不破坏既有测试与质量基线（质量基线只减不增），并 MUST 交付第 20 节课件修订建议文档（`lesson-20-revision.md`）记录全部保留偏差与测试映射。

### 明确不做（边界）

- 不实现 Tool Policy（Agent 级细粒度 allow/deny 规则引擎），核心阶段保持“白名单 + Profile `tools` 字段限定”。
- 不实现工具数量庞大时的按需延迟加载机制。
- 不将 OryxOS 自身作为 MCP server 对外暴露。
- 不实现容器级或 microVM 级强隔离沙箱（统一 `Sandbox` 接口与 `WhitelistSandbox` 实现归第 23/24 节，强隔离归扩展阶段）。
- 不实现单次模型响应内多个工具调用的并行执行（保持顺序执行）。
- 不新增 SSE MCP 传输实现（保持既有受控 stdio 传输，SSE 留待扩展阶段）。
- 不新增或升级任何第三方依赖，不引入 Spring AI 自动工具执行路径。

### Key Entities *(include if feature involves data)*

- **统一工具（OryxTool）**: 屏蔽来源差异的统一工具抽象，承载描述元数据（名称、描述、离线 JSON Schema、来源、重试与副作用属性）、前置授权校验与同步执行方法。
- **工具结果（ToolResult）**: 工具执行的统一返回载体，记录结果状态（成功/错误/拒绝/未知/未执行）、内容、错误码、脱敏提示、重试分类与副作用状态。
- **工具注册表（ToolRegistry）**: 汇总内置工具、`@Tool` 原生扩展工具与 MCP 工具的统一索引，负责离线 Schema 注册校验、重名与协议名冲突校验、以及按 Agent Profile `tools` 列表精确过滤。
- **MCP 服务器配置（mcp_servers.yaml）**: 工作区级外部 MCP server 声明文件，定义传输类型、启动命令、参数、密钥引用形式的环境变量与超时阈值。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 课件验收 harness 指定的 7 个命名测试类（`OryxToolContractTest`、`ToolRegistryTest`、`FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`、`McpClientServiceTest`、`McpToolAdapterTest`）全部存在于 `oryxos-tool` 且 100% 通过。
- **SC-002**: 参数化契约测试覆盖注册表中全部三来源工具（内置、原生扩展、MCP），每一项的名称、描述与离线参数 Schema 检查 100% 通过；缺失 Schema 在注册时 100% 被拦截。
- **SC-003**: 失联 MCP server 与正常 MCP server 共存场景下，初始化 0 未捕获异常、输出脱敏 WARN 日志、正常 server 工具 100% 注册、失联 server 工具注册数为 0。
- **SC-004**: `us4-tools` 分组门禁、前序第 16~19 节全部回归测试与全量工程构建门禁（构建、单测、格式、静态分析、安全检查、质量基线只减不增）100% 通过，零新增依赖、零新增基线告警。
- **SC-005**: 第 20 节课件修订建议文档完整记录全部保留偏差、真实代码定位、7 个验收测试类映射与剩余人工验收项（方式一真实模型+真实 MCP 跑通、方式三真实 `tool list` 与对话调通、`tool_invocations` 实链目检）。

## Assumptions

- 前序第 16、17、18、19 节交付的 Provider、ReAct、ToolExecutor、ProfileContext、CLI、会话层与 Notify 抽象均已在代码库就位且保持契约稳定。
- 统一 `Sandbox` 抽象与 `WhitelistSandbox` 实现将在第 24 节落地；本节通过既有工具前置白名单授权策略守住“涉外 IO 前必过白名单校验”不变量，不提前发明第 24 节对外接口。
- 真实模型配合真实外部 MCP server 的端到端验证及方式三真跑链路属于人工冒烟确认项，不纳入离线自动化单测。
