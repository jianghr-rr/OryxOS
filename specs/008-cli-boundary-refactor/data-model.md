# Data Model & Harness Mapping: 第 18 节 CLI 与会话层

## 1. 核心数据模型（现状保持，不变更表结构）

- **`sessions` 表（`V003__session_execution.sql`）**：`id`（PK, UUID）、`workspace_id`、`channel`、`user_id`、`agent_name`、`provider_name`、`model`、`kind`（`CONVERSATION`/`ONE_SHOT`）、`status`（`ACTIVE`/`ARCHIVED`）、`created_at`、`archived_at`、`version`。
- **`messages` 表（`V003__session_execution.sql`）**：`id`（PK）、`session_id`（FK）、`execution_id`、`turn_id`、`seq`、`role`（`USER`/`ASSISTANT`/`TOOL`）、`content_json`、`provider_name`、`provider_opaque_json`、`created_at`。

## 2. 课件验收 harness → 测试类与方法映射

| 课件测试类 | 目标模块与路径 | `@Tag` | 新增英文方法名 | `@DisplayName`（课件原文对照） | 覆盖守点 |
| --- | --- | --- | --- | --- | --- |
| `SessionManagerTest` | `oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/session/SessionManagerTest.java` | `us5-cli` | `sameIdentityTriple_reacquiresSameSessionAcrossTurns` | 同一三元组按同一会话ID续聊返回同一个Session（多轮对话串连） | 同一 `(channel, user, Agent)` 多次 `acquire` 同一 ID 返回相同会话 |
| `SessionManagerTest` | 同上 | `us5-cli` | `differentChannelUserOrProfile_rejectedWithIdentityMismatch` | channel/user/profile 任一不同即隔离拒绝（防串号） | `channel`、`user`、`Agent` 任一不同均抛 `SESSION_IDENTITY_MISMATCH` |
| `SessionManagerTest` | 同上 | `us5-cli` | `sessionIdGeneratedExclusivelyInsideSessionManager` | 会话 ID 由 SessionManager 内部统一生成且新会话互不冲突 | 两次新建均由内部生成唯一 UUID，不存在外部拼接依赖 |
| `SessionManagerTest` | 同上 | `us5-cli` | `busyOrArchivedSessionCannotBeReacquired` | 进行中租约拒绝重入、归档后拒绝续聊且保留历史 | 并发租约报 `SESSION_BUSY`，归档后报 `SESSION_ARCHIVED` 且 `lookup` 可见 |
| `SessionRepositoryTest` | `oryxos-storage/src/test/java/io/github/oryxlabs/oryxos/storage/SessionRepositoryTest.java` | `us5-cli` | `manualMigrationSchemaPersistsAndReadsSessionMetadata` | 手工建表脚本建出的 sessions 表能存能读 | 通过 `StorageResourceFactory` 跑手工迁移脚本，写入并回读 `Session` 元数据 |
| `SessionRepositoryTest` | 同上 | `us5-cli` | `structuredMessagesJsonRoundTripPreservesToolPairs` | 结构化消息 JSON 序列化回读后消息与工具配对完整 | 写入 `USER` / `ASSISTANT(toolCalls)` / `TOOL` / `ASSISTANT(final)`，回读内容与配对完整 |
| `SessionRepositoryTest` | 同上 | `us5-cli` | `reopeningStorageContextAfterRestartPreservesSessionHistory` | 模拟重启（新建存储上下文重查）后会话与历史完整保留 | 关闭 `StorageResources` 后以新 `StorageResourceFactory` 重开同一 `oryxos.db`，会话与历史完整 |

## 3. 测试分组登记变化

- `us5-cli`：15 → 22（原有 `CliOperationsTest` 6 + `CliRuntimeContractTest` 9；新增 `SessionManagerTest` 4 + `SessionRepositoryTest` 3）。
- `us2-loop`、`us3-memory`、`storage-faults`、`us5-http` 等前序分组保持不变。
