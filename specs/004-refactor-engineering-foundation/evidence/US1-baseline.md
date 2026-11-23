# US1：存量基线（T023）

命令：`./tools/dev/quality-baseline --init`（2026-11-21，`baseCommit` = `5b288ba`，即纯格式化提交）。报告由 `./tools/dev/mvnw -B -o compile checkstyle:check pmd:pmd spotbugs:spotbugs` 生成，插件配置与 `clean verify` 相同，已包含 T022 的例外与 PMD `AvoidCatchingGenericException` 替换。

基线只收主代码（`scope=main`），唯一键为 `(tool, rule, file)`，共 **460** 条：

| 工具 | 规则 | 条目数 |
| --- | --- | --- |
| checkstyle | AvoidStarImportCheck | 143 |
| checkstyle | NeedBracesCheck | 108 |
| checkstyle | MultipleVariableDeclarationsCheck | 15 |
| checkstyle | OverloadMethodsDeclarationOrderCheck | 4 |
| checkstyle | AbbreviationAsWordInNameCheck | 1 |
| pmd | ControlStatementBraces | 108 |
| pmd | PreserveStackTrace | 44 |
| pmd | OryxThreadPoolCreation | 6 |
| pmd | CompareObjectsWithEquals | 2 |
| pmd | AvoidCatchingGenericException | 1 |
| spotbugs | REC_CATCH_EXCEPTION | 12 |
| spotbugs | NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE | 10 |
| spotbugs | AT_STALE_THREAD_WRITE_OF_PRIMITIVE | 2 |
| spotbugs | PREDICTABLE_RANDOM | 1 |
| spotbugs | USO_UNSAFE_METHOD_SYNCHRONIZATION | 1 |
| spotbugs | USO_UNSAFE_STATIC_METHOD_SYNCHRONIZATION | 1 |
| spotbugs | WMI_WRONG_MAP_ITERATOR | 1 |

合计：checkstyle 271、pmd 161、spotbugs 28。

随后 `quality-gate.py check` 输出 `Quality gate PASS: 460 baseline entries, 3913 report-only findings`。只报告的条目来自测试代码与 Checkstyle `info` 级别（Javadoc 类）。

清零建议：`NeedBracesCheck` / `ControlStatementBraces` 是同一问题的两份报告（单行 `if` 无花括号），`AvoidStarImportCheck` 可机械修复；这两类约占 78%，适合单独的小提交逐模块清理后执行 `quality-baseline --prune`。
