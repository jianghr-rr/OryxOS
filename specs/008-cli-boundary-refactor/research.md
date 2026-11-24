# Research: 第 18 节 CLI 与会话层现状核查

**Feature**: `008-cli-boundary-refactor` | **Date**: 2026-11-24

## R1. CLI 入口与 12 个子命令核查

- **入口结构**：
  - `oryxos-cli`: `OryxOsCli` 仅作薄 JAR `--version` 入口；完整 Picocli 根命令是 `OryxCommandLine`，在 `commandLine()` 中注册全部 12 个操作：`init`、`status`、`serve`、`gateway`、`chat`、`profile`（`list`/`create`/`show`/`delete`）、`provider list`、`tool list`、`session list`。
  - `oryxos-boot`: `OryxOsApplication.main()` 经 `OryxCommandLine` 解析参数；轻命令（`init`/`status`/`profile *`/`provider list`/`tool list`/`session list`/`--help`/`--version`）直接返回，不触发 Spring Boot 启动；重命令（`chat`/`serve`/`gateway`）通过 `BootRuntimeLauncher` 启动运行时。
- **既有覆盖**：`EntryPointContractTest`、`WorkspaceCommandsTest`、`AgentStatusTest`、`CliOperationsTest`、`CliRuntimeContractTest`、`CliCommandsIT` 已完整覆盖 12 命令、`--help`、退出码、JSON 信封与轻命令不启 Spring。

## R2. 重命令 JPA/Entity 扫描与手工建表脚本

- `OryxOsApplication` 显式排除 `DataSourceAutoConfiguration`、`HibernateJpaAutoConfiguration`、`JpaRepositoriesAutoConfiguration`。
- 存储生命周期由 `oryxos-storage` 的 `StorageResourceFactory` 绑定工作区互斥锁、运行 `SchemaMigrator`（手工 SQL 脚本 `V001`~`V007`，其中 `V003__session_execution.sql` 创建 `sessions`、`executions`、`messages`、`workspace_state`）并显式构造 Hibernate `EntityManagerFactory`。
- 因此不存在课件所述的“漏配 `@EnableJpaRepositories`/`@EntityScan` 导致 Found 0 JPA repository interfaces”问题；此设计在 spec 005/006 中已作为保留偏差固定。

## R3. 会话层契约与课件差异

- **核心类**：`oryxos-core` 的 `SessionManager`、`Session`、`SessionStore`、`SessionMessage`；`oryxos-storage` 的 `SessionEntity`、`MessageEntity`、`JpaSessionStore`。
- **ID 与身份模型**：
  - 课件：`getOrCreate(channel, user, profileName)` 按三元组拼接确定性 ID（幂等）。
  - 现行（宪章 + spec 001）：会话 ID 由 `SessionManager` 内部统一经 `Uuids.v4()` 生成，同一 `(channel, user, Agent)` 允许多会话；同会话续聊传入会话 ID 并由 `SessionManager.acquire` 强制校验 `(channel, user, agentName)` 身份三元组（任一不符抛 `SESSION_IDENTITY_MISMATCH`）及创建时 `(providerName, model)` 快照。
- **消息存储形态**：
  - 课件：`sessions.messages_json` 单列存整段对话 JSON。
  - 现行：`V003__session_execution.sql` 分表存 `sessions` + `messages`（每条消息含 `content_json`、`provider_opaque_json`、`seq`、`turn_id`、`execution_id`），支持结构化工具调用配对与精确轮次分页。

## R4. 结论

本次无需修改主代码与表结构，只需在 `oryxos-core` 与 `oryxos-storage` 新增课件命名的 `SessionManagerTest` 与 `SessionRepositoryTest`，补齐直接对应第 18 节验收 harness 的可读测试用例，同步测试分组登记并交付 `lesson-18-revision.md`。
