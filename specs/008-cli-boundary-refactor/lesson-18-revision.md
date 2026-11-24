# 第 18 节课件修订建议（对齐现行 OryxOS 实现）

**来源课件**: `docs/第18节：CLI 功能概述、实现思路与代码讲解.md`
**对应规格**: `specs/008-cli-boundary-refactor/spec.md`（分支 `008-lesson18-cli-boundary`）
**整理日期**: 2026-11-24

第 18 节课件以教学示意方式讲解 CLI 入口与会话层。以下 6 项是现行生产实现（spec 001~007 + 宪章 v1.0.0）与课件写法之间的**有依据差异**，建议在后续修订课件时补充说明或对照注释。

## 一、保留的偏差对照表

| # | 课件原写法 | 现行实现写法 | 代码位置 | 保留依据 |
| --- | --- | --- | --- | --- |
| 1 | `OryxOsCli` 是整个可执行 JAR 的 `main` 入口并直接挂 12 个 `@Command` 子命令 | `OryxOsCli` 仅作 `oryxos-cli` 薄 JAR 的 `--version` 入口；完整 Picocli 根命令是 `OryxCommandLine`（挂 12 子命令），fat JAR 真正 `main` 入口是 `oryxos-boot` 的 `OryxOsApplication` | `oryxos-cli/src/main/java/io/github/oryxlabs/oryxos/cli/OryxOsCli.java`、`OryxCommandLine.java`；`oryxos-boot/src/main/java/io/github/oryxlabs/oryxos/boot/OryxOsApplication.java` | 保持模块职责分离：`oryxos-cli` 不反向依赖 Spring Boot 与全部业务模块，轻命令由 `OryxCommandLine` 先于 Spring 解析执行 |
| 2 | 重命令启动类需显式声明 `@EnableJpaRepositories(basePackages=...)` 与 `@EntityScan(basePackages=...)` 避免 `Found 0 JPA repository interfaces` | `OryxOsApplication` 显式排除 `DataSourceAutoConfiguration` / `HibernateJpaAutoConfiguration` / `JpaRepositoriesAutoConfiguration`，由 `StorageResourceFactory` 绑定工作区锁、跑手工迁移脚本并程序化创建 `EntityManagerFactory` | `oryxos-boot/src/main/java/io/github/oryxlabs/oryxos/boot/OryxOsApplication.java`；`oryxos-storage/src/main/java/io/github/oryxlabs/oryxos/storage/lifecycle/StorageResourceFactory.java` | 与 spec 005/006 同口径：SQLite 单写协调、校验和保护与进程锁要求显式控制连接池与迁移生命周期，避免自动扫描绕过锁与迁移检查 |
| 3 | `chat` 交互类名为 `CliChannel` | 现名为 `CliChannelAdapter`（位于 `oryxos-channel-cli`） | `oryxos-channel-cli/src/main/java/io/github/oryxlabs/oryxos/channel/cli/CliChannelAdapter.java` | 已被装配层与集成测试引用，保持命名与入站适配器语义一致，不破坏既有契约 |
| 4 | `SessionManager.getOrCreate(channel, user, profileName)` 按三元组拼接确定性 `session_id`（幂等），对外 `getOrCreate`/`get`/`save` | 会话 ID 由 `SessionManager` 内部统一生成随机 UUID（`Uuids.v4()`），同一 `(channel, user, Agent)` 允许多会话；对外提供 `acquire`/`acquireForExecution`/`lookup`/`archive`，续聊传入会话 ID 并强制校验三元组（不符抛 `SESSION_IDENTITY_MISMATCH`）与创建时模型快照 | `oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/session/SessionManager.java`、`Session.java` | 宪章与 spec 001 已确认：允许同一用户在同一渠道与同一 Agent 开启多条独立会话，同时保留“ID 仅在 `SessionManager` 内部生成”与三元组防串号不变量 |
| 5 | `sessions` 表含 `messages_json` 单列存整个对话历史、`last_active_at` 列，接口为 `SessionRepository` | `V003__session_execution.sql` 拆分为 `sessions`（会话元数据）+ `messages`（逐条结构化消息，含 `content_json`、`provider_opaque_json`、`seq`、`turn_id`、`execution_id`）；契约为 `SessionStore`（core）+ `JpaSessionStore`（storage） | `oryxos-storage/src/main/resources/db/migration/V003__session_execution.sql`；`oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/session/SessionStore.java`；`oryxos-storage/src/main/java/io/github/oryxlabs/oryxos/storage/session/JpaSessionStore.java` | 分表支持逐条即时落盘、工具调用 ID 配对、原子事务回滚及精确轮次分页，避免单列 JSON 整列重写损坏 |
| 6 | `chat` 仅声明 `@Option(names = "--profile", defaultValue = "default")` | `ChatCommand` 要求显式传 `--profile` 与 `--user`，并支持 `--session` 续聊与 `--json` 机器输出 | `oryxos-cli/src/main/java/io/github/oryxlabs/oryxos/cli/command/ChatCommand.java` | 防止多用户本地调试时静默共用默认身份，与 HTTP/调度统一身份三元组 |

## 二、验收 harness 测试类映射表

| 课件第四部分测试类 | 现行测试类路径 | 所属测试分组 | 覆盖的验收点 |
| --- | --- | --- | --- |
| `SessionManagerTest` | `oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/session/SessionManagerTest.java` | `us5-cli` | 同会话 ID 同三元组多轮复用；channel/user/profile 任一不同拒绝（`SESSION_IDENTITY_MISMATCH`）；ID 仅在 `SessionManager` 内部统一生成；忙碌/归档边界 |
| `SessionRepositoryTest` | `oryxos-storage/src/test/java/io/github/oryxlabs/oryxos/storage/SessionRepositoryTest.java` | `us5-cli` | 手工迁移脚本（`V001`~`V007`）建表存取；结构化消息 JSON 序列化与工具配对回读完整；关闭并重建存储上下文模拟重启后会话与历史完整保留 |
| （补充既有 CLI 契约） | `oryxos-cli/.../CliOperationsTest.java`、`oryxos-boot/.../CliRuntimeContractTest.java`、`EntryPointContractTest.java` | `us5-cli`、`f0-discovery` | 12 子命令、`--help`/`--version`、轻命令不启动 Spring、退出码与 JSON 信封、跨重启恢复 |
