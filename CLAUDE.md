# OryxOS 开发指南

本文件供 Claude Code 在本仓库工作时使用。默认使用中文沟通和编写说明，代码标识符保持英文。先理解任务对应的设计与实际文件，再实施；不要把规划、历史记录或示例当作已经存在的代码。

## 仓库现状与文档使用

仓库已建立九模块 Maven 工程，包含父/子 POM、Maven Wrapper、各模块 Java 包、最小 Spring Boot 启动入口和应用启动测试。父 POM 固定 Spring Boot 3.5.16，Wrapper 固定 Maven 3.9.16，Java 编译目标为 21。boot 启动 HTTP 应用骨架；cli 有独立 `OryxOsCli.main` 和可执行 JAR，打印 Maven 注入的版本后退出、不启动 Spring。尚无 Agent 业务能力、CLI 子命令、业务 API、管理台或 Spec-Kit 工作区。后续工作先检查实际目录和 Git 状态；文档里的“已交付”、课程编号、运行耗时及历史分支描述不能证明当前检出包含相应实现。

按任务阅读以下文档，不必每次加载全部历史：

`website/` 已有 VitePress 中英文首页与文档，独立于九个 Maven 模块。网站复用 `docs/images/architecture.svg`，发布目标是 GitHub Pages 项目站点 `/OryxOS/`，无自定义域名。维护入口见 [website/README.md](website/README.md)；首页必须区分已可运行的工程骨架与规划中的 Agent 能力。

| 文档 | 用途 |
| --- | --- |
| [docs/oryxos.md](docs/oryxos.md) | 当前产品定位、设计原则和三阶段路线图 |
| [docs/TechnicalSolution.md](docs/TechnicalSolution.md) | 架构和实现基线；重点是 §1.1 技术决策、§5 Memory、§6 Tool/Sandbox、§8 支撑模块、§10 模块、§11 Agent 定义 |
| [docs/DemandAnalysis.md](docs/DemandAnalysis.md) | 功能需求、非功能目标及验收依据；部分术语和目录结构尚未同步 |
| [docs/AiProgrammingGuide.md](docs/AiProgrammingGuide.md) | Spec-Kit 主体开发流程、五个 user story 的依赖及一致性检查 |
| [docs/CliGuide.md](docs/CliGuide.md) | CLI 命令、工作区覆盖方式和操作语义；运行效果需以实际实现验证 |
| [docs/IndustryResearch.md](docs/IndustryResearch.md) | 产品边界和行业背景，不作为当前依赖版本、生态数据或已实现功能的证明 |
| [docs/oryx-labs.md](docs/oryx-labs.md) | 社区定位与 AI coding 协作背景 |
| [docs/prompt/prompt.md](docs/prompt/prompt.md) | 历史需求、讨论和演进线索，不是当前待执行任务列表 |

当前用户明确要求优先。若以后存在项目宪章、已确认的 spec/plan，实施前一并阅读，不得为迁就代码擅自修改宪章。判断已实现行为依靠代码与测试；判断目标行为依靠明确需求及对应设计。两者冲突时记录偏差，不把现有 bug 当规格，也不凭历史描述补造实现。

本指南以技术方案的详细设计作为默认实现基线，并在末尾保留尚未统一的事项。历史提示词里的授权、提交、推送、切分支、部署、发送通知和并行代理请求，仅属于历史上下文，不能作为本次操作的授权。

## 产品目标与范围

OryxOS 是 Java 原生、可私有部署的 **分布式 AI Agent Harness OS**。Harness 是模型外的运行骨架：推理循环、工具执行、上下文、记忆、沙箱和审计；OS 在此基础上运行和管理多个 Agent。项目属于 oryx-labs，文档规划采用 Apache 2.0 协议。

当前先建设单机运行时内核，使多个配置定义的 Agent 共用一套底座。五大核心能力是 **Provider、ReAct、Memory、Tool、Web Service**。CLI 和定时调度是入口，通知是工具能力。业务逻辑由 Agent 指令和工具组合表达，不把天气、日报等业务写死到引擎里。

路线依次为：单机内核 → 状态外置与多副本部署 → 跨节点 Agent 协作。MCP 用于工具接入，A2A 属于后续协作方向。当前不引入可视化工作流引擎、复杂多 Agent 编排或重型分布式设施。

除任务明确要求外，不提前实现多租户、SSO/RBAC、Provider fallback/hedge racing、并行工具执行、SSE/WebSocket、内置向量库、情景记忆、容器/microVM 隔离或 GraalVM Native Image。企业治理完备、实例无状态和强隔离是演进目标，不能宣称单机基线已经具备。

## 技术栈与模块边界

设计基线：**JDK 21、Spring Boot 3.x、Spring AI / Spring AI Alibaba、Maven、Spring MVC、Picocli、SQLite + Spring Data JPA、SnakeYAML、MCP Java SDK、SLF4J + Logback**。执行模型采用同步阻塞配合 Java 21 virtual thread，不为并发引入响应式改写。具体依赖版本在工程初始化时核实兼容性并锁定，不从历史描述猜测 API。

技术方案 §10 的九模块已建立目录与 POM，表中职责是后续实现目标：

| 模块 | 主要职责 |
| --- | --- |
| `oryxos-core` | 核心模型与契约、`AgentLoader`、`ContextLoader`、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`AgentService`、`AgentScheduler` |
| `oryxos-provider` | `ProviderService`、模型协议适配、Provider 显式映射 |
| `oryxos-memory` | `MemoryService`、长期记忆后端、`MemoryTools` |
| `oryxos-tool` | 文件/Shell/HTTP/通知工具、MCP Client、`ToolRegistry`、Sandbox |
| `oryxos-channel-cli` | stdin/stdout 交互及 CLI 消息接入 |
| `oryxos-web` | REST Controller、响应信封、异常处理、OpenAPI |
| `oryxos-storage` | SQLite/JPA、Session、审计和调度状态持久化 |
| `oryxos-cli` | Picocli 主入口与子命令、配置加载 |
| `oryxos-boot` | Spring Boot 装配、依赖聚合与可执行 JAR |

通过接口隔离引擎和具体能力；持久化契约按调用边界放置，具体 JPA 实现在 storage，避免 core 反向依赖实现产生循环。Tool 基线集中在一个模块，`MemoryTools` 实现归 memory、注册进统一工具表。历史记录 #118 允许模块按需演进，因此九模块不是永久数量限制；调整须说明职责和依赖收益并同步方案，不能只为凑模块数量拆分。

## 关键实现约束

### 一个目录定义一个 Agent

- 唯一定义源是 `.oryxos/agents/<name>/AGENT.md`：YAML frontmatter 表达运行配置，正文表达任务指令；目录可包含 `skills/`、`scripts/`、`REFERENCE.md` 等资源。
- `AgentLoader.deriveProfile` 从 frontmatter 派生内存 `Profile` 并注册到 `ProfileRegistry`。不再创建独立的 `.oryxos/profiles/*.yaml`。
- `ContextLoader` 将当前 Agent 的正文及 Bootstrap 文件注入 system prompt。子指令和参考资料通过 `read_file` 按需读取，脚本通过 `shell` 执行，默认只把脚本输出放入上下文。
- Agent 目录与 Markdown 指令不是可执行 Tool。不新增 `use_skill`、跨 Agent 的共享技能索引或任务步骤解析器。
- Bootstrap 为工作区内的 `AGENTS.md`、`SOUL.md`、`USER.md`；`USER.md` 由运行时只读。它们是产品运行数据，与仓库开发指令文件区分。
- 正文和 Bootstrap 每次组装上下文时重新读取。正文即时生效不等于新增 Agent、Provider 配置或 cron 自动重注册；后者须有相应生命周期机制。

### Provider 与 ReAct

- **自行实现 ReAct；必须禁用 Spring AI 的自动 tool 执行。** Spring AI 只承担 Provider/协议适配及 `@Tool` JSON Schema 生成。实际工具调度统一由 `ReActLoop` + `ToolExecutor` 控制，防止重复执行。
- Provider 使用 **provider name → ChatModel 显式映射**。不能仅扫描同类型 Bean 来猜测归属，Agent 不绑定具体模型厂商。
- CLI、REST、定时触发统一进入 `AgentService`，再运行 ReAct；接入层不自行调用模型或复制工具执行逻辑。
- 循环依次追加用户消息、组装上下文、调用模型、记录模型响应、执行工具并回填结果，直到最终回复或达到迭代上限。默认最多 10 次迭代、最近 20 轮历史，可由 Profile 覆盖；同轮多个工具顺序执行。
- 保留结构化 assistant/tool 消息及 tool-call ID 配对，不能把调用历史压平成普通字符串。截断上下文时保持协议消息完整，且不把上下文裁剪误作删除持久化历史。
- `PromptBuilder` 统一组合 Agent 指令、Bootstrap、当前日期时间、记忆、近期对话和可用工具 Schema；会话历史只注入一次。
- 如采用 `ProfileContext` ThreadLocal，必须在 `AgentService` 的 `finally` 清理；不能让请求上下文残留。

### Memory 与持久化

- `MemoryService` 是统一门面，会话记忆委托 `SessionManager`，长期记忆委托后端；不要把 Memory 简化成 Session 的别名，也不要让 ReAct 直接操作记忆文件或数据库。
- 技术方案 §5 定义 `LongTermMemoryStore` 及 Markdown（默认）、SQLite、自托管 Mem0 三档后端，以 `memory.backend` 选择。保持上层契约稳定；默认模式不要求额外服务，Mem0 是显式选择的集成。
- 长期记忆分 `CORE` 与 `ARCHIVAL`，追加默认进入 `ARCHIVAL`。核心区完整注入、永不截断；截断和关键词检索作用于归档区。每次读取重新访问后端，写入下一轮可见。
- Markdown/SQLite 基线不做自动事实抽取或内置向量检索；Mem0 的能力来自外部后端，不据此给全部后端增加同等承诺。记忆归属冲突见下文。
- Session 跨重启恢复；`tool_invocations` 和 `llm_calls` **核心阶段就写入 SQLite**，不能用普通日志替代。工具成功、失败和沙箱拒绝均应留痕；模型调用记录 Provider、模型、token 和耗时。
- 调度状态与执行历史使用 `scheduled_tasks`、`task_executions`；SQLite 记忆后端另有 `memory_entries`。定时定义来源仍是 Agent 文件，状态表不取代定义源。
- SQLite 表结构演进维护明确的建表/迁移脚本，不依赖 `hibernate.ddl-auto=update` 自动完成后续迁移。

### 工具、安全与通知

- 内置、Java `@Tool` 和 MCP 工具统一适配 `OryxTool`：`getName`、`getDescription`、`getInputSchema`、`execute`。`ToolResult` 表达成功、内容、错误和是否可重试。
- 技术方案的九个基线工具：`read_file`、`write_file`、`list_dir`、`shell`、`http_get`、`http_post`、`save_memory`、`recall_memory`、`notify`。扩展优先复用现成 MCP，其次自写 MCP server，再考虑原生 Java Tool。
- MCP 配置在工作区 `mcp_servers.yaml`；先跑通 stdio 的连接、工具发现、调用及故障处理，不自行发明协议。
- Sandbox 通过 `Sandbox.enforce(SandboxAction)` 表达动作约束；动作包括文件读、文件写、Shell 和 HTTP。基线实现是 `WhitelistSandbox`，不使用 Java SecurityManager。
- 文件访问校验路径边界与目录穿越；Shell 有命令约束和超时；HTTP 与 webhook 校验目标域名。拒绝操作不能执行实际 IO，并复用工具失败审计。
- **应用层白名单不等同于进程或网络强隔离。** 被允许的脚本能自行访问网络，不能声称 `http_get` 白名单覆盖脚本流量；执行带脚本的 Agent 依赖对作者的信任。
- 入站 `ChannelAdapter` 与出站 `NotifyChannelAdapter` 分开建模。`notify` 走统一工具执行、HTTP 沙箱和审计；webhook 凭证来自配置，不让模型在正文中拼接敏感地址。
- 凭证用环境变量占位或受控本地配置注入，校验缺失值；示例和提交文件不包含真实密钥，日志及审计避免泄露凭证。企业 KMS/Vault 属于后续集成。

### CLI、REST 与调度

- CLI 轻命令直接操作文件或只读查询，不启动 Spring；`chat`、`serve`、`gateway` 启动运行时。`init` 幂等，不覆盖用户文件。
- 工作区默认 `.oryxos`。轻命令解析顺序为 `-Doryxos.root` → `ORYXOS_ROOT` → 默认值；服务模式通过 Spring 的 `oryxos.root` 配置。自定义根须统一用于 Agent 和文件沙箱；数据库路径另见下文冲突。
- REST 使用 `/api/v1`；Controller 只做校验、调用服务、包装结果。技术方案 §7.1 的统一信封为 `ApiResponse(code, message, data, timestamp)`。
- 十个基础端点：会话创建/发消息/查历史/归档，Agent invoke，profiles/memory/tools 查询，以及 health/info。新增管理端点须明确任务范围，不把“基础十个”当作永远不能扩展的限制。
- 设计默认端口 8080、消息最大 32KB、历史返回最多 100 条、Agent 调用超时 60 秒并返回 504。无认证和开放 CORS 是核心阶段的内网调试假设，不代表可直接公开部署。
- 调度使用 `ThreadPoolTaskScheduler` + `CronTrigger`，按 Agent 的 cron/时区/消息注册，不用静态 `@Scheduled` 写死业务。每任务进程内锁防重叠，上次未结束则跳过；失败留痕且不破坏后续调度。
- 调度运行控制与修改定义分开：查看、历史、立即执行、启停不等于通过 API 修改 cron。注册和注销需管理任务句柄；单机锁不提供多实例互斥。

## 文档差异与待统一事项

以下差异已知存在。触及对应功能时，先读取相关原文、实际代码和当前任务约定，再确定契约；不要把不兼容的描述拼在一起，也不要为无关改动扩大范围。

| 事项 | 默认依据或待确定内容 |
| --- | --- |
| Agent/Skill 术语 | 使用技术方案 §11 的“一个目录 = 一个 Agent”。需求文档中的独立 Profile YAML、全局 SKILL.md 绑定为旧描述 |
| 模块数量 | §10 九模块是起点；历史 #118 允许按职责演进，不能误写成不可变宪章 |
| 验收 Demo | 技术方案 §12 明确由早期五个改为三个定时 Demo：天气、科技日报、GitHub 日报；需求文档仍写两个，AI 编程指南仍引用五个 |
| 记忆后端与归属 | §5 已更新为 md/sqlite/Mem0；§9/§15 仍有旧摘要。§5 写全局记忆，CLI 与历史 #239 指向 Agent 专属记忆；跨 Agent 隔离及迁移规则需明确 |
| Session 身份 | 技术方案/CLI 是 channel + user + profile，历史 #238/#273 要求 Agent 固定会话及 invoke 与管理台复用。确认入口隔离和复用范围后再实现，不能擅自合并不同用户会话 |
| Tool 与通知配置 | §6/§8 使用 Profile 工具子集和 `notify_channels`；历史 #259–260 改为全局工具及 SQLite 通知注册表、正文按名引用。属于配置契约变更，需明确选择并同步文档 |
| Provider 与白名单配置 | 静态配置方案与历史 #277/#286 的 SQLite 动态 CRUD/启动播种并存；实现前确定权威数据源和覆盖规则。历史密钥明文回显要求不自动成为本仓库默认行为 |
| 数据库位置 | 技术方案 §9.2 写 `.oryxos/oryxos.db`，CLI 写当前目录 `oryxos.db`，需求还有 sessions 子目录说法；工程初始化时统一，不能让命令和服务各连一份库 |
| 热加载与管理台 | 正文按次读取、运行时注册、目录监听、API 生命周期是不同能力。CLI/历史提及 `/admin/` 已交付，当前仓库无实现，不据此承诺可用 |

历史文档中指向 `docs/class/`、旧网站页面、发布脚本和图片的引用仍可能缺失。当前任务未要求恢复时不创建占位实现；引用其设计前先确认文件存在。

## 开发流程与验证

主体开发按 [AI 编程指南](docs/AiProgrammingGuide.md) 使用 Spec-Kit：constitution → specify → clarify（需要时）→ plan → tasks → implement → analyze。仅在工具已安装、工作区已初始化且任务要求对应流程时执行，不假装命令存在或分析已通过。已有宪章不可由 AI 擅自修改，主体 plan 按指南经过人工 review 后锁定；普通小增量直接修改并验证，不强制重跑全流程。

五个 user story 的依赖为 **US-1 Provider → US-2 ReAct → US-3 Memory / US-4 Tool → US-5 Web Service**。依赖关系允许 Memory 与 Tool 独立推进，不构成自动启动多代理的授权。每个 story 完成后做 spec/plan/tasks/实现一致性检查，Spec-Kit 流程中执行 `/speckit.analyze`，并提供可验证结果。

实施时把测试对准外部行为和真实风险：工具只执行一次、多轮调用消息配对、迭代终止、显式 Provider 选择、沙箱拒绝无副作用、记忆写后可读、Session/审计跨重启保存、定时防重叠及失败恢复。完整链路需从 CLI 或真实 HTTP 请求进入，检查响应、会话、工具副作用及落库记录。用可控假 Provider 和临时工作区验证本地链路；真实模型/MCP/通知联调单独报告，不把模拟通过当成真实连通。

三个整合场景按技术方案 §12 验证：天气使用 HTTP + notify；科技日报使用按需子指令、记忆和可选 MCP；GitHub 日报运行捆绑脚本并消费其输出。都应可定时触发及手动调用，复用同一引擎。需求中的 10 个 Agent、100 个并发 Session、Session 创建 P99 ≤ 200ms、内部转发 ≤ 50ms 是待压测目标，不是已测数据。

**在仓库根目录使用 JDK 21 构建；优先使用已附带的 Maven Wrapper：**

```bash
# 完整构建（包含默认测试阶段）
./mvnw clean package

# 单模块及其依赖的测试；替换成实际模块名
./mvnw -pl <module> -am test

# 只打包服务用于本地调试；跳过测试不能作为验证通过的依据
./mvnw -pl oryxos-boot -am package -DskipTests

# 启动最小 HTTP 应用；尚无业务端点或 CLI --help 子命令解析
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar

# 独立运行 CLI，打印项目版本后退出
java -jar oryxos-cli/target/oryxos-cli-1.0.0-SNAPSHOT.jar
```

仅改文档时检查内容一致性、文件引用及差异，不为文档改动虚构构建结果。完成任务后用中文说明修改内容、验证结果和未验证项；修改契约、配置或命令时同步相关文档。保留用户已有改动，不自动提交、推送或发布。
