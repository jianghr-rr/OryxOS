# OryxOS 工程基线

本文说明仓库的质量门禁、日志与运维端点、CI 与分支保护，以及日常需要执行的命令。设计依据见 [specs/004-refactor-engineering-foundation](../specs/004-refactor-engineering-foundation/)，门禁契约见 [contracts/quality-gates.md](../specs/004-refactor-engineering-foundation/contracts/quality-gates.md)。

所有命令都在仓库根目录执行，并先在当前终端选择 JDK 21（不修改系统默认 Java）：

```bash
export ORYXOS_JAVA_HOME=/absolute/path/to/jdk-21/Contents/Home
```

## 1. 门禁清单

| # | 门禁 | 工具 | 规则与例外文件 | 阻断范围 |
| --- | --- | --- | --- | --- |
| G1 | 格式 | Spotless 3.12.0 + google-java-format 2.0.0 | 无例外 | 主代码与测试代码全量阻断 |
| G2 | Checkstyle | Checkstyle 14.4.0 | `config/checkstyle/checkstyle.xml`、`config/checkstyle/suppressions.xml` | 主代码中不在基线内的条目 |
| G3 | 编码规约（P3C 映射） | PMD 7.29.0 | `config/pmd/alibaba-p3c.xml`、`config/pmd/exclude-pmd.properties` | 主代码中不在基线内的条目；测试代码只报告 |
| G4 | 静态安全 | SpotBugs 4.10.5.0 + Find Security Bugs | `config/spotbugs/exclude.xml` | 主代码中不在基线内的条目 |
| G5 | 测试 | Surefire | `tools/dev/test-groups.json` | 已登记分组必须全部执行且通过 |
| G6 | 基线判定 | `tools/dev/quality-gate.py` | `config/quality/baseline.json` | 汇总 G2–G4 |
| G7 | 依赖漏洞 | dependency-check 13.0.0 | `config/dependency-check/suppressions.xml` | CVSS ≥ 7.0；只在 profile `dependency-scan` 中运行 |

- **扫描范围**：Spotless、Checkstyle、PMD 只处理各模块的 `src/main/java` 与 `src/test/java`；SpotBugs 只分析主代码的编译产物。`target/generated-sources` 等生成代码不在范围内。
- **测试代码**：格式全量阻断；Checkstyle 与 PMD 的发现只报告、不阻断。
- **不可绕过**：`tools/dev/mvnw` 会以退出码 2 拒绝 `-Dspotless.*`、`-Dcheckstyle.skip`、`-Dpmd.skip`、`-Dcpd.skip`、`-Dspotbugs.skip`、`-Ddependency-check.skip`、`-Dformat.skip`、`-DskipTests` 以及 `-P!<profile>` 形式的 profile 反激活。
- **失败输出**：首行为 `Quality gate FAIL: G2(checkstyle) <n> new, G3(pmd) <n> new, G4(spotbugs) <n> new` 形式的汇总，其后每行一条 `tool rule file:line message`。

## 2. 本地命令

| 用途 | 命令 |
| --- | --- |
| 完整校验（格式、规约、安全、测试、基线） | `./tools/dev/mvnw clean verify` |
| 自动格式化 | `./tools/dev/mvnw spotless:apply` |
| 只检查格式 | `./tools/dev/mvnw spotless:check` |
| 依赖漏洞扫描 | 先在当前 shell 中 `export NVD_API_KEY`（不要把 Key 写进文件或命令参数），再执行 `./tools/dev/mvnw -Pdependency-scan dependency-check:aggregate`；报告在 `target/dependency-check-report.{html,json}` |
| 检查基线只减不增 | `./tools/dev/quality-baseline --check-shrink origin/main` |
| 删除已清零的基线条目 | 先执行一次 `./tools/dev/mvnw clean verify`，再执行 `./tools/dev/quality-baseline --prune` |
| 安装提交前钩子 | `./tools/dev/install-hooks`（卸载：`git config --unset core.hooksPath`） |

提交前钩子只在暂存区包含 Java、`pom.xml` 或 `config/` 下的文件时运行。它执行 `./tools/dev/mvnw -q -B compile pmd:pmd checkstyle:checkstyle`，其中 `compile` 会先经过 `spotless:check`，然后对 PMD 与 Checkstyle 做基线判定。SpotBugs 与测试只在完整校验中运行。提交所在的终端必须已设置 `ORYXOS_JAVA_HOME`，否则钩子会拒绝提交。`git commit --no-verify` 可以跳过钩子，但 CI 仍会拦截。

在开着 Java 插件的编辑器里工作时，本地校验请带上 `clean`：编辑器的后台编译器可能把编译失败的 class 写进 `target/classes`，Maven 的增量构建会沿用它，导致测试报出 `NoClassDefFoundError`。

## 3. 例外与存量基线

**例外登记**：所有豁免都写在第 1 节列出的文件中，每条都必须带 `reason:` 与 `registered: YYYY-MM-DD`。XML 文件写在条目前的注释或 `<notes>` 里，properties 文件写在条目前的 `#` 注释里；缺少任一项时 `quality-gate.py` 判定失败。不在代码里用注解抑制，也不通过注释掉检查、关闭插件或命令参数绕过。

**存量基线**：`config/quality/baseline.json` 按 `(tool, rule, file)` 登记改造前已存在的违规，共 **460** 条（Checkstyle 271、PMD 161、SpotBugs 28），创建于提交 `5b288ba`。条目最多的规则：

| 条目数 | 工具 | 规则 |
| --- | --- | --- |
| 143 | checkstyle | `AvoidStarImportCheck` |
| 108 | checkstyle | `NeedBracesCheck` |
| 108 | pmd | `ControlStatementBraces` |
| 44 | pmd | `PreserveStackTrace` |
| 15 | checkstyle | `MultipleVariableDeclarationsCheck` |
| 12 | spotbugs | `REC_CATCH_EXCEPTION` |
| 10 | spotbugs | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` |

**清零计划**：基线只减不增，CI 的 `Baseline must not grow` 步骤会拦截新增条目。修改某个文件时顺手清掉该文件的条目，然后执行 `--prune`。建议优先处理机械性的星号导入与缺失花括号（占 359 条），再逐条处理 `PreserveStackTrace` 和 SpotBugs 条目。SpotBugs 的安全类发现已逐条分诊，见 [US1-security-triage.md](../specs/004-refactor-engineering-foundation/evidence/US1-security-triage.md)。

**依赖漏洞例外**：首次扫描（2026-11-23）报出的存量漏洞中，Tomcat、httpclient5、httpcore5、sqlite-jdbc、Log4j 已在根 `pom.xml` 升级；自有 CLI 模块被误配为 GitHub CLI、httpcore5 5.5 被误配为 5.5 预发布版，都登记为误报。Spring Framework 6.2.19 与 Spring AI 1.1.8 已是各自版本线的最后一版，其中 OryxOS 未使用受影响功能的 CVE 登记为已接受例外，范围锁定在这两个版本上，到 **2027-02-23** 失效，需要在此之前完成 Spring Boot 4 迁移或重新评估。逐条依据见 [US4-cve-triage.md](../specs/004-refactor-engineering-foundation/evidence/US4-cve-triage.md)。以后引入 WebFlux 服务端、SSE、SpEL 求值、Spring AI 的工具调度或文档读取时，必须先复查这些例外。

**已知局限**：判定粒度是“文件 + 规则”。如果某个文件已经登记了某条规则，在同一文件里再新增同类违规不会被拦截。新文件不可能出现在基线里，因此新文件的任何违规都会阻断。

## 4. P3C 规约的落地方式（偏差 D-1）

`oryxos-init` 原本指定 `com.alibaba.p3c:p3c-pmd` 规则集，但它的最新版本 2.1.1 绑定 PMD 6.15，无法解析本仓库的 Java 16+ 语法，也与 PMD 7 不兼容。因此改用 PMD 7 加仓库内的映射规则集 `config/pmd/alibaba-p3c.xml`。该取舍已于 2026-11-21 获维护者确认。

| 手册分组 | 实际启用的 PMD 7 规则 | 缺口 |
| --- | --- | --- |
| 命名 | `ClassNamingConventions`、`MethodNamingConventions`、`FieldNamingConventions`、`LocalVariableNamingConventions`、`FormalParameterNamingConventions` | 抽象类 `Abstract` 前缀不强制 |
| 控制语句 / OOP | `ControlStatementBraces`、`NonExhaustiveSwitch`、`CompareObjectsWithEquals`、`UseEqualsToCompareStrings` | — |
| 日志 | `SystemPrintln`、`AvoidPrintStackTrace` | 三处契约要求的 stderr/stdout 输出已在 `exclude-pmd.properties` 登记 |
| 并发 | 自定义 `OryxThreadPoolCreation`（禁止 `Executors.new*` 固定、缓存、单线程与调度线程池，允许虚拟线程执行器）、`DoubleCheckedLocking`、`NonThreadSafeSingleton`、`UnsynchronizedStaticFormatter`、`DontCallThreadRun`、`UseNotifyAllInsteadOfNotify` | 手动创建线程、线程命名、`Timer`、`Lock` 必须在 `finally` 中释放 |
| 异常处理 | `EmptyCatchBlock`、`ReturnFromFinallyBlock`、`DoNotThrowExceptionInFinally`、`AvoidCatchingGenericException`（限定 NPE 与 `Throwable`，替代 PMD 7 中已废弃的 `AvoidCatchingNPE`、`AvoidCatchingThrowable`）、`AvoidThrowingRawExceptionTypes`、`PreserveStackTrace` | — |

格式类 P3C 规则（缩进、空格、行长）不纳入，由 Google 格式负责。缺口部分建议在 IDE 中安装阿里巴巴编码规约插件作为补充。

## 5. 日志

| 形态 | 选择方式 | 输出 |
| --- | --- | --- |
| 开发（默认） | 未激活 `prod` profile | stderr 文本；终端支持时带颜色 |
| 生产 | `SPRING_PROFILES_ACTIVE=prod`（服务器由 systemd drop-in 设置） | stderr 与 `${ORYXOS_LOG_DIR}/oryxos.json`，每行一条 JSON |

两种形态都不写 stdout。CLI 命令自身的 stdout/stderr 输出属于命令行契约，不经过日志系统。生产 JSON 必有 `@timestamp`、`level`、`logger_name`、`thread_name`、`message`、`application`（固定为 `oryxos`）；HTTP 请求期间带 `traceId`，Agent 执行期间带 `executionId`，有异常时带 `stack_trace`。消息、异常栈和 MDC 值都经过与审计记录相同的脱敏。

文件滚动参数（生产形态）：

| 参数 | 默认值 | 环境变量 |
| --- | --- | --- |
| 目录 | `/var/log/oryxos` | `ORYXOS_LOG_DIR` |
| 单文件上限 | `50MB` | `ORYXOS_LOG_MAX_FILE_SIZE` |
| 保留天数 | `14` | `ORYXOS_LOG_MAX_HISTORY` |
| 目录总量上限 | `1GB` | `ORYXOS_LOG_TOTAL_SIZE_CAP` |

归档文件名为 `oryxos.<yyyy-MM-dd>.<n>.json.gz`。目录无法创建或不可写时服务照常启动，stderr 输出一行 `[oryxos-logging] WARN log directory not writable: <dir>`，只关闭文件日志。

**追踪**：上游请求头 `X-Request-ID` 匹配 `^[A-Za-z0-9._:-]{8,64}$` 时沿用，否则生成小写 UUID v4。该值写入响应信封的 `traceId`，并出现在该请求的全部日志中，包括异步派发、超时与执行完成回调。每个 `/api/v1/**` 请求结束时输出一条访问日志（记录器 `io.github.oryxlabs.oryxos.web.access`，消息 `http_request method=… path=… status=… durationMs=…`）。CLI 与定时执行没有 `traceId`，用 `executionId` 检索。

在服务器上按级别过滤 journal 中的 JSON 日志：

```bash
journalctl -u oryxos -o cat | grep '^{' | python3 -c 'import sys,json;[print(json.loads(l)["message"]) for l in sys.stdin if json.loads(l)["level"]=="ERROR"]'
```

## 6. 运维端点与公网屏蔽

运维端点与业务共用 `127.0.0.1:8080`，不出现在 `/v3/api-docs` 中。只暴露以下端点，`env`、`beans`、`heapdump`、`threaddump`、`metrics` 等一律不暴露。

| 端点 | 说明 |
| --- | --- |
| `/actuator/health` | 只返回总体状态与分组名，不含组件详情 |
| `/actuator/health/liveness` | 只有进程级失效才报 `DOWN` |
| `/actuator/health/readiness` | 与 `/api/v1/health` 同源；`PAUSED_RECORDING`、存储不可用或启动中时为 503 `OUT_OF_SERVICE` |
| `/actuator/info` | 应用名、版本、构建时间 |
| `/actuator/prometheus` | 所有指标都带 `application="oryxos"` |

发布与回滚脚本仍以 `/api/v1/health` 判断健康。公网入口（`deploy/tencent-cloud/nginx/oryxos-baota-vhost.conf.tpl`）对 `/actuator` 一律返回 403，`/grafana/` 与 `/prometheus/` 需要 Basic Auth，并向后端传递 `X-Request-ID $request_id`。腾讯云安全组与防火墙不得放行 9090、3300。服务器侧安装与访问见 [TencentCloudDeployment.md](TencentCloudDeployment.md#5-监控栈安装与访问)。

## 7. CI

| 工作流 | 触发 | 作业名 | 失败影响 |
| --- | --- | --- | --- |
| `.github/workflows/quality-gate.yml` | `pull_request`；`main` 以外分支的 `push`；被部署工作流调用 | **`Full Verify`** | 阻断合并；在部署链上阻断部署 |
| `.github/workflows/dependency-scan.yml` | `pull_request`；每日定时；手动触发 | **`Dependency Scan`** | 阻断合并；不在部署链上 |
| `.github/workflows/deploy-tencent-cloud.yml` | `main` 推送、手动触发 | 先调用 `Full Verify`，再执行发布 | 门禁失败不进入部署，发布使用门禁作业产出的 JAR |

`Full Verify` 的步骤依次为 `Format check`、`Full verify (tools/dev/mvnw clean verify)`、`Baseline must not grow`、`Upload quality reports`、`Upload boot JAR`。Checkstyle、PMD、SpotBugs 与测试都在 `Full verify` 这一个步骤里（偏差 D-6，已于 2026-11-21 获维护者确认）：本地与 CI 执行同一条命令且只编译一次，失败时看该步骤日志首行的门禁编号（`G2(checkstyle)`、`G3(pmd)`、`G4(spotbugs)`）即可区分原因，详细报告在 `quality-reports` 产物中。

`Dependency Scan` 需要仓库 Actions Secret `NVD_API_KEY`，缺失时作业直接失败。漏洞库缓存在 `.oryxos-dev/repository/org/owasp/dependency-check-data`，按日期作为缓存键。缓存的恢复和保存拆成两步，保存不论扫描成败都会执行：扫描只要发现漏洞就会失败，如果像 `actions/cache` 默认那样只在成功时保存，每次都要重新下载整个 NVD 漏洞库。NVD 不可用（例如返回 503）时，`failOnError=true` 会让作业失败，不会凭不完整的漏洞库通过。

## 8. 分支保护

在 GitHub 仓库页面执行：Settings → Branches → Add rule（或 Add branch ruleset），然后：

1. Branch name pattern 填 `main`。
2. 勾选 Require a pull request before merging。
3. 勾选 Require status checks to pass before merging，搜索并添加 `Full Verify` 与 `Dependency Scan`。两个检查必须在 GitHub 上至少运行过一次，才能被搜到。
4. 勾选 Do not allow bypassing the above settings，管理员也不例外。
5. 保存。之后直接 `git push origin main` 会被平台拒绝，所有改动都通过合并请求合入。

## 9. 运维注意事项

- 服务器 systemd 版本为 219。单元文件不能使用 `MemoryMax=`、`StateDirectory=`、`LogsDirectory=`、`ProtectSystem=strict`、`ReadWritePaths=`，脚本中不能用 `systemctl show --value`，改用 `systemctl show -p X | cut -d= -f2`。现有 `oryxos.service` 中的 `ProtectSystem=strict` 与 `ReadWritePaths=` 在 219 上实际不生效，需另行评估。
- 观察内存：

  ```bash
  for s in oryxos prometheus grafana; do systemctl show $s -p MemoryCurrent -p MemoryLimit; done
  docker stats --no-stream
  ps -o rss=,comm= -C dockerd
  ```

  Prometheus 限 256MB、Grafana 限 512MB（合计 768MB；Grafana 13 实测常驻超过 256MB，见 plan 偏差 D-7），`OOMScoreAdjust=500`，内存紧张时优先于 OryxOS 被回收。

## 10. `oryxos-init` Definition of Done 核对

| # | 检查项 | 结论 | 依据或原因 |
| --- | --- | --- | --- |
| 1 | 9 个 Maven 模块骨架，打包产出可执行 JAR | 已满足 | `./tools/dev/mvnw clean verify` 产出 `oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar`；统一通过 `tools/dev/mvnw`，不直接调用 `mvn` |
| 2 | 结构化日志（prod 为 JSON，含 traceId），无 `System.out` | 已满足 | 见第 5 节与 [US2-local.md](../specs/004-refactor-engineering-foundation/evidence/US2-local.md)；日志全部经 SLF4J。剩余三处 `System.out/err` 是 CLI 契约输出和日志目录告警，已在 `exclude-pmd.properties` 登记 |
| 3 | `/actuator/health`、`/info`、`/prometheus` 可访问 | 已满足 | `ActuatorEndpointsTest` 与 US2 手工验证 2.4、2.5 |
| 4 | 虚拟线程开启 | 已满足 | `application.yml` 中 `spring.threads.virtual.enabled: true`（改造前已存在） |
| 5 | springdoc 可用，统一 `ApiResponse` 与全局异常处理就位 | 已满足 | `ApiResponse` 与 `ApiExceptionHandler`（即 DoD 所说的 `GlobalExceptionHandler`，沿用现有类名）；`/v3/api-docs` 由 `PublishedOpenApiTest` 锁定零差异；`/swagger-ui.html` 的访问结果见 [US5-walkthrough.md](../specs/004-refactor-engineering-foundation/evidence/US5-walkthrough.md) |
| 6 | Spotless（Google 格式）、阿里 P3C、Checkstyle、`.editorconfig` 全部生效 | 已满足（P3C 按 D-1） | G1–G3；P3C 以 PMD 7 映射规则集落地，缺口见第 4 节；`.editorconfig` 位于仓库根目录 |
| 7 | SpotBugs、Find Security Bugs、PMD、OWASP Dependency-Check 接入 `mvn verify` | 部分满足 | SpotBugs、Find Security Bugs、PMD 在默认 `verify` 中运行。Dependency-Check 按契约 G7 只在 profile `dependency-scan` 与 CI 的 `Dependency Scan` 作业中运行：它需要联网下载漏洞库和 `NVD_API_KEY`，放进默认构建会让离线构建失败并显著拉长每次校验 |
| 8 | 提交前钩子与 CI 跑通，任一检查失败即阻断 | 部分满足（CI 待维护者操作） | 钩子已实测能拦截格式错误与新增 PMD/Checkstyle 违规，见 [US3-hook.md](../specs/004-refactor-engineering-foundation/evidence/US3-hook.md)。CI 工作流已就绪，但尚未在 GitHub 上运行：推送后 CI 标红与变绿（T062）、开启第 8 节的分支保护（T063）需要推送和修改仓库设置，须维护者授权 |
| 9 | 敏感配置全用 `${ENV_VAR}` 占位，无明文密钥 | 已满足 | 新增文件均使用环境变量或文件引用（`NVD_API_KEY` 来自 Actions Secret，Grafana 密码由安装脚本生成到服务器文件）；扫描结果见 [acceptance-summary.md](../specs/004-refactor-engineering-foundation/evidence/acceptance-summary.md) |
