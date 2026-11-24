# 故意破坏（T022，SC-005）

两次破坏均在运行 `./tools/dev/mvnw -B -ntp -o test -Dgroups=us2-loop` 后立即恢复；恢复后 `ReActLoop.java` 无 diff，
`AgentService.java` 中 `ProfileContext.(set|clear)` 各 1 处。

## 1. 取消轮数上限

quickstart §7 原计划把循环条件改为 `iteration <= limit + 1`；实施时发现第 `limit` 轮本身会提前 return，那样改不构成破坏。
因此改为 `int limit = ctx.agent().maxIterations() + 1;`。

```text
ReActLoopTest.modelAlwaysRequestingToolsStopsAtMaxIterations:75 expected: <10> but was: <11>
ReActLoopTest.iterationLimitPairsUnexecutedProposalsAndStopsNewIo:56 Unexpected exception thrown: java.util.NoSuchElementException
BUILD FAILURE
```

第二条是原有用例：上限变大后，循环向只备了 1 条回复的脚本请求第 2 次回复，因而一并变红。

## 2. 去掉出口清理

删除 `AgentService` 的 `ProfileContext.clear();`：

```text
AgentServiceTest.profileContextIsClearedWhenProcessingThrows:102 expected: <null> but was: <AgentProfile[name=demo, ...]>
AgentServiceTest.profileContextIsClearedBeforeCompletionOnWorkerThread:123 expected: <null> but was: <AgentProfile[name=demo, ...]>
BUILD FAILURE
```

这证明 US1 阶段两个“清理”用例并不是空过：去掉清理就会失败（参见 [red-green.md](./red-green.md)）。
