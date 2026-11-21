# 质量门禁配置

本目录及相邻的 `config/checkstyle/`、`config/pmd/`、`config/spotbugs/`、`config/dependency-check/` 存放全部门禁规则与例外，契约见 `specs/004-refactor-engineering-foundation/contracts/quality-gates.md`，使用说明见 `docs/EngineeringBaseline.md`。

- `baseline.json`：存量违规基线，按 `(tool, rule, file)` 登记，只减不增；由 `tools/dev/quality-baseline` 维护，不手工新增条目。
- 扫描范围：Spotless、Checkstyle、PMD 只处理各模块 `src/main/java` 与 `src/test/java`；SpotBugs 只分析主代码编译产物。`target/generated-sources` 等生成代码不在扫描范围内。
- 测试代码：格式全量阻断；Checkstyle 与 PMD 的发现只报告、不阻断。
- 例外：每条都必须写明 `reason:` 与 `registered: YYYY-MM-DD`（XML 文件写在条目前的注释或 `<notes>` 中，properties 文件写在条目前的 `#` 注释中），否则 `tools/dev/quality-gate.py` 判定失败。
