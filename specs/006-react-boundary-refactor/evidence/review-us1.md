# US1 故事级复查（`ProfileContext`）

审查对象：`oryxos-core/.../core/agent/ProfileContext.java`（新增）、`AgentService.java`（两行接线）。

| 契约 | 结论 | 依据 |
| --- | --- | --- |
| PC1 处理期间 `current()` 与 `ctx.agent()` 同一实例 | 满足 | `AgentServiceTest#profileContextIsAvailableDuringToolExecution`（`assertSame`） |
| PC2 内联路径异常后已清理 | 满足 | `#profileContextIsClearedWhenProcessingThrows`，快照 `FAILED`/`EXECUTION_FAILED` |
| PC3 异步路径在 `handle.complete` 回调前已清理 | 满足 | `#profileContextIsClearedBeforeCompletionOnWorkerThread`：`cleanup` 回调内读取为 `null`，且处理期间非空 |
| PC4 `set`/`clear` 只在 `AgentService` | 满足 | `rg 'ProfileContext\.(set|clear)' -g '!**/test/**'` 仅命中 `AgentService.java:211`、`:256` |
| PC5 现有工具/记忆不改取值方式 | 满足 | `rg 'ProfileContext\.current' -g '!**/test/**'` 零命中 |

FR-002 “所有退出路径”：`set` 是 `try` 第一句，`clear` 是同一 `try` 的 `finally` 第一句，`OryxError`、`RuntimeException`、`Error` 均经过；
会话获取失败与准入拒绝发生在 `workerTask` 之前，从未设置。FR-003：未改动任何工具或记忆后端。
