# 最终全量校验（T074，SC-001、SC-002、SC-005）

日期 2026-11-23，Temurin 21.0.13+9，Apple M4。

## 干净检出

当前改动尚未全部提交，因此“干净检出”的做法是：把 `git ls-files -co --exclude-standard` 列出的文件（已跟踪文件加上未被忽略的未跟踪文件）复制到 `/tmp/oryx004/clean`。副本中没有任何 `target/` 目录和 IDE 编译产物。`.oryxos-dev` 在副本内新建，只有 `maven`（Wrapper 发行包）和 `repository`（依赖缓存）两个子目录链接到主仓库，`home`、`tmp` 留在副本内。`real-secrets.env` 等本地文件没有带入。

## 结果

| 项 | 结果 | 结论 |
| --- | --- | --- |
| 命令 | `time ./tools/dev/mvnw -B clean verify` | 退出码 0，`BUILD SUCCESS` |
| 耗时 | `10:40.60 total`（640.6 秒）；Maven `Total time: 10:39 min` | 相对 T0 = 594 秒多 **47 秒**，满足 SC-001（≤ 3 分钟） |
| 测试 | `Test evidence PASS: 334 required cases`（T001 为 322 例）；`tools/dev/test-groups.json` 分组数 51（改造前 50，新增 `f004-observability`） | 不少于 T001 |
| OpenAPI | `PublishedOpenApiTest` 2/2 通过 | 19 操作 / 44 schema 零差异（SC-005） |
| 门禁 | `Quality gate PASS: 460 baseline entries, 3946 report-only findings` | 基线没有增长（SC-002） |

## 全新检出暴露的问题与修复

在副本中第一次运行时 `oryxos-storage` 失败：`WriterIsolationTest` 把子进程输出写到 `oryxos.test.evidenceDir` 指向的 `.oryxos-dev/evidence/`，而 `tools/dev/build.py` 只传入了这个路径，从不创建目录。开发者本机的该目录早就因其他工具存在，所以一直没有暴露；但在 GitHub Actions 的全新检出上，`Full Verify` 一定会因此失败。

修复：`tools/dev/build.py` 在启动 Maven 前执行 `(dev / 'evidence').mkdir(parents=True, exist_ok=True)`。`tools/dev/test_build_environment.py` 的 8 个用例仍然通过，上表即修复后的结果。

另外，搭建副本时如果把整个 `.oryxos-dev` 做成指向主仓库的符号链接，`java.io.tmpdir` 的真实路径会落到副本工作区之外，ReAct 与 Session 的契约测试会被沙箱的路径边界检查拒绝。这是沙箱的正确行为，与本次改动无关；正式结果使用的是上面描述的隔离方式。
