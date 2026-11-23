# US3：提交前钩子实测（T061，quickstart 3.1）

日期 2026-11-23，Temurin 21.0.13+9，Apple M4。

## 环境

维护者只授权了 004 分支上的两次本地提交，因此本测试**不在维护者仓库中提交**。测试使用 T074 的干净副本 `/tmp/oryx004/clean`：在该目录里新建一个独立的临时 git 仓库，用 `--no-verify` 提交一次当前全部文件作为起点，再执行 `./tools/dev/install-hooks`。维护者仓库的 `core.hooksPath` 始终保持未设置。

## 结果

| # | 操作 | 结果 | 耗时 |
| --- | --- | --- | --- |
| 1 | 把 `core/runtime/Uuids.java` 的 2 空格缩进改成 4 空格，`git add` 后 `git commit` | 退出码 1，提交未产生；输出 `spotless:check ... The following files had format violations` 和 `[pre-commit] Format or analysis failed. To fix formatting run: ./tools/dev/mvnw spotless:apply` | 3 秒 |
| 2 | 执行 `./tools/dev/mvnw spotless:apply` 后再提交（附带一行合规注释改动） | 文件恢复为合规格式；退出码 0，提交成功 | 11 秒 |
| 3 | 新建 `core/probe/HookProbe.java`，写一个空的 `catch (RuntimeException e) {}`，提交 | 退出码 1，提交未产生；`Quality gate FAIL: G2(checkstyle) 1 new, G3(pmd) 1 new`，并列出 `pmd EmptyCatchBlock .../HookProbe.java:7` 与 `checkstyle EmptyCatchBlockCheck .../HookProbe.java:7` | 12 秒 |

三种情况都在目标的 60 秒以内。依赖缓存与编译产物已就绪；首次在新检出上提交时需要完整编译，耗时会更长。

## 结论

钩子能在提交阶段拦截格式错误，也能拦截新增的 PMD/Checkstyle 违规，并给出修复命令和文件行号；修复后可以正常提交。`git commit --no-verify` 可以跳过钩子（第 1 步之前的起点提交就是这样产生的），这种情况由 CI 的 `Full Verify` 兜底，对应 T062，尚待授权推送后验证。
