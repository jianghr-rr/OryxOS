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

## 推送后补充：根因与修复

推送后 GitHub Actions（Quality Gate，提交 `18b07e9`）同一断言失败，`expected <0> but was <32>`，说明这不是偶发问题，必须查清根因。

**根因在测试桩，与本规格的改动无关。** 以本地第 1 次失败（证据 `.oryxos-dev/evidence/soak-smoke/ab63e7c2-…/workspace/oryxos.db`）为例：
- 失败的 llm_calls 为 2 条 `PROVIDER_CONNECTION`，失败的 tool_invocations 为 7 条 `HTTP_CONNECTION`（"HTTP read failed; no implicit retry was made"）。
- 这 9 条全部发生在 `15:09:13.129–.131` 之间，且两个桩的 handler 都没有记录违规，说明请求根本没到达桩。
- 桩是 JDK 21 内置 `HttpServer`。`ServerImpl` 会在空闲连接数达到 `sun.net.httpserver.maxIdleConnections`（默认 200）时，直接关闭刚处理完请求的连接。
- 100 个会话加上 Provider 和 `http_get` 两个客户端，连接数正好在 200 附近。一旦大量连接同时空闲，超出部分会被服务端关闭，而客户端连接池仍会复用它们，于是读失败。
- 运行时代码按设计不做隐式重试，所以这些失败都被计为违规。

**修复**：在父 POM 的 surefire `systemPropertyVariables` 中设置 `sun.net.httpserver.maxIdleConnections=2048`。该值是 `ServerImpl` 类加载时读入的静态常量，只能在测试 JVM 启动时设置。生产代码未改动。

| 检查 | 结果 |
| --- | --- |
| 属性进入测试进程 | surefire 报告中 `sun.net.httpserver.maxIdleConnections=2048` |
| 修复后 `-Dgroups=kernel-soak-harness` | PASS（5/5） |
| 反向验证：临时改为 20 | 同一断言失败，`but was <355>`；失败类型同为 `PROVIDER_CONNECTION` / `PROVIDER_UNAVAILABLE` / `HTTP_CONNECTION`；验证后已恢复为 2048 |
| 修复后全量 `clean verify` | `BUILD SUCCESS`，boot 117 项全部通过 |
