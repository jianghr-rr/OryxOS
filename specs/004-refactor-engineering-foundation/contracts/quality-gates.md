# 契约：质量门禁、命令与 CI 检查

对应需求：FR-010 ~ FR-022、SC-001、SC-002、SC-006。

## 1. 门禁清单

| # | 门禁 | 工具（锁定版本） | Maven 阶段 | 规则 / 豁免文件 | 阻断范围 |
| --- | --- | --- | --- | --- | --- |
| G1 | 格式 | Spotless 3.12.0 + google-java-format 2.0.0 | `process-sources`（`spotless:check`） | 无豁免 | 全仓主代码与测试代码，全量阻断 |
| G2 | Checkstyle | maven-checkstyle-plugin 3.6.0 + Checkstyle 14.4.0 | `verify`（只出报告） | `config/checkstyle/checkstyle.xml`、`config/checkstyle/suppressions.xml` | 主代码中 severity≥warning 且不在基线内的条目 |
| G3 | 编码规约（P3C 映射） | maven-pmd-plugin 3.28.0 + PMD 7.29.0 | `verify`（只出报告） | `config/pmd/alibaba-p3c.xml`、`config/pmd/exclude-pmd.properties` | 主代码中不在基线内的条目；测试代码只报告 |
| G4 | 静态安全 | spotbugs-maven-plugin 4.10.5.0 + Find Security Bugs 1.14.0 | `verify`（只出报告） | `config/spotbugs/exclude.xml` | 主代码中不在基线内的条目 |
| G5 | 测试 | Surefire（现有） | `test` | `tools/dev/test-groups.json` | 已登记分组必须全部执行且通过（现有门禁） |
| G6 | 基线判定 | `tools/dev/quality-gate.py`（新增） | Maven 结束后由 `tools/dev` 调用 | `config/quality/baseline.json` | 汇总 G2–G4 |
| G7 | 依赖漏洞 | dependency-check-maven 13.0.0 | 仅 profile `dependency-scan` | `config/dependency-check/suppressions.xml` | CVSS ≥ 7.0 |

所有豁免、抑制与例外都写在上表列出的仓库文件中，每条必须带 `reason` 与登记日期。不允许通过注释掉检查、关闭插件或命令参数来绕过（FR-018）。

## 2. 本地命令（唯一入口：`tools/dev`）

| 用途 | 命令 | 对应 CI 步骤名 |
| --- | --- | --- |
| 完整校验（格式 + 规约 + 安全 + 测试 + 基线） | `./tools/dev/mvnw clean verify` | `Full verify (tools/dev/mvnw clean verify)` |
| 自动格式化 | `./tools/dev/mvnw spotless:apply` | — |
| 只查格式 | `./tools/dev/mvnw spotless:check` | — |
| 依赖漏洞扫描（显式开启） | `NVD_API_KEY=... ./tools/dev/mvnw -Pdependency-scan dependency-check:aggregate` | `Dependency scan (tools/dev/mvnw -Pdependency-scan ...)` |
| 基线只减不增检查 | `./tools/dev/quality-baseline --check-shrink origin/main` | `Baseline must not grow` |
| 删除已清零的基线条目 | `./tools/dev/quality-baseline --prune` | — |
| 安装提交前钩子 | `./tools/dev/install-hooks` | — |

`NVD_API_KEY` 只在含 `-Pdependency-scan` 的命令中由 `build.py` 放行，不得写进文件或命令参数（执行时从当前 shell 环境读取）。

### 2.1 `tools/dev` 新增的禁止参数

除现有禁止项以外，以下参数会直接让 `tools/dev` 以退出码 2 拒绝执行：

`-Dspotless.`、`-Dcheckstyle.skip`、`-Dpmd.skip`、`-Dcpd.skip`、`-Dspotbugs.skip`、`-Ddependency-check.skip`、`-Dformat.skip`，以及任何形如 `-P!<profile>` / `-P-<profile>` 的 profile 反激活。

## 3. 基线文件格式（`config/quality/baseline.json`）

```json
{
  "schema": 1,
  "createdAt": "YYYY-MM-DD",
  "baseCommit": "<创建基线时的提交 SHA>",
  "entries": [
    { "tool": "pmd", "rule": "ThreadPoolCreation", "file": "oryxos-core/src/main/java/.../AgentService.java" }
  ]
}
```

- `entries` 按 `tool, file, rule` 排序，没有重复项。
- **判定**：本次报告中出现的 `(tool, rule, file)` 不在 `entries` 中 → 失败；输出 `tool rule file:line message`。
- **只减不增**：`--check-shrink <ref>` 读取 `<ref>` 上的基线文件。当前文件中存在 `<ref>` 上没有的条目即失败；条目数和清零进度写入工程基线说明。
- **已知局限**（写入工程基线说明）：某文件已登记的规则内再新增同类违规不会被拦截。
- 新文件（`<ref>` 上不存在的路径）不可能在基线里，因此新文件的任何违规都会阻断。

## 4. P3C → PMD 7 映射（`config/pmd/alibaba-p3c.xml`）

映射表维护在规则集文件的注释中，并在工程基线说明中汇总。下表是三组规则的**最低覆盖集**；实施时以 PMD 7.29.0 规则索引核对规则名，规则名不存在时 PMD 会报错。

| 手册分组 | P3C 规则（示例） | PMD 7 实现 |
| --- | --- | --- |
| 编码规约 · 命名 | ClassNamingShouldBeCamel、MethodNamingShouldBeLowerCamel、ConstantFieldShouldBeUpperCase、AbstractClassShouldStartWithAbstract | `ClassNamingConventions`、`MethodNamingConventions`、`FieldNamingConventions`、`LocalVariableNamingConventions`、`FormalParameterNamingConventions`（抽象类前缀规则记为缺口，不强制） |
| 编码规约 · 控制语句 / OOP | NeedBrace、SwitchStatement（必须 default）、EqualsAvoidNull、WrapperTypeEquality | `ControlStatementBraces`、`NonExhaustiveSwitch`、`CompareObjectsWithEquals`、`UseEqualsToCompareStrings` |
| 编码规约 · 日志 | 禁止 `System.out` / `printStackTrace` | `SystemPrintln`、`AvoidPrintStackTrace`（两处 CLI 契约输出登记为带理由的例外） |
| 并发 | ThreadPoolCreation、AvoidManuallyCreateThread、ThreadShouldSetName | 自定义 XPath 规则 `OryxThreadPoolCreation`：禁止 `Executors.newFixedThreadPool/newCachedThreadPool/newSingleThreadExecutor/newScheduledThreadPool/newSingleThreadScheduledExecutor/newWorkStealingPool`，允许 `newVirtualThreadPerTaskExecutor`、`newThreadPerTaskExecutor`；手动建线程、线程命名记为缺口 |
| 并发 | AvoidUseTimer、LockShouldWithTryFinally、DoubleCheckedLocking、static SimpleDateFormat | `DoubleCheckedLocking`、`NonThreadSafeSingleton`、`UnsynchronizedStaticFormatter`、`DontCallThreadRun`、`UseNotifyAllInsteadOfNotify`；Timer、Lock 释放用自定义 XPath 规则或记为缺口 |
| 异常处理 | 空 catch、捕获后不处理、finally 中 return、捕获 NPE、抛出原始类型异常 | `EmptyCatchBlock`、`ReturnFromFinallyBlock`、`DoNotThrowExceptionInFinally`、`AvoidCatchingNPE`、`AvoidCatchingThrowable`、`AvoidThrowingRawExceptionTypes`、`PreserveStackTrace` |

格式类 P3C 规则（缩进、空格、行长）一律**不纳入**，由 Google 格式负责（FR-012）。

## 5. CI 检查（GitHub Actions）

| 工作流文件 | 触发 | 作业名（分支保护必过项的准确名称） | 失败影响 |
| --- | --- | --- | --- |
| `.github/workflows/quality-gate.yml` | `pull_request`；除 `main` 外分支的 `push`；被部署工作流 `workflow_call` | **`Full Verify`** | 阻断合并；在部署链上阻断部署 |
| `.github/workflows/dependency-scan.yml` | `pull_request`；每日 `schedule`；`workflow_dispatch` | **`Dependency Scan`** | 阻断合并；不在部署链上 |
| `.github/workflows/deploy-tencent-cloud.yml` | `main` 推送（路径过滤增加 `config/**`、`tools/dev/**`、`.mvn/**`）、`workflow_dispatch` | 先调用 `Full Verify`，再执行 `Build, Verify & Deploy` | 门禁失败不进入部署 |

`Full Verify` 的步骤名与本地命令一一对应（FR-019）：`Format check`、`Full verify (tools/dev/mvnw clean verify)`、`Baseline must not grow`、`Upload quality reports`。
Checkstyle、PMD、SpotBugs 与测试在同一次 `verify` 中依次运行，报告作为工作流产物上传；失败时日志首行写明门禁编号（G1–G6）。合为一个步骤是已确认的偏差 D-6（见 [plan.md](../plan.md#偏差与需维护者确认)）。

## 6. 提交前钩子

| 项 | 约定 |
| --- | --- |
| 安装 | `./tools/dev/install-hooks` 执行 `git config core.hooksPath tools/dev/hooks`，只修改本仓库 `.git/config` |
| 执行内容 | `tools/dev/mvnw -q spotless:check pmd:pmd checkstyle:checkstyle`，然后对 PMD/Checkstyle 做基线判定 |
| 失败提示 | 打印 `./tools/dev/mvnw spotless:apply` 修复命令及违规列表 |
| 依赖 | 只需 `ORYXOS_JAVA_HOME` 与系统 `python3`（与 `tools/dev` 相同），不安装全局软件 |
| 跳过 | `git commit --no-verify` 可跳过；CI 仍会拦截 |
| 卸载 | `git config --unset core.hooksPath` |
