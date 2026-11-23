# US1：门禁复现与拦截验证（T024，quickstart §1）

日期 2026-11-21，分支 `004-refactor-engineering-foundation`（HEAD `5b288ba` 加未提交的 US1 后续改动），Temurin 21.0.13+9，Apple M4，依赖缓存已就绪。

| # | 操作 | 实际结果 | 结论 |
| --- | --- | --- | --- |
| 1.1 | `./tools/dev/mvnw -B clean verify` | 退出码 0；`BUILD SUCCESS`（Maven `Total time: 10:20 min`）；`Test evidence PASS: 322 required cases`；`Quality gate PASS: 460 baseline entries, 3913 report-only findings`；脚本计时 **622 秒** | 通过。相对 T0 = 594 秒多 **28 秒**，满足 SC-001（≤ 3 分钟） |
| 1.2 | 把 `core/runtime/Uuids.java` 一行改成 4 空格缩进，执行 `spotless:check` | 退出码 1，`The following files had format violations: src/main/java/io/github/oryxlabs/oryxos/core/runtime/Uuids.java`；`spotless:apply` 后再次检查退出码 0，文件与原文逐字节一致 | 通过 |
| 1.3 | 新建 `core/probe/GateProbe.java`，写空的 `catch (Exception e) {}`，执行 `./tools/dev/mvnw -pl oryxos-core verify` | 退出码 2。首行 `Build gate: Quality gate FAIL: G2(checkstyle) 1 new, G3(pmd) 1 new, G4(spotbugs) 1 new`，其后 `pmd EmptyCatchBlock oryxos-core/.../GateProbe.java:12 Avoid empty catch blocks` 与 `checkstyle EmptyCatchBlockCheck .../GateProbe.java:12` | 通过（定位到行） |
| 1.4 | 同一探针中写 `Runtime.getRuntime().exec("sh -c " + arg)` | 同一次运行中输出 `spotbugs COMMAND_INJECTION oryxos-core/.../GateProbe.java:17 Potential Command Injection` | 通过（定位到行） |
| 1.5 | `./tools/dev/mvnw verify -Dspotbugs.skip=true` | 退出码 2，`Build gate: Isolation/test-gate override is not supported: -Dspotbugs.skip` | 通过 |
| 1.6 | 删除探针后执行 `./tools/dev/mvnw -pl oryxos-core clean verify` | 退出码 0，`Quality gate PASS: 460 baseline entries, 979 report-only findings` | 通过；探针文件已删除，`git status` 中 `oryxos-core` 无残留 |

与 quickstart 文字的差异：quickstart 写的期望输出是 `G6 pmd EmptyCatchBlock ...`。按偏差 D-6，实际首行为 `Quality gate FAIL: G<n>(<tool>) ...` 汇总，后续每行是 `tool rule file:line message`。1.3 与 1.4 合并成一个探针、只跑 `oryxos-core`，以节省一次完整构建；门禁判定使用的仍是全仓基线。
