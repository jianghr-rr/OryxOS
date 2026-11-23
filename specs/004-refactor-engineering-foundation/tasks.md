# Tasks: 工程地基重构（日志、监控、规范与安全门禁）

**Input**: Design documents from `specs/004-refactor-engineering-foundation/`

**Prerequisites**: [plan.md](./plan.md)、[spec.md](./spec.md)、[research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/](./contracts/)、[quickstart.md](./quickstart.md)

**Tests**: 规格每个用户故事都定义了 Independent Test，宪章 VI 要求测试覆盖风险，因此包含测试任务。Java 测试登记到 `tools/dev/test-groups.json` 的新分组 `f004-observability`；门禁脚本测试沿用 `tools/dev/test_*.py` 的写法。

**Organization**: 按用户故事分组。需要授权的动作（提交、推送、开启分支保护、服务器安装、修改 nginx、合入 main）在任务中标注 **【需授权】**，由维护者执行或明确授权后执行。

## Format: `[ID] [P?] [Story] Description`

- **[P]**：可以并行（不同文件，且不依赖未完成的任务）
- **[Story]**：所属用户故事（US1–US5）
- 证据统一写入 `specs/004-refactor-engineering-foundation/evidence/`

---

## Phase 1: Setup（共享准备）

**Purpose**：测出重构前的基线，锁定版本，建立配置目录骨架。

- [X] T001 在当前检出（未做任何 004 改动）上执行 `time ./tools/dev/mvnw clean verify`，把耗时 T0、通过的测试数和 JDK 版本写入 `specs/004-refactor-engineering-foundation/evidence/P0-baseline.md`（SC-001 的对照基准）
- [X] T002 在 `pom.xml` 的 `<properties>` 中新增锁定版本：`spotless.version=3.12.0`、`google-java-format.version=2.0.0`（P0 实测由 1.37.0 调整）、`checkstyle-plugin.version=3.6.0`、`checkstyle.version=14.4.0`、`pmd-plugin.version=3.28.0`、`pmd.version=7.29.0`、`spotbugs-plugin.version=4.10.5.0`、`findsecbugs.version=1.14.0`、`dependency-check.version=13.0.0`、`logstash-logback-encoder.version=8.1`，并在 `<dependencyManagement>` 中加入 `net.logstash.logback:logstash-logback-encoder:${logstash-logback-encoder.version}`
- [X] T003 [P] 新建 `.editorconfig`：`root=true`；所有文件 `charset=utf-8`、`end_of_line=lf`、`insert_final_newline=true`、`trim_trailing_whitespace=true`；`*.java` 为 `indent_style=space`、`indent_size=2`、`max_line_length=100`；`*.{xml,yml,yaml,json}` 缩进 2；`*.py` 缩进 4；`*.md` 不去行尾空白
- [X] T004 [P] 新建 `.mvn/jvm.config`，写入 google-java-format 在 JDK 16+ 进程内运行所需的 `--add-exports jdk.compiler/com.sun.tools.javac.{api,code,file,parser,tree,util}=ALL-UNNAMED`（google-java-format 2.0.0 同样需要）
- [X] T005 [P] 新建空白配置骨架：`config/checkstyle/`、`config/pmd/`、`config/spotbugs/`、`config/dependency-check/`、`config/quality/`，每个目录放一个只含用途说明的 `README.md`（实施注：合并为一份 `config/quality/README.md`，统一说明各目录用途与例外登记规则）

---

## Phase 2: Foundational（阻塞性前置）

**Purpose**：扩展 `tools/dev` 统一入口与基线判定。US1、US3、US4 都依赖它。

**⚠️ CRITICAL**：本阶段完成前不得开始用户故事阶段。

- [X] T006 [P] 先写测试 `tools/dev/test_quality_gate.py`（与 `tools/dev/test_report_gate.py` 同样写法）：用临时目录中的 PMD、Checkstyle、SpotBugs XML 样例覆盖以下情形——
  - 新三元组 `(tool, rule, file)` 导致失败，输出 `tool rule file:line message`；
  - 已在基线中的三元组通过；
  - `src/test/` 路径只报告、不阻断；
  - 期望报告缺失时失败（fail-closed）；
  - 抑制文件中缺少 `reason` 或 `registered` 的条目导致失败；
  - `--check-shrink` 发现比 `<ref>` 多出的条目时失败；
  - `--prune` 只删除不再出现的条目；
  - Checkstyle 中 severity 为 `info` 的新条目（如 Javadoc 类）只报告、不阻断，`warning` 及以上才阻断；
  - `--tools pmd,checkstyle` 只判定指定工具，此时缺少 SpotBugs 报告不算失败
- [X] T007 实现 `tools/dev/quality-gate.py`：按 [data-model.md §2](./data-model.md#2-violation违规报告归一化结果) 归一化，`rule` 取 Checkstyle `source` 的最后一段、PMD `rule`、SpotBugs `type`；`file` 统一为仓库相对路径并用 `/` 分隔；按 [contracts/quality-gates.md §3](./contracts/quality-gates.md#3-基线文件格式configqualitybaselinejson) 判定；Checkstyle 只有 severity≥`warning` 的条目参与阻断（契约 G2），`info` 条目归入报告；支持 `--tools <list>` 只判定指定工具（供提交前钩子使用）；失败输出首行写明门禁编号（G2–G4，偏差 D-6）；检查四个抑制文件中每条都有 `reason` 与 `registered`；成功输出 `Quality gate PASS: <n> baseline entries, <m> report-only findings`（依赖 T006）
- [X] T008 实现 `tools/dev/quality-baseline`（bash 包装）及其 Python 主体：`--init` 根据当前报告生成 `config/quality/baseline.json`（`schema:1`、`createdAt`、`baseCommit`、按 `tool,file,rule` 排序且去重的 `entries`，只收 `scope=main`）；`--prune` 只删除条目；`--check-shrink <ref>` 用 `git show <ref>:config/quality/baseline.json` 对比，`<ref>` 上没有该文件时视为空基线（依赖 T007）
- [X] T009 修改 `tools/dev/build.py`：
  1. 在 `forbidden` 中加入 `-Dspotless.`、`-Dcheckstyle.skip`、`-Dpmd.skip`、`-Dcpd.skip`、`-Dspotbugs.skip`、`-Ddependency-check.skip`、`-Dformat.skip`；拒绝 `-P` 参数中以 `!` 或 `-` 开头的 profile；
  2. 当命令含 `verify`、`install` 或 `package` 时（实施注：只对 `verify`/`install` 判定；`package` 不经过 `verify` 阶段，不会生成分析报告，判定会因报告缺失必然失败），在 Maven 运行前删除所选模块的 `target/pmd.xml`、`target/checkstyle-result.xml`、`target/spotbugsXml.xml`，在 Maven 成功且测试证据通过后调用 `quality-gate.py`；
  3. 仅当参数含 `-Pdependency-scan` 时，从当前环境放行 `NVD_API_KEY`；
  4. 当 `GITHUB_ACTIONS=true` 时改用 `tools/dev/maven-settings-ci.xml`；

  （依赖 T007）
- [X] T010 [P] 新建 `tools/dev/maven-settings-ci.xml`：从 `.github/workflows/deploy-tencent-cloud.yml` 中现有的内联 settings 迁移 GCS Central 镜像 profile 与 `aether.transport.http.retryHandler.*` 重试属性；文件不含凭证
- [X] T011 在 `tools/dev/test_build_environment.py` 中补充用例：新增的禁止参数均以退出码 2 拒绝；`-P!x` 被拒绝；不带 `-Pdependency-scan` 时 `NVD_API_KEY` 不进入子进程环境；`GITHUB_ACTIONS=true` 时选用 CI settings（依赖 T009、T010）

**Checkpoint**：`python3 -m unittest tools/dev/test_quality_gate.py tools/dev/test_build_environment.py` 通过；`./tools/dev/mvnw clean verify` 在尚无质量报告时仍按原样通过（门禁判定此时只针对已配置插件）。

---

## Phase 3: User Story 1 - 开发者本地一条命令复现全部质量门禁 (Priority: P1) 🎯 MVP

**Goal**：`./tools/dev/mvnw clean verify` 同时完成格式、Checkstyle、P3C 映射规约、SpotBugs 安全扫描、测试与基线判定；`spotless:apply` 能自动修复格式。

**Independent Test**：[quickstart.md §1](./quickstart.md#1-us1本地一条命令复现全部门禁) 的 1.1–1.6 全部符合。

### 门禁配置

- [X] T012 [US1] 在 `pom.xml` 的 `<build><plugins>` 中加入 Spotless：`googleJavaFormat` 版本为 `${google-java-format.version}`、`style=GOOGLE`，（实施注：`removeUnusedImports` 未单独配置，`googleJavaFormat` 步骤已包含，见 P0-compat）加 `removeUnusedImports`，**不加** `importOrder`；`includes` 为 `src/main/java/**/*.java`、`src/test/java/**/*.java`；`check` 目标绑定 `process-sources`（依赖 T002、T004）
- [X] T013 [P] [US1] 新建 `config/checkstyle/checkstyle.xml`：以 Checkstyle 14.4.0 自带的 `google_checks.xml` 为底，删除 `Indentation`、`OperatorWrap`、`SeparatorWrap`、`CustomImportOrder`、`WhitespaceAround`、`WhitespaceAfter`、`NoWhitespaceBefore`、`ParenPad`、`MethodParamPad`、`LineLength`；保留命名、`OuterTypeFilename`、`OneTopLevelClass`、`MissingSwitchDefault`、`FallThrough`、`EmptyCatchBlock`，severity 为 `warning`；Javadoc 类模块 severity 为 `info`；文件顶部注释说明"格式由 Spotless 唯一负责"
- [X] T014 [P] [US1] 新建 `config/checkstyle/suppressions.xml`（初始为空，注释写明每条必须带 `reason` 与 `registered`）
- [X] T015 [P] [US1] 新建 `config/pmd/alibaba-p3c.xml`：PMD 7 规则集，按 [contracts/quality-gates.md §4](./contracts/quality-gates.md#4-p3c--pmd-7-映射configpmdalibaba-p3cxml) 引用编码规约、并发、异常处理三组内置规则。新增 XPath 规则 `OryxThreadPoolCreation`：禁止 `Executors.newFixedThreadPool/newCachedThreadPool/newSingleThreadExecutor/newScheduledThreadPool/newSingleThreadScheduledExecutor/newWorkStealingPool`，允许 `newVirtualThreadPerTaskExecutor`、`newThreadPerTaskExecutor`。每条规则前用注释写出对应的 P3C 规则名，无法实现的列在文件末尾的"缺口"注释中
- [X] T016 [P] [US1] 新建 `config/pmd/exclude-pmd.properties`：登记 `io.github.oryxlabs.oryxos.cli.OryxOsCli=SystemPrintln` 与 `io.github.oryxlabs.oryxos.boot.OryxOsApplication=SystemPrintln`，理由为"CLI 契约输出（FR-004）"，并写 `registered=2026-11-21`
- [X] T017 [P] [US1] 新建 `config/spotbugs/exclude.xml`：全局排除 `EI_EXPOSE_REP`、`EI_EXPOSE_REP2`，理由为"record 与不可变副本误报"；文件头注释说明设计内的安全告警只能在 T022 分类后逐条登记
- [X] T018 [US1] 在 `pom.xml` 中加入 maven-checkstyle-plugin（依赖 `checkstyle:${checkstyle.version}`，`configLocation=config/checkstyle/checkstyle.xml`，`suppressionsLocation`，`includeTestSourceDirectory=true`，`failOnViolation=false`，`check` 绑定 `verify`，输出 `target/checkstyle-result.xml`）、maven-pmd-plugin（覆盖 `pmd-core`/`pmd-java` 为 `${pmd.version}`，`rulesets=config/pmd/alibaba-p3c.xml`，`excludeFromFailureFile`，`targetJdk=21`，`includeTests=true`，`failOnViolation=false`，`format=xml`，`pmd` 绑定 `verify`）、spotbugs-maven-plugin（`findsecbugs-plugin:${findsecbugs.version}`，`effort=Max`，`threshold=Medium`，`excludeFilterFile=config/spotbugs/exclude.xml`，`failOnError=false`，`xmlOutput=true`，`check` 绑定 `verify`）。四个插件都只扫描 `src/main/java` 与 `src/test/java`，显式不扫描 `target/generated-sources`，并在 `config/quality/README.md` 中写明这一排除范围（spec 边界情况“生成代码”）（依赖 T013–T017）

### 兼容性实测（research 中标为“待实测”的项）

- [X] T019 [US1] 实测并把结果写入 `specs/004-refactor-engineering-foundation/evidence/P0-compat.md`：
  - `./tools/dev/mvnw spotless:check` 在 JDK 21 上能运行；
  - PMD 7.29.0 能加载 `alibaba-p3c.xml`，没有未知规则名；
  - SpotBugs 能在九个模块上完成扫描；
  - 记录三者各自的耗时。

  任一失败时按 [research.md](./research.md) 中的回退方案修正，并在证据中写明（依赖 T018）

### 存量处理

- [X] T020 [US1] 执行 `./tools/dev/mvnw spotless:apply` 全仓格式化，然后跑 `./tools/dev/mvnw clean verify` 确认测试全绿；**【需授权】** 作为独立提交“style: apply google-java-format”，不夹带其他改动（依赖 T019）
- [X] T021 [US1] 新建 `.git-blame-ignore-revs`，写入 T020 提交的完整 SHA 并注释用途
- [X] T022 [US1] 对 SpotBugs 报告做安全告警分类：设计内的正确行为（例如 `shell` 工具的命令执行、文件工具受 `WhitelistSandbox` 约束的路径访问）逐条登记到 `config/spotbugs/exclude.xml`，限定到类与 bug pattern，附 `reason` 与 `registered`；其余进入基线。分类表写入 `evidence/US1-security-triage.md`（依赖 T020）
- [X] T023 [US1] 执行 `./tools/dev/quality-baseline --init` 生成 `config/quality/baseline.json`，按工具和规则统计存量条目数，写入 `evidence/US1-baseline.md`（依赖 T022）

### 验证

- [X] T024 [US1] 按 [quickstart.md §1](./quickstart.md#1-us1本地一条命令复现全部门禁) 执行 1.1–1.6：在临时文件 `GateProbe.java` 中注入格式错误、`EmptyCatchBlock`、`COMMAND_INJECTION`，确认它们分别被拦截并定位到行；验证 `-Dspotbugs.skip=true` 被拒绝；计算耗时与 T0 的差值（SC-001 要求 ≤ 3 分钟），结果写入 `evidence/US1-gates.md`，最后删除探针文件（依赖 T023）

**Checkpoint**：US1 可以单独交付。此时干净检出执行 `tools/dev/mvnw clean verify` 全部通过，且新违规会被拦截。

---

## Phase 4: User Story 2 - 运维在已部署服务器上获得结构化日志与可观测端点 (Priority: P1)

**Goal**：生产形态输出 JSON 日志（同时写 stderr 和滚动文件，并做脱敏），响应 `traceId` 与日志一致，提供 Actuator 运维端点，服务器部署 Prometheus + Grafana。

**Independent Test**：[quickstart.md §2](./quickstart.md#2-us2日志追踪与运维端点本地) 与分组 `f004-observability` 通过；服务器部分见 [quickstart.md §5](./quickstart.md#5-服务器监控栈与配置同步) 的 5.1–5.6。

### 测试先行（先写，确认失败）

- [X] T025 [P] [US2] 新建 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/observability/ProductionLogFormatTest.java`：在 `prod` profile 与临时 `ORYXOS_LOG_DIR` 下启动上下文，读取 `oryxos.json` 的前 100 行，每行都能被 Jackson 解析，且含 `@timestamp level logger_name thread_name message application`，其中 `application=oryxos`；不带 `prod` 时目录中不产生文件
- [X] T026 [P] [US2] 新建 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/observability/TraceCorrelationTest.java`：
  - 用真实 HTTP 发起 10 个请求，覆盖普通查询、经 `HttpExecutionAdapter` 的 Agent 调用（可控假 Provider）和 60 秒超时路径（缩短为测试时钟），每个响应的 `traceId` 都能在日志中找到对应的 `http_request` 访问日志行，且 10 个互不相同；
  - `X-Request-ID: probe-abcdef12` 会被沿用；不匹配 `^[A-Za-z0-9._:-]{8,64}$` 的值会被替换为新的 UUID v4；
  - 请求结束后各工作线程的 MDC 中不残留 `traceId` 或 `executionId`；
  - 不经 HTTP、直接通过 `AgentService` 触发一次执行（模拟定时与 CLI 入口，使用可控假 Provider），该执行期间的日志行都带有 `executionId`，且值等于持久化的执行 ID（FR-003）
- [X] T027 [P] [US2] 新建 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/observability/LogRedactionTest.java`：已注册的密钥、`https://` URL、`token=xxx` 分别出现在日志消息、异常消息、异常栈和 MDC 值中时，JSON 与文本两种形态都输出 `[REDACTED]` 或 `[REDACTED_URL]`，与 `Redactor` 的结果一致
- [X] T028 [P] [US2] 新建 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/observability/GuardedFileAppenderTest.java`：`ORYXOS_LOG_DIR` 指向不可写目录时上下文正常启动，stderr 含 `[oryxos-logging] WARN log directory not writable:`；`ORYXOS_LOG_MAX_FILE_SIZE`、`ORYXOS_LOG_MAX_HISTORY`、`ORYXOS_LOG_TOTAL_SIZE_CAP` 能覆盖默认值 `50MB`、`14`、`1GB`
- [X] T029 [P] [US2] 新建 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/observability/ActuatorEndpointsTest.java`：
  - `/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness` 均返回 200 且 `UP`；
  - 运行时处于 `PAUSED_RECORDING` 时 readiness 返回 503 `OUT_OF_SERVICE`，且与 `/api/v1/health` 的 503 一致；
  - `/actuator/prometheus` 含 `jvm_memory_used_bytes`、`jvm_threads_live_threads`、`process_uptime_seconds`，并且有带 `application="oryxos"` 标签的 `http_server_requests_seconds_count`（包含一次异步执行请求）；
  - `/actuator/info` 返回 200，且含 `build.version`（来自 `build-info`）；
  - `/actuator/env`、`/actuator/beans`、`/actuator/heapdump` 返回 404；
  - `/v3/api-docs` 中不含 `actuator`
- [X] T030 [US2] 在 `tools/dev/test-groups.json` 中新增 local 分组 `f004-observability`，登记 T025–T029 的类与方法（依赖 T025–T029）

### 实现：core 与 web

- [X] T031 [P] [US2] `oryxos-core/pom.xml` 新增 `org.slf4j:slf4j-api`（版本由 Boot BOM 管理）；新建 `oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/observability/MdcContext.java`，提供 `capture()` 快照，以及在执行期间恢复、结束后清除的 `wrap(Runnable)`、`wrap(Callable)`、`wrap(Consumer)`、`wrap(BiConsumer)`
- [X] T032 [US2] 修改 `oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/agent/AgentService.java`：`workers.execute(...)` 提交的任务用 `MdcContext.wrap` 包装；任务内设置 MDC `executionId`（复用现有执行 ID），在 `finally` 中移除（FR-003）（依赖 T031）
- [X] T033 [P] [US2] 新建 `oryxos-web/src/main/java/io/github/oryxlabs/oryxos/web/observability/RequestTrace.java`（`currentOrNew()`：读取 MDC `traceId`，缺失时返回新的 UUID v4 小写字符串）与 `TraceIdFilter.java`（最高优先级 `OncePerRequestFilter`）：
  - 校验 `X-Request-ID`，匹配 `^[A-Za-z0-9._:-]{8,64}$` 时沿用，否则生成新值；写入 MDC `traceId`；
  - `/api/v1/**` 请求结束时（异步请求通过 `AsyncListener` 在完成时）由 logger `io.github.oryxlabs.oryxos.web.access` 以 INFO 输出 `http_request method=<M> path=<不含查询串> status=<s> durationMs=<ms>`；
  - 跳过 `/actuator/**` 与 `/admin/**`；
  - 在 `finally` 中清除 MDC
- [X] T034 [US2] 修改 `oryxos-web/src/main/java/io/github/oryxlabs/oryxos/web/api/ApiResponse.java`：`traceId` 改为取 `RequestTrace.currentOrNew()`，record 字段与签名不变；修改 `ApiExceptionHandler.java`：`unexpected(Exception)` 用 SLF4J 以 ERROR 记录（异常信息交给日志脱敏），响应体不变（依赖 T033）
- [X] T035 [US2] 修改 `oryxos-web/src/main/java/io/github/oryxlabs/oryxos/web/execution/HttpExecutionAdapter.java`：在 `execute` 的请求线程上执行 `MdcContext.capture()`，然后用它包装 `deadlines.schedule(...)` 的超时任务、`workers.execute(...)` 的派发任务和 `whenFinished(...)` 回调，覆盖 [data-model.md §5](./data-model.md#5-tracecontext请求追踪上下文) 列出的全部传递点（依赖 T031、T033）
- [X] T036 [US2] 在 `oryxos-boot` 中把 `TraceIdFilter` 注册为最高优先级过滤器，位于现有 `HttpExecutionAdapter.ingress()` 之前；注册位置在现有 `oryxos-boot/src/main/java/io/github/oryxlabs/oryxos/boot/config/WebApiConfiguration.java`（依赖 T033）

### 实现：boot 日志与 actuator

- [X] T037 [US2] `oryxos-boot/pom.xml` 新增 `spring-boot-starter-actuator`、`micrometer-registry-prometheus`、`net.logstash.logback:logstash-logback-encoder`；`spring-boot-maven-plugin` 增加 `build-info` 目标（依赖 T002）
- [X] T038 [P] [US2] 新建 `oryxos-boot/src/main/java/io/github/oryxlabs/oryxos/boot/observability/LogRedaction.java`（静态持有运行时 `Redactor`，注册前使用 `new Redactor()`）、`RedactingValueMasker.java`（实现 logstash `ValueMasker`，对所有字符串值调用 `LogRedaction.redact`）、`RedactingConverters.java`（`%redactedMessage`、`%redactedException` 与 `%redactedMdc` 三个 Logback 转换器；`%redactedMdc` 用于文本形态中的 MDC 值脱敏，异常转换器内部类名为 `Throwable`）、`LogRedactionRegistrar.java`（Spring bean，容器启动时注册 `Redactor`）
- [X] T039 [P] [US2] 新建 `oryxos-boot/src/main/java/io/github/oryxlabs/oryxos/boot/observability/GuardedRollingFileAppender.java`：继承 `RollingFileAppender`，`start()` 先检查目录可以创建且可写；不满足时只记 WARN 状态，向 `System.err` 输出 `[oryxos-logging] WARN log directory not writable: <dir>`，不启动，且不产生 ERROR 状态
- [X] T040 [US2] 重写 `oryxos-boot/src/main/resources/logback-spring.xml`（依赖 T037–T039）：
  - `springProfile name="!prod"`：stderr 彩色文本，pattern 使用 `%redactedMessage` 与 `%redactedException`；
  - `springProfile name="prod"`：stderr 使用 `LogstashEncoder`，加 `customFields {"application":"oryxos"}` 与 `MaskingJsonGeneratorDecorator(RedactingValueMasker)`；另有一个 `GuardedRollingFileAppender`，文件为 `${ORYXOS_LOG_DIR:-/var/log/oryxos}/oryxos.json`，滚动策略为 `SizeAndTimeBasedRollingPolicy`，`fileNamePattern=oryxos.%d{yyyy-MM-dd}.%i.json.gz`，参数 `maxFileSize=${ORYXOS_LOG_MAX_FILE_SIZE:-50MB}`、`maxHistory=${ORYXOS_LOG_MAX_HISTORY:-14}`、`totalSizeCap=${ORYXOS_LOG_TOTAL_SIZE_CAP:-1GB}`；
  - 两种形态都不写 stdout；不输出调用者信息与主机名
- [X] T041 [P] [US2] 新建 `oryxos-boot/src/main/java/io/github/oryxlabs/oryxos/boot/observability/OryxRuntimeHealthIndicator.java`：bean 名 `oryxRuntime`，`WebRuntimePort.health().ready()` 为 true 时返回 `UP`，否则返回 `OUT_OF_SERVICE`，不输出详情
- [X] T042 [US2] 修改 `oryxos-boot/src/main/resources/application.yml`（依赖 T037、T041）：
  - `management.endpoints.web.exposure.include=health,info,prometheus`；
  - `management.endpoint.health.probes.enabled=true`、`show-details=never`、`show-components=never`；
  - `management.endpoint.health.group.readiness.include=readinessState,oryxRuntime`、`liveness.include=livenessState`；
  - `management.metrics.tags.application=oryxos`；
  - `management.info.build.enabled=true`；
  - `spring.output.ansi.enabled=detect`
- [X] T043 [US2] 执行 `./tools/dev/mvnw -pl oryxos-boot -am test -Dgroups=f004-observability,us5-published-contract,f0-foundation`，确认 T025–T029、OpenAPI 零差异（19 操作 / 44 schema）和 CLI 入口契约都通过；再按 [quickstart.md §2](./quickstart.md#2-us2日志追踪与运维端点本地) 手工执行 2.1–2.9，结果写入 `evidence/US2-local.md`（依赖 T030–T042）

### 实现：服务器侧（监控栈与日志持久化）

- [X] T044 [P] [US2] 新建 `deploy/tencent-cloud/monitoring/versions.env`：`PROMETHEUS_VERSION=3.13.5`、`GRAFANA_VERSION=13.3.1`，两个 `*_SHA256` 从官方发布的校验和文件中取值（Prometheus 取 `sha256sums.txt` 中 linux-amd64 那一行，Grafana 取 `dl.grafana.com` 上对应的 `.sha256`），并注释来源 URL
- [X] T045 [P] [US2] 新建 `deploy/tencent-cloud/server-config/systemd/oryxos.service.d/10-observability.conf`：只含 `Environment=SPRING_PROFILES_ACTIVE=prod` 与 `Environment=ORYXOS_LOG_DIR=/var/log/oryxos`
- [X] T046 [P] [US2] 新建 `deploy/tencent-cloud/server-config/systemd/prometheus.service` 与 `grafana.service`，按 [contracts/server-config.md §3](./contracts/server-config.md#3-systemd-单元要点systemd-219-兼容) 编写：
  - 加 `MemoryAccounting=yes`、`MemoryLimit=256M`、`Environment=GOMEMLIMIT=200MiB`（实施注：Grafana 实测后改为 `512M` / `400MiB`，见 plan D-7）、`EnvironmentFile=-/etc/oryxos-monitoring/monitoring.env`、`OOMScoreAdjust=500`、`NoNewPrivileges=yes`、`ProtectSystem=full`、`ProtectHome=yes`、`PrivateTmp=yes`、`Restart=on-failure`；
  - 不得出现 `MemoryMax=`、`StateDirectory=`、`LogsDirectory=`、`ProtectSystem=strict`、`ReadWritePaths=`；
  - Prometheus 启动参数为 `--web.listen-address=127.0.0.1:9090 --web.route-prefix=/prometheus --web.external-url=${PROMETHEUS_EXTERNAL_URL} --storage.tsdb.path=/var/lib/prometheus --storage.tsdb.retention.time=15d --storage.tsdb.retention.size=1GB`，配置文件指向 `/opt/oryxos/monitoring-config/current/prometheus/prometheus.yml`
- [X] T047 [P] [US2] 新建 `deploy/tencent-cloud/server-config/monitoring/prometheus/prometheus.yml`：`scrape_interval: 15s`；job `oryxos` 采集 `127.0.0.1:8080`、`metrics_path: /actuator/prometheus`；job `prometheus` 采集 `127.0.0.1:9090`、`metrics_path: /prometheus/metrics`；不写告警规则
- [X] T048 [P] [US2] 新建 `deploy/tencent-cloud/server-config/monitoring/grafana/grafana.ini`：
  - `[server] http_addr=127.0.0.1`、`http_port=3300`、`serve_from_sub_path=true`，`root_url` 从环境变量 `GF_SERVER_ROOT_URL` 读取；
  - `[security] admin_password=$__file{/etc/oryxos-monitoring/secrets/grafana-admin-password}`；
  - `[auth.anonymous] enabled=false`、`[users] allow_sign_up=false`、`[auth.basic] enabled=false`、`[unified_alerting] enabled=false`；
  - `[analytics] reporting_enabled=false`、`check_for_updates=false`、`[plugins] preinstall_disabled=true`；
  - `[paths] provisioning=/opt/oryxos/monitoring-config/current/grafana/provisioning`、`data=/var/lib/grafana`
- [X] T049 [P] [US2] 新建 `deploy/tencent-cloud/server-config/monitoring/grafana/provisioning/datasources/prometheus.yaml`（数据源 URL 为 `http://127.0.0.1:9090/prometheus`，`isDefault: true`，`editable: false`）与 `provisioning/dashboards/oryxos.yaml`（文件型 provider，指向 `/opt/oryxos/monitoring-config/current/grafana/dashboards`）
- [X] T050 [P] [US2] 新建看板 `deploy/tencent-cloud/server-config/monitoring/grafana/dashboards/oryxos-jvm-http.json`，标题 `OryxOS JVM & HTTP`，`uid` 固定；面板包括：JVM 堆和非堆内存、GC 暂停次数与耗时、存活线程数、按 `uri` 与 `status` 分组的 HTTP 请求速率、HTTP p50/p95 耗时、5xx 错误率、进程运行时长；默认时间范围 24 小时，`$__rate_interval` ≤ 1 分钟；查询都带 `application="oryxos"` 过滤
- [X] T051 [US2] 新建 `deploy/tencent-cloud/scripts/sync-server-config.sh`，按 [contracts/server-config.md §6](./contracts/server-config.md#6-发布时同步-sync-server-configsh) 实现两个阶段（依赖 T045–T050）：
  - `oryxos-dropin`：sha256 比对，用临时文件加 `mv` 原子替换并保留 `.prev`（本次为新建时记录“新建”标记），变化时执行 `daemon-reload`，失败时恢复；
  - `oryxos-dropin --restore`：撤销最近一次 drop-in 同步，有 `.prev` 时恢复 `.prev`，本次为新建时删除该 drop-in，然后 `daemon-reload`；供 `deploy-release.sh` 的失败与自动回滚路径使用；
  - `monitoring`：按内容 hash 复制到 `/opt/oryxos/monitoring-config/<hash>/`，执行 `promtool check config`，原子切换 `current` 链接，单元文件变化时 `daemon-reload`，只重启有变化的服务；失败时把链接切回原目标并重启受影响的服务；保留最近 3 份；单元尚未安装时只同步文件并输出 `monitoring not installed; skipped restart`；
  - 每次结束都写出 `/opt/oryxos/monitoring-config/server-config.sha256`；
  - 根路径可用环境变量 `ORYXOS_SYNC_ROOT` 覆盖，用于测试；
  - 不使用 `systemctl show --value`，兼容 bash 4.2
- [X] T052 [US2] 新建 `tools/dev/test_sync_server_config.py`：在 `ORYXOS_SYNC_ROOT` 临时根目录下用桩 `systemctl` 与桩 `promtool` 覆盖以下情形——内容未变化时不重启；只改看板时只重启 grafana；`promtool` 失败时链接回到原目标且退出码非零；drop-in 失败时恢复 `.prev`；单元未安装时跳过重启；`oryxos-dropin --restore` 在有 `.prev` 时恢复 `.prev`、本次为新建时删除该 drop-in，两种情况都执行 `daemon-reload`（依赖 T051）
- [X] T053 [US2] 新建 `deploy/tencent-cloud/scripts/install-monitoring.sh`，按 [contracts/server-config.md §5](./contracts/server-config.md#5-一次性安装脚本-install-monitoringsh) 的 8 个步骤实现，每步都能重复执行；对 `127.0.0.1:9090` 与 `127.0.0.1:3300` 做端口预检；按 `versions.env` 校验 sha256；用 `grafana --version` 与 `prometheus --version` 做 glibc 预检；密码文件只生成一次，只打印路径不打印值；`monitoring.env` 缺失时写占位文件并提示；不修改 nginx、防火墙、Docker 和 `oryxos.service`（依赖 T044、T051）
- [X] T054 [P] [US2] 修改 `deploy/tencent-cloud/nginx/oryxos-baota-vhost.conf.tpl`：新增 `location ~ ^/actuator(/|$) { return 403; }`；在 `location /` 中加入 `proxy_set_header X-Request-ID $request_id;`；按 [contracts/server-config.md §4](./contracts/server-config.md#4-nginx-参考片段) 新增 `/grafana/`（含 `proxy_set_header Authorization "";`）与 `/prometheus/`，二者都启用 Basic Auth；顶部注释说明 9090 与 3300 不得放行
- [ ] T055 [US2] 【需授权】在服务器上首次安装：维护者在本机下载并核对两个 tar 包，`scp` 到服务器，填写 `/etc/oryxos-monitoring/monitoring.env`，执行 `install-monitoring.sh`，并在宝塔面板中应用 T054 的 nginx 片段；按 [quickstart.md §5](./quickstart.md#5-服务器监控栈与配置同步) 的 5.1–5.6 验收，观察 24 小时后再记录一次 `MemoryCurrent`（SC-009），结果写入 `evidence/US2-server.md`（主机名和 IP 脱敏）（依赖 T053、T054，以及 US3 中 T063 部署出带 actuator 的新版本）
  - 进度（2026-11-23）：安装与 nginx 已完成，5.1、5.4 通过，5.5 看板已预配置并可登录查看，Grafana 上限按 D-7 放宽；5.3 `oryxos` 目标、5.5 数据曲线、5.6 日志文件与 24 小时内存复测待新版本部署后完成

**Checkpoint**：本地可观测性完整可验证（T043）；服务器部分依赖 US3 的部署链交付新版本后完成 T055。

---

## Phase 5: User Story 3 - 合并与提交前自动强制执行门禁 (Priority: P2)

**Goal**：PR 和非 main 分支推送自动执行与本地一致的 `Full Verify`；部署工作流先过门禁再发布，并同步服务器侧配置；main 分支受保护；提供提交前钩子。

**Independent Test**：[quickstart.md §3](./quickstart.md#3-us3ci分支保护与提交前钩子) 的 3.1–3.5，以及 §5 的 5.7–5.9。

- [X] T056 [P] [US3] 新建 `.github/workflows/quality-gate.yml`（依赖 Phase 2、US1）：
  - 触发：`pull_request`、`push`（`branches-ignore: [main]`）、`workflow_call`（输出 boot JAR 产物名）；
  - 作业名 **`Full Verify`**，步骤依次为：`actions/checkout`（`fetch-depth: 0`）、`setup-java` Temurin 21、`actions/cache` 缓存 `.oryxos-dev/repository`、`Format check`（`./tools/dev/mvnw -B -ntp spotless:check`）、`Full verify (tools/dev/mvnw clean verify)`、`Baseline must not grow`（PR 时对 `origin/${{ github.base_ref }}`，否则对 `origin/main`）、`Upload quality reports`、上传 `oryxos-boot/target/oryxos-boot-*.jar`；
  - 环境变量 `ORYXOS_JAVA_HOME: ${{ env.JAVA_HOME }}`；`permissions: contents: read`
- [X] T057 [US3] 修改 `.github/workflows/deploy-tencent-cloud.yml`（依赖 T056、T051）：
  - 新增作业 `quality`：`uses: ./.github/workflows/quality-gate.yml`；`build-and-deploy` 设置 `needs: quality`，下载门禁上传的 JAR，替换原来的“Build and run regression suite”步骤，“Prepare release artifact & checksum”及之后的步骤语义不变；
  - 删除内联的 Maven settings 步骤（已迁到 `tools/dev/maven-settings-ci.xml`）；
  - `paths` 增加 `config/**`、`tools/dev/**`、`.mvn/**`、`.editorconfig`；
  - 发布包新增 `server-config/` 目录（rsync `deploy/tencent-cloud/server-config/` 到 `/tmp/oryxos-release/server-config/`），上传到远端 release 目录；同时把 `sync-server-config.sh` 上传并安装到 `/opt/oryxos/bin/`
- [X] T058 [US3] 修改 `deploy/tencent-cloud/scripts/deploy-release.sh`（依赖 T051）：
  1. 把现有“5. Start new version”失败分支中的回滚步骤（停服务、`current` 切回 `previous`、`sync_config_if_present`、启动、健康检查）抽成函数 `auto_rollback`，行为不变；
  2. 在“4.6 Synchronize managed runtime.yaml”之后、“5. Start new version”之前，当 `${RELEASE_DIR}/server-config` 存在时调用 `sync-server-config.sh oryxos-dropin`。失败时**不得直接 `fail`**（此时旧服务已停、`current` 已指向新版本）：先执行 `oryxos-dropin --restore`，再调用 `auto_rollback`，最后 `exit 1`；
  3. 在 `auto_rollback` 中、启动回滚目标之前处理 drop-in：回滚目标含 `server-config/` 时用它执行 `oryxos-dropin`；不含时执行 `oryxos-dropin --restore`，确保回滚后不残留新版本的 drop-in（FR-020a“不得留下新旧混合配置”）；
  4. 在健康检查通过并 `print_profiles_summary` 之后调用 `sync-server-config.sh monitoring`，失败时记录 `monitoring sync failed; OryxOS release kept` 并以非零码退出，**不进入** `auto_rollback`；
  5. 没有 `server-config/` 的旧发布包，第 2、4 步都跳过。

  在 `tools/dev/test_sync_server_config.py` 中用桩 `systemctl` 补两个用例：drop-in 同步失败时最终 `current` 指回旧版本且旧服务被重新启动；新版本健康检查失败、回滚目标不含 `server-config/` 时，drop-in 恢复为同步前的状态
- [X] T059 [P] [US3] 修改 `deploy/tencent-cloud/scripts/rollback.sh`：回滚目标中存在 `server-config/` 时先执行 `oryxos-dropin` 阶段再启动；不触碰监控配置
- [X] T060 [P] [US3] 新建 `tools/dev/hooks/pre-commit`（bash）：执行 `tools/dev/mvnw -q spotless:check pmd:pmd checkstyle:checkstyle`（实施注：改为 `compile pmd:pmd checkstyle:checkstyle`，单独 `pmd:pmd` 无法解析 reactor 兄弟模块；`compile` 已经过 `spotless:check`，见 P0-compat），再用 `quality-gate.py --tools pmd,checkstyle` 判定；`ORYXOS_JAVA_HOME` 缺失时提示后以退出码 1 拒绝；失败时打印 `./tools/dev/mvnw spotless:apply`。新建 `tools/dev/install-hooks`：执行 `git config core.hooksPath tools/dev/hooks`，并打印卸载命令 `git config --unset core.hooksPath`（`quality-gate.py` 需支持 `--tools` 参数）
- [X] T061 [US3] 执行 `./tools/dev/install-hooks`，提交一次格式不合规的改动，确认被拒绝，并记录钩子耗时（目标 ≤ 60 秒）；然后撤销该改动，结果写入 `evidence/US3-hook.md`（依赖 T060）
- [ ] T062 [US3] 【需授权】把 US1–US3 的改动推送到功能分支：先推送一个故意不合规的提交，确认 `Full Verify` 标红且失败步骤名可辨；修复后变绿；记录 CI 总时长（包含原先被排除的 `kernel-soak-harness` 分组），超过 45 分钟时向维护者报告，不擅自调整分组；结果写入 `evidence/US3-ci.md`
  - 进度（2026-11-23）：功能分支已推送，`Full Verify` 通过，约 15.7 分钟；故意不合规提交的标红验证未执行
- [ ] T063 [US3] 先在 `docs/EngineeringBaseline.md` 中写出“分支保护”一节（GitHub Settings → Branches → Add rule → `main` → Require status checks: `Full Verify`、`Dependency Scan` → Do not allow bypassing；其余章节由 T069 补齐）。随后 **【需授权】** 按该节开启 main 保护（禁止直接推送；两个检查在 GitHub 上至少运行过一次才可选为必过项），通过 PR 合入，确认部署工作流先跑 `quality` 再发布、健康检查通过、`/var/log/oryxos/oryxos.json` 开始写入；按 [quickstart.md §5](./quickstart.md#5-服务器监控栈与配置同步) 执行 5.7–5.9，结果写入 `evidence/US3-deploy.md`（依赖 T057、T058、T062、T066、T068；监控部分依赖 T055）
  - 进度（2026-11-23）：“分支保护”一节已写入 `docs/EngineeringBaseline.md` 第 8 节；开启保护、合入与服务器验证待授权，本任务保持未完成

**Checkpoint**：门禁由机器强制执行；合入 main 的提交会经过门禁、发布并同步服务器侧配置。

---

## Phase 6: User Story 4 - 第三方依赖已知漏洞持续扫描 (Priority: P2)

**Goal**：独立的依赖漏洞扫描作业，超过阈值时阻断合并，不影响默认校验和部署。

**Independent Test**：[quickstart.md §4](./quickstart.md#4-us4依赖漏洞扫描) 的 4.1–4.4。

- [X] T064 [P] [US4] 新建 `config/dependency-check/suppressions.xml`：官方 schema，初始为空；注释要求每条 `<suppress>` 的 `<notes>` 写明理由与登记日期
- [X] T065 [US4] 在 `pom.xml` 中新增 profile `dependency-scan`：引入 `dependency-check-maven:${dependency-check.version}`，配置 `failBuildOnCVSS=7`、`suppressionFiles=config/dependency-check/suppressions.xml`、`formats=HTML,JSON`、`nvdApiKeyEnvironmentVariable=NVD_API_KEY`、`ossindexAnalyzerEnabled=false`、`retireJsAnalyzerEnabled=false`、`nodeAuditAnalyzerEnabled=false`、`nodeAnalyzerEnabled=false`、`failOnError=true`；默认构建不包含该插件（依赖 T002、T064）
- [X] T066 [US4] 新建 `.github/workflows/dependency-scan.yml`：触发为 `pull_request`、`schedule`（每日一次，UTC 时间）、`workflow_dispatch`；作业名 **`Dependency Scan`**；`actions/cache` 缓存 `.oryxos-dev/repository/org/owasp/dependency-check-data`，key 含日期；步骤 `Dependency scan (tools/dev/mvnw -Pdependency-scan dependency-check:aggregate)`，环境变量 `NVD_API_KEY: ${{ secrets.NVD_API_KEY }}`、`ORYXOS_JAVA_HOME`；`NVD_API_KEY` 为空时第一步就失败并说明原因；报告作为产物上传；不被部署工作流引用（依赖 T065）
- [ ] T067 [US4] 实测漏洞库不可达时的行为：在本地断开 NVD 访问，或在 `workflow_dispatch` 中使用无效的 Key 运行；若插件凭旧缓存静默通过，则在 T066 中增加一步，检查数据库更新日志并显式失败。结果写入 `evidence/US4-offline.md`（依赖 T066）
  - 进度（2026-11-23）：CI 上真实遇到 NVD 返回 503，作业失败并给出原因，没有静默通过（见 `evidence/US4-cve-triage.md`）；“已有旧缓存、更新失败”的情况待验证
- [X] T068 [US4] 【需授权】在验证分支给 `oryxos-boot/pom.xml` 临时加入一个带已知高危 CVE 的旧依赖，发起 PR，确认 `Dependency Scan` 失败并列出坐标、CVE 编号与 CVSS；移除后通过；登记一条带理由的示例抑制并验证生效；结果写入 `evidence/US4-scan.md`，验证分支不合入（依赖 T066）
  - 进度（2026-11-23）：验证分支 `004-verify-cve-scan`（PR #1）加入 commons-collections 3.2.1，`Dependency Scan` 失败并列出坐标、CVE-2015-6420 与 9.8 分；抑制条目已在 CI 上生效（Spring、Spring AI、CLI 误报不再报出）；不含该依赖的 PR #2 扫描通过（运行 45127674169）。记录写在 `evidence/US4-scan.md` 与 `evidence/US4-cve-triage.md`

**Checkpoint**：`Dependency Scan` 可以作为必过检查，部署链不受其影响。

---

## Phase 7: User Story 5 - 工程基线可验证、可交接 (Priority: P3)

**Goal**：一份简短、每条命令都可执行的工程基线说明，并逐项核对 `oryxos-init` 的 DoD。

**Independent Test**：[quickstart.md §6](./quickstart.md#6-us5工程基线说明可执行)。

- [X] T069 [US5] 新建 `docs/EngineeringBaseline.md`，覆盖以下内容（依赖 US1–US4）：
  - G1–G7 门禁清单与对应文件；
  - 本地命令（完整校验、自动格式化、依赖扫描、基线 prune 与 check-shrink、安装钩子）；
  - 例外登记规则；存量基线条目数（来自 T023）与清零计划；“同文件同规则新增不被拦截”的已知局限；
  - P3C 映射表与缺口；
  - 日志形态切换、日志文件位置与滚动参数；用 `journalctl -u oryxos -o cat` 加 `python3` 按 `level` 过滤 JSON 的命令；
  - 运维端点与 nginx 屏蔽规则；
  - 门禁扫描范围（只含 `src/main/java`、`src/test/java`，不含生成代码）与测试代码只报告的规则；
  - CI 行为与两个必过检查的准确名称；CI 中 Checkstyle、PMD、SpotBugs 与测试合在 `Full verify` 一个步骤内、靠失败日志首行的门禁编号区分（偏差 D-6）；沿用 T063 已写好的“分支保护”一节；
  - 运维注意事项（dockerd 内存观察命令、systemd 219 限制）
- [X] T070 [US5] 在 `docs/EngineeringBaseline.md` 末尾逐项核对 `oryxos-init` 的 Definition of Done（9 项），写明“已满足 / 未满足及原因”，并说明 D-1：P3C 以 PMD 7 映射规则集落地，已于 2026-11-21 获维护者确认（依赖 T069）
- [X] T071 [P] [US5] 修改 `docs/TencentCloudDeployment.md`：新增“监控栈安装与访问”（离线安装包、`install-monitoring.sh`、`monitoring.env`、Grafana 初始密码文件位置）、“nginx 新增入口”、“JSON 日志查看”，并在“查看服务与日志”中补充 `/var/log/oryxos`
- [X] T072 [P] [US5] 修改 `tools/dev/README.md`：在文件顶部新增“004 工程门禁”一节（命令与 `docs/EngineeringBaseline.md` 一致）；修改 `CLAUDE.md` 的“开发流程与验证”命令块，只追加 `spotless:apply`、`install-hooks`、`-Pdependency-scan` 三条命令说明，保留用户现有的未提交修改
- [X] T073 [US5] 按 `docs/EngineeringBaseline.md` 从头执行一遍并计时（SC-008 ≤ 30 分钟）；检查文中引用的文件和命令都真实存在，结果写入 `evidence/US5-walkthrough.md`（依赖 T069–T072）

**Checkpoint**：说明与实际配置一致，新成员可以按说明独立完成上手。

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T074 在干净检出上执行 `time ./tools/dev/mvnw clean verify`，确认全部测试分组数不少于 T001、`PublishedOpenApiTest` 零差异、`Quality gate PASS`、额外耗时 ≤ 3 分钟（SC-001、SC-002、SC-005），结果写入 `evidence/final-verify.md`
- [X] T075 [P] 扫描本次新增或修改的所有文件，确认没有真实密钥、Webhook、IP、域名或主机名（FR-025）：用 `rg -n` 检查 IPv4、`https://`，以及从 `deploy/tencent-cloud/nginx/oryxos-baota-vhost.conf.tpl` 现有 `server_name` 中读出的生产域名（检索时从文件读取，不把域名写进任何新文件或命令记录），并逐条确认命中项都是占位或文档示例
- [ ] T076 [P] 在服务器上记录 OryxOS 的 `MemoryCurrent`，与重构前的 417MB 对比，把指标采集与 JSON 编码带来的增量写入 `evidence/US2-server.md`（quickstart 5.11）
- [X] T077 运行 `/speckit-analyze` 检查 spec、plan、tasks 的一致性，并对照 SC-001–SC-009 逐条记录“已验证 / 未验证及原因”，写入 `evidence/acceptance-summary.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup（Phase 1）**：无依赖；T001 必须在任何 004 改动之前执行。
- **Foundational（Phase 2）**：依赖 Setup；阻塞所有用户故事。
- **US1（Phase 3）**：依赖 Foundational。T020 的纯格式化提交必须早于 US2 的任何代码改动，以保持 diff 干净。
- **US2（Phase 4）**：本地部分（T025–T043）依赖 US1 完成格式化（T020）；服务器部分 T044–T054 可以与本地部分并行；T055 依赖 US3 的部署链（T063 部署出新版本）。
- **US3（Phase 5）**：依赖 US1（门禁命令）与 T051（同步脚本）；T063 自己先写出分支保护一节，并依赖 US4 的 T066、T068（`Dependency Scan` 在 GitHub 上运行过一次后才能设为必过检查）。
- **US4（Phase 6）**：依赖 Foundational（`NVD_API_KEY` 放行）；可与 US2、US3 并行。
- **US5（Phase 7）**：依赖 US1–US4 的实际结果（基线计数、CI 名称、时长）。
- **Polish（Phase 8）**：最后执行。

### User Story Dependencies

```text
Setup → Foundational → US1 ──┬─▶ US2(本地) ─────────────┐
                             ├─▶ US3 ─▶ (T063 部署) ─▶ US2(T055 服务器验收)
                             └─▶ US4 ─▶ (T066/T068 供 T063 设必过项)
                                                         │
                                                         ▼
                                                  US5 → Polish
```

### Within Each User Story

- US2：先写测试 T025–T029 并确认失败，再实现 T031–T042，最后验证 T043。
- 门禁配置（T012–T018）完成之后才能做兼容性实测 T019；T019 通过后才能执行格式化 T020。

## Parallel Opportunities

- Setup：T003、T004、T005 可以并行。
- Foundational：T006 与 T010 可以并行。
- US1：T013–T017 规则文件可以并行编写。
- US2：T025–T029 五个测试类可以并行；T031、T033、T038、T039、T041 分属不同文件，可以并行；服务器侧 T044–T050、T054 都可以并行。
- US3：T056、T059、T060 可以并行。
- US4：T064 可以与 US3 的工作流任务并行。

### Parallel Example: User Story 2

```text
# 测试先行，五个测试类同时编写：
T025 ProductionLogFormatTest   T026 TraceCorrelationTest   T027 LogRedactionTest
T028 GuardedFileAppenderTest   T029 ActuatorEndpointsTest

# 服务器侧文件与本地代码并行：
T044 versions.env   T046 prometheus/grafana.service   T047 prometheus.yml
T048 grafana.ini    T049 provisioning                  T050 dashboard JSON
```

## Implementation Strategy

### MVP First（只做 US1）

1. 完成 Phase 1 + Phase 2。
2. 完成 US1（T012–T024），**在此停下验证**：本地一条命令拦截格式、规约和安全问题，存量问题已进入基线。
3. 这时质量门禁已经在本地生效，US2–US5 都在这套门禁之下开发。

### Incremental Delivery

1. US1 → 本地门禁（MVP）
2. US2 本地部分 → JSON 日志、追踪、actuator（本地可验证）
3. US3 → CI 强制执行 + 部署链 + 服务器配置同步；随后执行 T055 完成服务器监控验收
4. US4 → 依赖漏洞扫描作为必过检查
5. US5 → 工程基线说明与 DoD 核对

### 授权节点汇总

T020（格式化提交）、T055（服务器安装与 nginx）、T062（推送功能分支）、T063（分支保护与合入 main）、T068（验证分支 PR）都需要维护者执行或明确授权。其余任务都可以在本地完成并验证。
