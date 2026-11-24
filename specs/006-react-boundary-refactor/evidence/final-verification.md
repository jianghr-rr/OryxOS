# 最终验证（T025）

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 全量（第 1 次） | `./tools/dev/mvnw -B -ntp clean verify` | **失败 1 项**：`SoakHarnessTest.shortHarnessUsesOneHundredRealSessionsAndCompleteSqliteAuditWithoutClaimingSoak:84 expected <0> but was <9>`（100 会话冒烟中 2 个 HTTP 503、7 个“未完整确认”、4 条 `corruptOrForeignRecords`） |
| 失败复核 | `-Dgroups=kernel-soak-harness` 用同一份代码连跑 2 次 | 2 次都 PASS（5/5） |
| 全量（第 2 次，代码未变） | `./tools/dev/mvnw -B -ntp clean verify` | `BUILD SUCCESS`；`Test evidence PASS: 353 required cases`；`Quality gate PASS: 448 baseline entries` |
| prune 后门禁 | `./tools/dev/mvnw -B -ntp clean verify -Dgroups=us2-loop` | `BUILD SUCCESS`；`Test evidence PASS: 44 required cases`；`Quality gate PASS: 444 baseline entries` |
| 迁移方法名 | quickstart §3 | 无差异 |
| 边界搜索 | quickstart §4 | `ProfileContext.(set|clear)` 只在 `AgentService.java:236/284`；主代码中 `ProfileContext.current` 零引用；`tools/` 与 `verification.md` 中 `ReActContractTest` 零引用 |

**SoakHarnessTest 结论**：判为与本次改动无关的偶发失败，依据如下：
1. `git diff -w` 显示 `AgentService` 除 `ProfileContext.set/clear` 两行、截止调度器改为显式构造之外，只有加花括号和导入；
2. 主代码中没有任何地方读 `ProfileContext`；
3. `Executors.newSingleThreadScheduledExecutor(f)` 底层就是 `ScheduledThreadPoolExecutor(1, f)`，只是外面多一层不可重配置的包装；
4. 同一份代码单独跑 2 次、全量再跑 1 次都通过。

它在全量构建中偶发，且历史证据中没有记录过，建议单独立项观察；本规格未修改该测试。
