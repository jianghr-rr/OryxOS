# Feature Specification: CLI 与会话层边界对齐（对齐第 18 节课件与技术方案）

**Feature Branch**: `008-lesson18-cli-boundary`（规格目录名 `008-cli-boundary-refactor`；分支从已合入 PR #10 的 `origin/main` `64c9b1e` 切出）

**Created**: 2026-11-24

**Status**: Draft

**Input**: User description: "/oryxos-lesson-dev docs/第18节：CLI 功能概述、实现思路与代码讲解.md —— 第 18 节的 CLI（12 命令、轻重分流、`CliChannel`）与会话层（`SessionManager`、`sessions` 持久化）已在 spec 001~007 实现；沿用 PR #7（005）/ PR #8（006）的边界对齐规则：保留已确认的 spec 001~007 生产契约与偏差记录，补齐课件验收 harness 命名的 `SessionManagerTest` 与 `SessionRepositoryTest` 及缺失的回归守点，并交付第 18 节课件修订建议。"

## 背景与现状（规格输入）

本规格覆盖 OryxOS 的 **CLI 接入入口与会话持久化层**，是一次**不改变外部可观察行为**的边界对齐与验收 harness 补齐。
输入是仓库内 `docs/第18节：CLI 功能概述、实现思路与代码讲解.md`（下称“课件”）、`docs/TechnicalSolution.md`（§8.4、§8.6、§8.7、§9.2、§10）与 2026-11-24 当前检出的代码。课件是教学示意，凡与已确认宪章 v1.0.0、spec 001 / plan 001 / spec 005 / spec 006 冲突处，以后者为准（见“保留的偏差”）。

### 课件原则与当前代码对照

| 课件要求 | 当前实现 | 结论 |
| --- | --- | --- |
| CLI 只做薄入口，不碰模型与工具执行 | `OryxCommandLine` 仅解析参数与分发；`chat` 通过 `CliChannelAdapter` 委托 `AgentService` | 已满足 |
| 12 个子命令（`init`/`status`/`chat`/`serve`/`gateway`/`profile list\|create\|show\|delete`/`provider list`/`tool list`/`session list`）+ `--help` | 全部 12 个子命令已在 `oryxos-cli` 注册，并由 `CliOperationsTest` 覆盖 `--help` 与可发现性 | 已满足 |
| 命令按轻重分流：轻命令不启动 Spring 秒回，重命令（`chat`/`serve`/`gateway`）启动运行时 | `OryxCommandLine` 先于 Spring 上下文解析并直接执行轻命令；重命令经 `RuntimeLauncher` 启动 Spring；`EntryPointContractTest` / `CliRuntimeContractTest` 已验证 | 已满足 |
| 重命令启动 Spring 时显式声明 JPA/Entity 扫描范围，避免 0 repository 报错 | `OryxOsApplication` 显式排除 Spring Data JPA 自动配置，由 `StorageResourceFactory` + 手工 SQL 迁移（`V001`~`V007`）+ `EntityManager` 直接装配 SQLite | 已满足（以更可控的显式装配替代自动扫描，见保留偏差） |
| `chat` 读 stdin、写 stdout、维护当前会话、`/quit` 退出 | `CliChannelAdapter` 实现 stdin/stdout 循环、`/quit` 退出、同会话多轮续聊与脱敏输出 | 已满足（类名差异见保留偏差） |
| 会话层：`SessionManager` + `sessions` 表（手工建表脚本，不依赖 `ddl-auto=update`） | `SessionManager`（`oryxos-core`）+ `SessionEntity` / `JpaSessionStore` / `V003__session_execution.sql`（`oryxos-storage`）已实现 | 已满足（会话 ID 与消息存表形态见保留偏差） |
| 验收 harness：`SessionManagerTest`、`SessionRepositoryTest` | 两个测试类均不存在；相关断言散在 `SessionContractTest`、`SessionRuntimeContractTest`、`PersistentStorageContractTest`、`SessionReadContractTest` | **可定位性不足，需新增两个课件命名测试类并补齐直接对照断言** |

### 保留的偏差（不按课件改动）

| 课件写法 | 保留的已确认依据 |
| --- | --- |
| `OryxOsCli` 为整个程序 `main` 入口，直接挂 12 个子命令 | `oryxos-cli` 的 `OryxOsCli` 仅作薄 JAR `--version` 入口；完整 Picocli 根命令是 `OryxCommandLine`，可执行 fat JAR 主入口是 `oryxos-boot` 的 `OryxOsApplication`（spec 001 / 宪章 VII） |
| 重命令在启动类声明 `@EnableJpaRepositories` / `@EntityScan` | `OryxOsApplication` 显式排除 `DataSourceAutoConfiguration` / `HibernateJpaAutoConfiguration` / `JpaRepositoriesAutoConfiguration`，由 `StorageResourceFactory` 绑定工作区锁、手工迁移 `V001`~`V007` 并直接创建 `EntityManagerFactory`（与 spec 005/006 无 Spring Data Repository 接口同口径） |
| `CliChannel` 类名 | 现名为 `CliChannelAdapter`（位于 `oryxos-channel-cli`），已被装配层与集成测试引用，不改名破坏契约 |
| `SessionManager.getOrCreate(channel, user, profileName)` 按三元组确定性拼接唯一 `session_id`（幂等），对外 `getOrCreate`/`get`/`save` | 宪章与 spec 001 已确认：会话身份为 `(channel, user, Agent)`，同一组合允许多会话，ID 由 `SessionManager` 内部统一生成随机 UUID（`Uuids.v4()`）；对外提供 `acquire` / `acquireForExecution` / `lookup` / `archive`；同会话续聊传入会话 ID 并严格校验身份三元组（任一不同即报 `SESSION_IDENTITY_MISMATCH`）与创建时模型快照 |
| `sessions` 表含 `messages_json` 单列保存整个对话历史、`last_active_at` 列，实体对应 `SessionRepository` 接口 | `V003__session_execution.sql` 将会话元数据（`sessions`）与结构化消息（`messages`，每条含 `content_json`、`provider_opaque_json`、顺序号、执行/轮次外键）分表持久化，避免整列重写与损坏；持久化契约是 `SessionStore`（core）与 `JpaSessionStore`（storage），不引入 Spring Data `SessionRepository` |
| `chat` 参数默认 `--profile default`，无 `--user` 参数 | `ChatCommand` 要求显式传入 `--profile` 与 `--user`，可选 `--session` 续聊，防止默认身份混用（spec 001 US5 CLI 契约） |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 会话层验收 harness 可按课件类名定位并钉死核心回归点 (Priority: P1)

学员或维护者按第 18 节课件“验收 harness”表查找 `SessionManagerTest` 与 `SessionRepositoryTest`，能在对应模块直接定位到这两个测试类，并验证会话身份隔离、同会话多轮复用、唯一 ID 生成点、手工建表脚本读写、结构化消息序列化回读完整以及跨重启（重建存储上下文重查）历史保留。

**Why this priority**: 课件指出会话层是后续 CLI / HTTP / 定时任务所有入口共用的地基，出口径或持久化回归最难排查；当前测试散落在四个契约测试类中，无法按课件 harness 表一键对号。

**Independent Test**: 分别运行 `SessionManagerTest` 和 `SessionRepositoryTest`，确认课件表列出的每一条回归守点都有对应测试且全部通过；故意破坏身份校验或消息持久化时，对应测试必然失败。

**Acceptance Scenarios**:

1. **Given** 已通过会话管理器创建一个会话并释放租约，**When** 以相同 `(channel, user, Agent)` 与该会话 ID 再次获取会话，**Then** 返回同一个会话且 ID 一致（多轮续聊串起同一会话）。
2. **Given** 已存在一个归属特定 `(channel, user, Agent)` 的会话，**When** `channel`、`user`、`Agent` 任一不同却尝试复用该会话 ID，**Then** 系统以身份不匹配拒绝，不串号。
3. **Given** 同一 `(channel, user, Agent)` 或不同组合发起新建会话，**When** 通过会话管理器新建两次，**Then** 会话 ID 均由会话管理器内部统一生成且互不冲突，外部入口不自行拼接 ID。
4. **Given** 使用手工建表迁移脚本初始化空工作区数据库，**When** 写入会话元数据及多条结构化消息（含用户消息、助手工具调用、工具结果、最终回复），**Then** 回读后会话元数据与每条消息内容、角色、顺序、工具调用配对信息完全一致。
5. **Given** 已写入会话与完整对话轮次的数据库，**When** 关闭存储资源并新建独立存储上下文重新打开同一数据库（模拟进程重启），**Then** 原会话与全部历史消息完整可读，不丢失、不篡改。

---

### User Story 2 - CLI 入口、12 命令与轻重分流边界保持稳定可验 (Priority: P1)

维护者在对齐第 18 节后运行既有 CLI 契约测试与全量门禁，确认 12 个子命令、`--help`、轻命令不启动运行时、`chat` 多轮交互与 `/quit` 退出、共享工作区与持久化存储的行为保持完整且零回归。

**Why this priority**: 本次为行为保持型边界对齐，不得破坏 spec 001~007 已建立的 CLI 与运行时外部契约。

**Independent Test**: 运行既有 CLI 与存储测试分组及全量构建，全部用例通过且分组登记数量与实际执行一致。

**Acceptance Scenarios**:

1. **Given** 12 个 CLI 子命令与根命令，**When** 执行轻命令或请求 `--help` / `--version`，**Then** 不启动重型运行时即可完成响应。
2. **Given** CLI `chat` 与 HTTP / 调度共享同一工作区数据库，**When** 执行既有跨模式与重启恢复测试，**Then** 全部通过且行为与对齐前一致。

---

### User Story 3 - 课件与现行实现的差异有据可查 (Priority: P2)

课件作者或学员对照第 18 节课件阅读代码时，能在一份修订建议文档中查到每处差异的课件原写法、现行实现位置与设计依据，以及验收测试类的精确映射。

**Why this priority**: 第 18 节课件中关于确定性 `session_id`、`messages_json` 单列、`@EnableJpaRepositories` 及 `OryxOsCli` 的教学写法与生产级实现存在多处有依据的差异，不沉淀对照文档会造成理解偏差。

**Independent Test**: 逐条核对“保留的偏差”表与修订建议文档，每条均给出课件原写法、现行写法、真实代码路径及设计依据。

**Acceptance Scenarios**:

1. **Given** 第 18 节课件修订建议文档，**When** 逐条对照“保留的偏差”表，**Then** 全部 6 项偏差均有对应说明与真实文件定位。
2. **Given** 课件第四部分验收 harness 表，**When** 查阅文档中的测试对照表，**Then** `SessionManagerTest` 与 `SessionRepositoryTest` 均映射到现行测试类与方法。

### Edge Cases

- 尝试以不存在的会话 ID 获取会话：返回未找到错误，不静默新建。
- 尝试在会话租约尚未释放时并发重入同一会话：直接拒绝（忙碌），不排队、不覆盖状态。
- 会话归档后再尝试获取：拒绝（已归档），同时已持久化历史仍保持完整可读。
- 重启后以全新会话管理器重新打开已有会话：首次读取持久化历史与序号正常恢复，续写新轮次序号连续无冲突。
- 轻命令面对未初始化工作区或非法参数：安全返回明确错误码，不启动重型运行时，不泄露敏感配置。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 在核心模块新增按课件命名的 `SessionManagerTest`，覆盖同会话 ID 相同身份三元组可复用于多轮对话、channel/user/Agent 任一不同即拒绝隔离、会话 ID 由会话管理器内部统一生成且新会话互不相同、以及归档/忙碌边界。
- **FR-002**: 在存储模块新增按课件命名的 `SessionRepositoryTest`，覆盖手工迁移脚本建出的 `sessions` / `messages` 表可正常存取、多轮结构化消息（含工具调用与结果）序列化回读完整、以及关闭并重建存储上下文后（模拟重启）历史完整保留。
- **FR-003**: 新增测试方法名全部使用英文，并通过 `@DisplayName` 保留课件验收 harness 原文（包括课件示例代码的关键回归场景说明），不删除或弱化任何既有测试断言。
- **FR-004**: 保持现有 `SessionContractTest`、`SessionRuntimeContractTest`、`PersistentStorageContractTest`、`SessionReadContractTest`、`CliOperationsTest`、`CliRuntimeContractTest` 等前序测试原样通过，零回归。
- **FR-005**: 同步更新测试分组登记清单与对应验证说明文档，使新增的 `SessionManagerTest` 与 `SessionRepositoryTest` 纳入 `us5-cli`（及对应的汇总分组）并由报告门禁校验用例数一致。
- **FR-006**: 保持外部 CLI 命令参数、退出码、JSON 信封、HTTP 契约、数据库表结构与迁移脚本零变更，不新增第三方依赖，不新增未授权的对外公共类型。
- **FR-007**: 若触及任何受质量基线约束的既有主代码，同步清理其存量违规使基线总数只减不增；若无需修改主代码，确保基线零新增且全量静态门禁通过。
- **FR-008**: 交付第 18 节课件修订建议文档（`lesson-18-revision.md`），逐条列出 6 项保留偏差、真实代码定位与测试对照表。

### Key Entities *(include if feature involves data)*

- **Session（会话）**: 承载一次连续对话的身份三元组（`channel`、`user_id`、`agent_name`）、创建时模型快照（`provider_name`、`model`）、类型（`CONVERSATION` / `ONE_SHOT`）、状态（`ACTIVE` / `ARCHIVED`）及时间戳。
- **SessionMessage（会话消息）**: 归属特定会话、执行与轮次的结构化消息记录（`USER` / `ASSISTANT` / `TOOL`），保持顺序号与工具调用配对信息。
- **CLI Command（命令行入口）**: 分为无需启动运行时的轻命令与需要启动运行时的重命令，统一共享工作区定位规则与持久化存储。

## Assumptions & Dependencies

- **依赖前序交付物**: 第 16 节（Provider，`005-provider-boundary-refactor`）与第 17 节（ReAct，`006-react-boundary-refactor`）均已合入 `origin/main` 并验证通过。
- **明确不做（边界）**: 不修改已确认的 UUID 多会话模型为确定性三元组单会话；不将 `messages` 分表退化为 `sessions.messages_json` 单列；不改名 `CliChannelAdapter` 或 `OryxCommandLine`；不引入新的 Spring Data JPA Repository 自动扫描；不做流式 SSE、多租户、远程 CLI 代理或 GraalVM Native Image。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 课件验收 harness 表的 2 个测试类（`SessionManagerTest`、`SessionRepositoryTest`）均存在于对应模块且 100% 通过，覆盖全部 6 个验收子点。
- **SC-002**: 前序所有模块测试与全量构建门禁 100% 通过，测试分组登记数与实测执行数零偏差。
- **SC-003**: 质量基线违规总数不增加，静态代码规范与安全扫描零新增违规。
- **SC-004**: 课件修订建议文档覆盖全部 6 项保留偏差与 2 个测试类映射，每条均可追溯到真实文件。
