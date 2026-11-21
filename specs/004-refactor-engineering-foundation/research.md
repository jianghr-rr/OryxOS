# Phase 0 Research: 工程地基重构

**日期**：2026-11-21 | **对应规格**：[spec.md](./spec.md) | **方案**：[plan.md](./plan.md)

本文件记录 Phase 0 的选型决策。版本号来自 2026-11-21 对 Maven Central `maven-metadata.xml` 与 GitHub Releases API 的实际查询；
兼容性结论分两类标注：**已核实**（查询到元数据或代码事实）与 **待实测**（须在实施首个任务中用 `tools/dev` 实跑确认，失败按本文回退方案处理）。
本次方案阶段未在本机执行 Maven 构建（本机未发现 JDK 21 标准安装位置），不得把下列"待实测"项写成已通过。

## 0. 现状事实（代码核对，已核实）

| 事实 | 依据 | 对方案的影响 |
| --- | --- | --- |
| 299 个主代码 Java 文件、85 个测试 Java 文件；71 个文件使用 `record`，3 个使用文本块，未使用 switch 模式匹配 / record 模式 / sealed | `rg` 统计 | 存量规模决定基线策略；PMD 6 能解析现有语法但不能解析后续 Java 21 新语法 |
| `oryxos-core` 只依赖 SnakeYAML，不依赖 SLF4J；全仓仅 `oryxos-boot` 有 4 处 `LoggerFactory` | `oryxos-core/pom.xml`、`rg org.slf4j` | FR-003 需给 core 增加 `slf4j-api`（仅门面） |
| 主代码 `System.out/err` 仅出现在 `OryxOsCli`（薄 JAR 版本入口）与 `OryxOsApplication.main`（把流交给 Picocli） | `rg System.(out|err)` | 两处都是 CLI 契约输出（FR-004 豁免），需登记为 P3C/PMD 例外而非改写 |
| `ApiResponse.of` 每次生成随机 UUID 作为 `traceId`；OpenAPI 中 `traceId` 为无格式约束的 `string` | `ApiResponse.java`、`contracts/http-api.json` | 改为取请求追踪标识不改变 schema；上游请求头值经校验后可直接沿用 |
| 业务调用经 `HttpExecutionAdapter` 的虚拟线程 `workers`、平台线程 `deadlines` 与 `AgentService` 的虚拟线程池回调构造响应 | `HttpExecutionAdapter.java`、`AgentService.java` | MDC 必须在三类线程切换点显式传递，否则响应 `traceId` 与日志不一致 |
| `Redactor` 是运行期按已解析密钥注册的实例，另含 URL 与 `key=value` 凭证正则 | `core/security/Redactor.java` | 日志脱敏必须复用同一个运行时实例，才能满足"与审计口径一致" |
| `logback-spring.xml` 仅一个 STDERR 文本 appender；`application.yml` 无 actuator 配置 | 资源文件 | 日志与运维端点为新增配置 |
| CI（`deploy-tencent-cloud.yml`）直接调用 `mvn verify` 并多排除了 `kernel-soak-harness` 分组；没有走 `tools/dev`，因此不经过测试报告门禁 | 工作流文件 | FR-019/FR-022 要求 CI 与本地同一入口，需让 CI 调用 `tools/dev/mvnw` |
| `tools/dev/build.py` 清空环境变量、禁止 `-DskipTests` 等参数、校验测试报告 runId | `build.py`、`check-test-reports.py` | 新检查接入该入口；需扩展禁止参数与报告校验 |
| 现有 `oryxos.service` 使用 `ProtectSystem=strict` 与 `ReadWritePaths=`，而服务器 systemd 为 219（这两项分别在 232/231 引入） | 服务器检查 + 单元文件 | 现有加固项在服务器上被忽略（范围外发现，见 plan）；新单元只能用 219 支持的指令 |

## 1. 结构化日志

**Decision**：沿用 Logback（Boot 3.5.16 管理 1.5.34），新增 `net.logstash.logback:logstash-logback-encoder:8.1` 输出生产 JSON；开发形态保留 Boot 彩色文本模式。
形态由 Spring profile `prod` 选择，服务器经 systemd drop-in 设置 `SPRING_PROFILES_ACTIVE=prod`。

**Rationale**：
- 已核实：8.1 是 8.x 最后版本；**9.0 依赖 `jackson-databind 3.0.1`**，与 Boot 3.5 的 Jackson 2.21.4 冲突，故锁 8.1。
- spec Assumptions 将 Logstash Logback Encoder 列为既定输入；其 `MaskingJsonGeneratorDecorator` + 自定义 `ValueMasker` 能对**所有字符串字段**（消息、异常栈、MDC）做脱敏，直接满足 FR-005。

**Alternatives considered**：
- Spring Boot 3.4+ 内置 `logging.structured.format.*`：无需新依赖，但脱敏需重写成员定制器、对异常栈字段控制更弱，且偏离 spec 既定输入。
- logstash-logback-encoder 9.0：Jackson 3，排除。

**关键设计（细节见 [contracts/observability.md](./contracts/observability.md)）**：
- 输出目标：两种形态都只写 **stderr**（保持 CLI stdout 契约）；`prod` 额外写文件 `${ORYXOS_LOG_DIR:-/var/log/oryxos}/oryxos.json`，`SizeAndTimeBasedRollingPolicy`：`maxFileSize=50MB`、`maxHistory=14`、`totalSizeCap=1GB`，三项及目录均可由环境变量覆盖。
- **日志目录不可写不得导致启动失败（待实测，高风险）**：Spring Boot 在 Logback 初始化状态中出现 ERROR 时会抛 `IllegalStateException` 终止启动，而 `FileAppender` 打不开文件会记 ERROR。决定新增 `GuardedRollingFileAppender`（`oryxos-boot`）：`start()` 先检查目录可创建且可写，不满足时只记 WARN 状态、向 stderr 打一行明确告警并保持未启动，不产生 ERROR 状态；运行期磁盘写满由 Logback 自身记录错误、不影响进程。
- 脱敏：`LogRedaction` 静态持有者在 Spring 容器内拿到运行时 `Redactor` 后注册；注册前使用无密钥的 `new Redactor()`（仅 URL 与凭证正则）。JSON 形态通过 `ValueMasker`，文本形态通过自定义 `%redactedMessage` / `%redactedException` 转换器。
- `SC-003` 的度量对象是**日志文件**：journal 中除 JSON 外仍会出现 CLI 契约行（如 `SERVE: ...` 提示、启动期 `CONFIGURATION_ERROR` 诊断）与 JVM 自身警告，它们按 FR-004 不得改成 JSON。

## 2. 请求追踪标识

**Decision**：`oryxos-web` 新增最高优先级 `TraceIdFilter`：读取请求头 `X-Request-ID`，满足 `^[A-Za-z0-9._:-]{8,64}$` 时沿用，否则生成 UUID v4；写入 MDC 键 `traceId`，请求结束时清除。
`ApiResponse.of` 改为取 `RequestTrace.currentOrNew()`（读 MDC，缺失时生成新 UUID 以兼容非 HTTP 调用）。

**Rationale**：
- 已核实：`traceId` 在 OpenAPI 中是无格式 `string`，沿用合法上游值不改变 schema；格式白名单防止日志注入。
- `HttpExecutionAdapter` 在请求线程捕获 MDC 快照，用 `MdcContext.wrap(...)` 包装 `deadlines.schedule`、`workers.execute` 及 `whenFinished` 回调；`AgentService` 在执行任务内设置 `executionId`（FR-003），任务结束 `finally` 清除。
- nginx 参考片段增加 `proxy_set_header X-Request-ID $request_id;`，使 nginx 访问日志与应用日志可关联。
- 现有代码在成功请求、甚至大多数业务错误路径上都不写日志，因此单靠 MDC 无法保证"用 `traceId` 检索必然命中"。决定由同一个过滤器在 `/api/v1/**` 请求结束时写一条 INFO 访问日志（异步请求通过 `AsyncListener` 在完成时记录），`ApiExceptionHandler.unexpected` 以 ERROR 记录未预期异常。`/actuator/**` 与 `/admin/**` 不记，避免采集请求刷屏。

**Alternatives considered**：Micrometer Tracing + Brave/OTel 自动传播——引入分布式追踪属于 spec 明确不做的范围；且对手写的 `AsyncContext`/执行器回调仍需手动传播。

## 3. 健康与指标

**Decision**：`oryxos-boot` 增加 `spring-boot-starter-actuator` 与 `micrometer-registry-prometheus`（版本由 Boot BOM 管理：Micrometer 1.15.12、Prometheus client 1.3.10，已核实）。
暴露 `health`、`info`、`prometheus` 三个端点，与业务共用 `127.0.0.1:8080`；`management.endpoint.health.probes.enabled=true`；公共标签 `application=oryxos`。

**Rationale**：
- 就绪判定复用 `/api/v1/health` 的同一来源：新增 `OryxRuntimeHealthIndicator`（bean 名 `oryxRuntime`）读取 `WebRuntimePort.health().ready()`，未就绪返回 `OUT_OF_SERVICE`；readiness 组为 `readinessState,oryxRuntime`，liveness 组只含 `livenessState`。
- springdoc 默认 `show-actuator=false`，运维端点不进入已发布 OpenAPI；由现有 `PublishedOpenApiTest`（19 操作 / 44 schema）兜底（FR-009）。
- `show-details: never`，避免向本机以外泄露组件细节；公网经 nginx 对 `/actuator` 返回 403（FR-008）。
- HTTP 指标：`HttpExecutionAdapter` 手动 `startAsync`，`ServerHttpObservationFilter` 会在异步完成时记录（**待实测**，由新测试覆盖 `http.server.requests` 计数）。

**Alternatives considered**：独立管理端口——spec Assumptions 已确定不引入。

## 4. 代码格式

**Decision**：`com.diffplug.spotless:spotless-maven-plugin:3.12.0` + `google-java-format:2.0.0`（GOOGLE 风格；P0 实测由 1.37.0 调整，见下）；覆盖全部模块 `src/main/java` 与 `src/test/java`；`check` 绑定 `verify` 阶段之前的 `process-sources`，`apply` 为自动修复命令。新增 `.mvn/jvm.config` 写入 google-java-format 在 JDK 16+ 所需的 `--add-exports jdk.compiler/...=ALL-UNNAMED`。

**Rationale**：
- 已核实：Spotless 3.12.0 是最新版，前置 Maven ≥3.1.0（Wrapper 为 3.9.16）。
- ~~选 1.37.0 而非 2.0.0~~ **P0 实测后改为 2.0.0**（2026-11-21，见 [evidence/P0-compat.md](./evidence/P0-compat.md)）：Spotless 3.12.0 的胶水按 google-java-format 2.x API 编译，配 1.37.0 时每个文件都抛 `NoSuchMethodError`（`Style` 在 1.37.0 中改成了 record）。2.0.0 同样以 Java 21 为目标，在 JDK 21 上配合 `.mvn/jvm.config` 可以运行。`removeUnusedImports` 不再单独配置，`googleJavaFormat` 步骤已包含删除未使用 import 与排序。以后升级 = 一次性重新格式化提交。
- 不使用 `oryxos-init` 片段中的 `<importOrder/>`：google-java-format 本身按 Google 风格排序导入，叠加 Spotless 默认 importOrder 可能产生互相矛盾的要求（FR-013 要求不得矛盾）。
- 全仓一次性格式化放在**独立提交**，并新增 `.git-blame-ignore-revs` 记录该提交，保留 `git blame` 可读性。

**Alternatives considered**：Spotless `ratchetFrom`（只格式化改动文件）——与已确认的 FR-014"格式全量阻断"冲突。

## 5. 编码规约（P3C）——本方案最大偏差（已于 2026-11-21 由维护者确认采用映射规则集）

**已核实的不兼容**：`com.alibaba.p3c:p3c-pmd` 最新仍为 **2.1.1**，其 POM 绑定 **PMD 6.15.0**。PMD 6.15 无法解析 record、switch 表达式等 Java 16+ 语法（本仓 71 个文件使用 record）；PMD 7（`maven-pmd-plugin` 3.22+，当前 3.28.0 / PMD 7.29.0）与 P3C 的 PMD 6 规则 API 不兼容。

**Decision**：PMD 7 + 仓库内规则集 `config/pmd/alibaba-p3c.xml`。该规则集按阿里手册的**编码规约、并发、异常处理**三组，把 P3C 规则逐条映射到 PMD 7 内置规则；缺少内置等价的高价值规则用规则集内的 XPath 规则实现；仍无法实现的规则在映射表中标为"缺口"。映射表见 [contracts/quality-gates.md](./contracts/quality-gates.md)。
`maven-pmd-plugin:3.28.0`，显式覆盖 `pmd-java`/`pmd-core` 为 7.29.0，`targetJdk=21`。规则名不存在时 PMD 7 会直接报错，所以规则集本身能自我校验。

**Rationale**：PMD 7 持续维护、支持 Java 21；FR-012 要求的是"基于阿里手册、至少三组规则"，映射规则集满足要求且不锁死语言特性。开发者仍可在 IDE 安装阿里规约插件做即时提示（非门禁）。

**Alternatives considered**：
- A. `maven-pmd-plugin 3.21.2`（最后的 PMD 6 版本）+ 强制 PMD 6.55.0 + p3c-pmd 2.1.1：能保留官方规则原文，现有代码大概率可解析；但 PMD 6 已停止维护，P3C 规则针对 6.15 API 编译、在 6.55 上**是否全部可运行未验证**；而且一旦代码用上 Java 21 新语法（模式匹配 switch 等），整条门禁就会因解析失败而中断，等于用质量工具限制语言基线。**拒绝**，作为回退方案记录。
- B. 社区移植版 p3c（PMD 7）：未在 Maven Central 找到维护中的坐标，拒绝。

## 6. Checkstyle

**Decision**：`maven-checkstyle-plugin:3.6.0`，显式依赖 `com.puppycrawl.tools:checkstyle:14.4.0`（其 POM 的 `java.version` 为 21，已核实与 JDK 21 匹配）。规则文件 `config/checkstyle/checkstyle.xml` 以 14.4.0 自带的 `google_checks.xml` 为底：
- 移除与 google-java-format 职责重叠、可能产生矛盾的格式模块（`Indentation`、`OperatorWrap`、`SeparatorWrap`、空白与换行类、`CustomImportOrder`）——格式由 Spotless 唯一负责；
- 保留命名、结构与可靠性模块（如 `OuterTypeFilename`、`OneTopLevelClass`、`MissingSwitchDefault`、`FallThrough`、`EmptyCatchBlock`、命名类模块），严重级别 `warning`；
- Javadoc 类模块降为 `info`，只出报告、不阻断（避免每个新文件被迫写 Javadoc；严重级别显式配置，满足 FR-013）。

**Alternatives considered**：原样使用 `google_checks.xml`——其 `Indentation` 等模块与 google-java-format 在 lambda/switch 处存在已知分歧，会产生"格式化后仍报错"的矛盾。

## 7. 静态安全扫描

**Decision**：`spotbugs-maven-plugin:4.10.5.0`（SpotBugs 4.10.5）+ `findsecbugs-plugin:1.14.0`；`effort=Max`、`threshold=Medium`；只分析主代码；排除文件 `config/spotbugs/exclude.xml` 每条带理由。

**Rationale**：
- `threshold=Low` 在 300 个文件上噪声过大；Find Security Bugs 的注入、路径穿越、反序列化、XXE、弱加密等检测器默认为 Medium 及以上优先级。
- 确定的设计内告警（例如 `shell` 工具的命令执行、文件工具的路径访问——均已由 `WhitelistSandbox` 约束）登记为**带理由的抑制**；其余存量告警进入基线，并逐步清零。两者分开，避免把存量违规写成永久例外（FR-014、FR-018）。
- `EI_EXPOSE_REP/EI_EXPOSE_REP2` 在 record 与不可变副本上大量误报，全局排除并在排除文件中写明理由。

## 8. "只阻断新增/修改代码"的基线机制（FR-014）

**Decision**：Maven 中 PMD、Checkstyle、SpotBugs 均设置 `failOnViolation=false`，只生成 XML 报告；由 `tools/dev` 在 Maven 成功后调用新的 `tools/dev/quality-gate.py` 统一判定：

1. 解析各模块 `target/pmd.xml`、`target/checkstyle-result.xml`、`target/spotbugsXml.xml`，归一化成 `(tool, rule, 仓库相对路径)` 三元组；测试代码路径（`src/test/`）只报告。
2. 与 `config/quality/baseline.json` 比较：出现基线中没有的三元组 → 失败，并输出规则、文件、行号；基线中已消失的条目 → 提示运行 `tools/dev/quality-baseline --prune` 删除（只允许删除）。
3. CI 额外执行 `quality-baseline --check-shrink <base-ref>`：与合并目标分支的基线比较，**新增任何条目即失败**（"只减不增"）。
4. 报告新鲜度：运行前删除所选模块的旧报告；`verify` 后期望的报告缺失即失败（与测试报告门禁一致，fail-closed）。

**Rationale**：同时满足"文件 + 规则"粒度、只减不增，以及已确认的已知局限（同一文件已登记规则内新增同类违规不被拦截）；Python 实现与现有 `check-test-reports.py` 同构，不需要自写 Maven 插件。

**Alternatives considered**：各插件原生 baseline（PMD 无原生基线；Checkstyle 有 suppressions；SpotBugs 有 baseline 文件）——三套格式与粒度不一致，"只减不增"也无法统一校验。

**影响**：直接执行 `./mvnw verify` 只能得到格式检查与测试结果，不会执行基线判定；**唯一受支持的完整校验入口是 `tools/dev/mvnw verify`**（FR-022），CI 与提交前钩子都走这个入口。

## 9. 依赖漏洞扫描

**Decision**：`org.owasp:dependency-check-maven:13.0.0`，只放在 profile `dependency-scan` 中（不进默认 `verify`，满足 FR-017）；执行 `aggregate` 目标；`failBuildOnCVSS=7`；抑制文件 `config/dependency-check/suppressions.xml`；输出 HTML + JSON 报告；关闭本项目用不到、且需要额外凭证或联网的分析器（OSS Index、RetireJS、Node Audit）。
NVD API Key 通过 `nvdApiKeyEnvironmentVariable=NVD_API_KEY` 从环境读取，不出现在命令参数中。

**Rationale / 待实测**：
- `tools/dev/build.py` 默认清空环境变量，需在命令含 `-Pdependency-scan` 时**只放行 `NVD_API_KEY`**（与已有 `real-provider` 放行模式一致）。
- 漏洞库缓存在 `.oryxos-dev/repository/org/owasp/dependency-check-data`，CI 用 `actions/cache` 按日期作为 key；漏洞库更新失败时必须让作业失败（`failOnError=true`），不能用旧缓存静默通过——**待实测**断网行为，如插件在有旧缓存时只给警告，则在工作流中检查更新日志并显式失败。
- 维护者已于 2026-11-21 把 `NVD_API_KEY` 配置为仓库级 Actions Secret（网页方式，已确认存在）。

## 10. CI、合入流程与提交前钩子

**Decision**：
- 新增 `.github/workflows/quality-gate.yml`：触发条件为 `pull_request`、除 `main` 外分支的 `push`，以及 `workflow_call`；单个作业 **`Full Verify`**，运行 `ORYXOS_JAVA_HOME=$JAVA_HOME ./tools/dev/mvnw -B -ntp clean verify`，CI 中再执行一次 `quality-baseline --check-shrink`。
- 新增 `.github/workflows/dependency-scan.yml`：触发条件为 `pull_request`、每日 `schedule`、`workflow_dispatch`；作业 **`Dependency Scan`**。
- `deploy-tencent-cloud.yml` 改为先 `uses: ./.github/workflows/quality-gate.yml` 再部署。部署作业复用门禁作业上传的 boot JAR 产物，不重复构建，原有发布步骤语义不变（FR-020）。依赖扫描不在部署链上。
- CI 的 Maven 镜像与重试：`tools/dev` 禁止 `-s` 覆盖，因此新增仓库内 `tools/dev/maven-settings-ci.xml`（保留现有 GCS 镜像与 HTTP 重试）。`build.py` 仅在 `GITHUB_ACTIONS=true` 时选用它，本地依旧直连 Central。
- 分支保护：必过检查为 `Full Verify` 与 `Dependency Scan`，禁止直接推送 main；仓库内只提供开启步骤（FR-019a）。

**Rationale / 风险**：CI 改走 `tools/dev` 后，会执行原先 CI 排除、但本地会跑的 `kernel-soak-harness` 本地分组，使 CI 与本地一致；它对 CI 时长的影响**待实测**并写入基线说明。

**提交前钩子**：`tools/dev/hooks/pre-commit` + `tools/dev/install-hooks`（执行 `git config core.hooksPath tools/dev/hooks`，只改本仓库 `.git/config`）。钩子运行 `tools/dev/mvnw -q spotless:check pmd:pmd checkstyle:checkstyle`，再对 PMD/Checkstyle 跑基线判定（SpotBugs 需要编译产物，不放进钩子）。缺少 `ORYXOS_JAVA_HOME` 时给出提示并拒绝提交；`git commit --no-verify` 可以跳过，但 CI 仍会拦截（FR-021）。

## 11. 服务器监控栈

**输入**：2026-11-21 服务器只读检查及维护者完成的前置整治，结论原文见 [plan.md §服务器约束](./plan.md#服务器约束2026-11-21-检查)。

**Decision**：
- 版本：**Prometheus 3.13.5**（3.13 是当前仍在发补丁的 LTS 线；3.16.0 为最新普通版）、**Grafana 13.3.1**（最新稳定版）。二者均为官方 linux-amd64 tar 包，版本号与 sha256 锁定在 `deploy/tencent-cloud/monitoring/versions.env`。
- 安装：一次性幂等脚本 `deploy/tencent-cloud/scripts/install-monitoring.sh --dist-dir <目录>`。安装包由维护者在本机下载后 `scp` 到服务器，脚本只做离线校验和安装。原因：服务器直连 GitHub 不可用（10 秒超时）；**TUNA github-release 镜像只保留最新版**（已核实目录只有 `3.16.0` 与 `LatestRelease`），无法提供锁定版本。
- 运行：systemd 219 单元，使用 `MemoryAccounting=yes` + `MemoryLimit=256M`（各一份，合计 512MB）、`Environment=GOMEMLIMIT=200MiB`、`OOMScoreAdjust=500`、`ProtectSystem=full`、`NoNewPrivileges=yes`；不使用 `MemoryMax=`、`StateDirectory=`、`ProtectSystem=strict`、`ReadWritePaths=`。
- 监听：Prometheus `127.0.0.1:9090`，Grafana `127.0.0.1:3300`（3000/3001/3010/3011 已被 Docker 容器占用）。
- Prometheus：每 15 秒采集一次 `127.0.0.1:8080/actuator/prometheus` 与自身指标；`--storage.tsdb.retention.time=15d` + `--storage.tsdb.retention.size=1GB`；不开启 admin API 与 lifecycle API。
- Grafana：`auth.anonymous.enabled=false`、`users.allow_sign_up=false`、`auth.basic.enabled=false`（避免 nginx Basic Auth 头被 Grafana 当作登录凭证）；管理员密码 `admin_password = $__file{/etc/oryxos-monitoring/secrets/grafana-admin-password}`（0640 root:grafana，安装脚本生成随机值，不打印、不入库）；关闭统一告警、更新检查、使用统计与插件预装，以降低内存占用并避免外联。
- 子路径访问：同一 nginx 虚拟主机下的 `/grafana/` 与 `/prometheus/`。公网域名只写在服务器受控的 `/etc/oryxos-monitoring/monitoring.env`（`GF_SERVER_ROOT_URL`、`PROMETHEUS_EXTERNAL_URL`），不入库（FR-025）。

**待实测**：Grafana 13.3.1 二进制能否在 glibc 2.17 上运行（安装脚本先执行 `grafana --version` 预检，失败即中止并给出原因）；Prometheus 子路径参数组合（`--web.external-url` + `--web.route-prefix=/prometheus`）在 3.13 UI 下是否正常；两者实际常驻内存（SC-009）。

**Alternatives considered**：yum 安装 Grafana（`yum update` 可能意外升级，也会与 Prometheus 的安装方式不统一）；Docker（FR-020a 要求 systemd 管理，且不应与 dockerd 生命周期耦合）；在服务器上用镜像在线下载（固定版本不可得）。

## 12. 服务器侧配置随发布同步（FR-020a）

**Decision**：仓库新增 `deploy/tencent-cloud/server-config/`（监控配置、Grafana provisioning 与看板、`oryxos.service.d/10-observability.conf`），由部署工作流随发布包一起上传，由新脚本 `sync-server-config.sh` 分两段同步：

1. **OryxOS drop-in 段**：在 `deploy-release.sh` 启动新版本**之前**执行。原子写入（临时文件 + `mv`）、保留 `.prev`；只有内容变化时才执行 `daemon-reload`。失败时恢复 `.prev` 并让发布失败，此时新版本尚未启动，旧版本照常拉起（沿用既有回滚语义）。
2. **监控段**：在 OryxOS 健康检查通过**之后**执行。内容按 sha256 寻址，复制到 `/opt/oryxos/monitoring-config/<hash>/`，先用 `promtool check config` 校验，再原子切换 `current` 符号链接，并只重启内容有变化的服务。失败时把链接切回原目标、本次发布以非零码失败，但**不回滚已通过健康检查的 OryxOS**（FR-020a："监控组件同步失败不得影响 OryxOS 主服务的回滚判定"）。保留最近 3 份。

**Rationale**：符号链接切换保证不会出现新旧配置混用；分段执行保证监控问题不会触发主服务回滚。发布结束时写出 `server-config.sha256` 清单，SC-007 逐文件比对即可。

**Alternatives considered**：每次发布都无条件重启监控服务（不满足"仅在内容变化时重载"）；用 rsync 直接覆盖 `/etc`（中途失败会留下新旧混合配置）。
