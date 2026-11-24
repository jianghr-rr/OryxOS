# Final Verification Evidence — 008-cli-boundary-refactor（第 18 节）

- **Date**: 2026-11-24
- **Branch**: `008-lesson18-cli-boundary`（base: `origin/main` `64c9b1e`）

## 1. 全量构建与静态门禁（`mvn clean verify`）

```text
ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home ./tools/dev/mvnw -B -ntp clean verify
...
OryxOS ............................................. SUCCESS [  1.078 s]
OryxOS core ........................................ SUCCESS [  8.721 s]
OryxOS provider .................................... SUCCESS [  6.204 s]
OryxOS storage ..................................... SUCCESS [ 15.593 s]
OryxOS memory ...................................... SUCCESS [  5.250 s]
OryxOS tool ........................................ SUCCESS [ 12.189 s]
OryxOS channel-cli ................................. SUCCESS [  1.869 s]
OryxOS web ......................................... SUCCESS [  3.028 s]
OryxOS cli ......................................... SUCCESS [  3.512 s]
OryxOS boot ........................................ SUCCESS [09:38 min]
BUILD SUCCESS (Total time: 10:36 min)
Test evidence PASS: 365 required cases; run 05cb16fc-a7e7-49f4-9227-cecbacda32b1
Quality gate PASS: 444 baseline entries, 3993 report-only findings
```

- Spotless、Checkstyle/P3C、SpotBugs/FindSecBugs、PMD 全绿。
- `./tools/dev/quality-baseline --check-shrink origin/main` → `Baseline did not grow against origin/main: 444 entries`。

## 2. 本节定向验证与验收 harness 对号

- `ORYXOS_JAVA_HOME=... ./tools/dev/mvnw -B -ntp test -Dgroups=us5-cli` → `BUILD SUCCESS`，`Test evidence PASS: 22 required cases; run ee3b92f6-900b-4845-83be-e60e621fedf9`。
- `SessionManagerTest`（4/4 通过）：
  - `sameIdentityTriple_reacquiresSameSessionAcrossTurns`
  - `differentChannelUserOrProfile_rejectedWithIdentityMismatch`
  - `sessionIdGeneratedExclusivelyInsideSessionManager`
  - `busyOrArchivedSessionCannotBeReacquired`
- `SessionRepositoryTest`（3/3 通过）：
  - `manualMigrationSchemaPersistsAndReadsSessionMetadata`
  - `structuredMessagesJsonRoundTripPreservesToolPairs`
  - `reopeningStorageContextAfterRestartPreservesSessionHistory`

## 3. 本节交付物存在性核对

- CLI 主入口与 12 子命令：`OryxOsApplication.java`、`OryxCommandLine.java`、`OryxOsCli.java`、`ChatCommand` / `InitCommand` / `StatusCommand` / `ServeCommand` / `GatewayCommand` / `ProfileCommand`（4 子命令）/ `ProviderListCommand` / `ToolListCommand` / `SessionListCommand` — 全部存在。
- CLI 通道：`CliChannelAdapter.java`（`oryxos-channel-cli`）— 存在。
- 会话管理与存储：`Session.java`、`SessionManager.java`、`SessionStore.java`（`oryxos-core`）；`SessionEntity.java`、`MessageEntity.java`、`JpaSessionStore.java`、`V003__session_execution.sql`（`oryxos-storage`）— 全部存在。
- 验收测试：`SessionManagerTest.java`、`SessionRepositoryTest.java` — 均已落地且非空。
- 课件修订建议：`specs/008-cli-boundary-refactor/lesson-18-revision.md` — 已交付。

## 4. 前序节全量回归

- 全部 9 个子模块共 365 项必跑用例 0 失败、0 错误、0 跳过，覆盖第 16 节（`us1-contract`）、第 17 节（`us2-loop`）及 US3/US4/US5/kernel-local 全量契约。

## 5. H4 六条全局不变量自查

1. **涉外 IO 首行过 `Sandbox.enforce`**：本次无涉外 IO 主代码改动；既有 `FileTools`/`ShellTools`/`HttpTools`/`NotifyTools` 均维持原样并通过 `us4-tools`。
2. **LLM 与工具调用成败落库**：本次无主代码改动；既有 `llm_calls`/`tool_invocations` 审计路径完整通过 `us1-contract`/`us2-loop`/`storage-faults`。
3. **无明文 key**：grep 扫描新增文件与 diff 无任何真实密钥或 `sk-*`。
4. **`session_id` 只在 `SessionManager` 内生成**：主代码 `SessionManager.guarded` 统一由 `Uuids.v4()` 生成，`SessionManagerTest.sessionIdGeneratedExclusivelyInsideSessionManager` 已钉死。
5. **无 Reactor / `CompletableFuture` / 自建线程池**：grep 扫描新增测试文件结果 `H4_CLEAN`。
6. **无 Spring AI 自动工具执行路径**：`ProviderArchitectureGuardTest` 与 `ProviderToolSchemaTest` 在全量 `clean verify` 中全部通过。

## 6. 剩余人工验收项

1. 使用真实模型运行 `oryxos chat --profile <name> --user <user>` 完成多轮对话，确认 `/quit` 正常退出。
2. 人工确认轻命令（`init`、`profile list`、`--help`）秒回，重命令（`chat`、`serve`）才启动 Spring。
3. 人工核对三种运行模式（`chat` / `serve` / `gateway`）共享同一工作区配置与 SQLite 会话存储。
