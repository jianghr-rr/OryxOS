# US3：功能分支 CI（T062）

## 正常提交：通过

2026-11-23 把功能分支 `004-refactor-engineering-foundation` 推送到 GitHub，head 为 `8fa95f1`，共 7 个提交。推送触发 `Quality Gate` 工作流，触发条件是对 main 以外分支的 push。

| 项 | 结果 |
| --- | --- |
| 运行 | [Quality Gate #45108707991](https://github.com/jianghr-rr/OryxOS/actions/runs/45108707991) |
| Job | `Full Verify`：success，所有步骤 success 或 skipped |
| 时长 | 03:54:44Z 开始，04:10:28Z 结束，约 15.7 分钟（包含 `kernel-soak-harness` 分组），未超过 45 分钟 |

## 不合规提交：标红

推送提交 `2c38a8a`。它把 `RequestTrace.java` 的一行改成错误缩进和多余空格，用来验证门禁会拦下不合规代码。

| 项 | 结果 |
| --- | --- |
| 运行 | [Quality Gate #45110860570](https://github.com/jianghr-rr/OryxOS/actions/runs/45110860570) |
| Job | `Full Verify`：failure，用时 21 秒（04:16:42Z–04:17:03Z） |
| 失败步骤 | `Format check` 为 failure；`Full verify (tools/dev/mvnw clean verify)`、`Baseline must not grow`、`Upload boot JAR` 均为 skipped。失败停在格式检查这一步，从步骤名就能看出原因 |

## 修复后：变绿

紧接着用 `git revert` 撤销这处改动，修复提交与本证据一起推送，结果见下节。
