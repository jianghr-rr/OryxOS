# Implementation Plan: 工程地基重构（日志、监控、规范与安全门禁）

**Branch**: `004-refactor-engineering-foundation`（Spec Kit 按 `.specify/feature.json` 定位；方案编写时 Git 实际检出为 `main`，未切换分支） | **Date**: 2026-11-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/004-refactor-engineering-foundation/spec.md`

## Summary

在不改变五大核心能力行为、公开 REST 契约、CLI 输出和模块划分的前提下，按 `oryxos-init` 补齐工程地基：

1. **质量门禁**：Spotless + google-java-format 全量格式阻断；Checkstyle、PMD 7（阿里 P3C 映射规则集）、SpotBugs + Find Security Bugs 只出报告，由 `tools/dev` 中新增的基线判定按"文件 + 规则"阻断新增违规、基线只减不增；OWASP Dependency-Check 放在独立 profile 和独立 CI 作业中。
2. **可观测性**：Logback 双形态（开发彩色文本 / 生产 JSON，同时写 stderr 和滚动文件），脱敏复用运行时 `Redactor`；`X-Request-ID` 追踪标识贯穿过滤器、虚拟线程派发与回调，响应 `traceId` 与日志一致；Actuator health/liveness/readiness/info/prometheus 与业务共用回环端口，就绪判定与 `/api/v1/health` 同源。
3. **强制执行**：CI 改走 `tools/dev/mvnw`，与本地同一入口；新增 `Full Verify`、`Dependency Scan` 两个必过检查和 main 分支保护说明；部署工作流先调用门禁再发布；提供提交前钩子。
4. **服务器侧**：Prometheus 3.13.5 + Grafana 13.3.1 以离线安装包、一次性幂等脚本安装为 systemd 219 服务（回环监听、各 256MB 上限）；监控配置、看板和 OryxOS drop-in 随发布按内容变化同步，主服务回滚判定不受监控同步影响。

选型依据和版本核实见 [research.md](./research.md)。

## Technical Context

**Language/Version**: Java 21（`maven.compiler.release=21`）；Bash（服务器脚本，兼容 CentOS 7 系 bash 4.2）；Python 3（`tools/dev` 门禁脚本，沿用现有 `/usr/bin/python3 -I`）；YAML（GitHub Actions、Grafana provisioning）

**Primary Dependencies**:
- 运行时新增：`spring-boot-starter-actuator`、`micrometer-registry-prometheus`（Boot 3.5.16 BOM：Micrometer 1.15.12 / Prometheus client 1.3.10）、`net.logstash.logback:logstash-logback-encoder:8.1`；`oryxos-core` 新增 `org.slf4j:slf4j-api`（BOM 管理，仅门面）
- 构建插件新增：Spotless 3.12.0 + google-java-format 2.0.0（P0 实测由 1.37.0 回退调整，见 research §1）、maven-checkstyle-plugin 3.6.0 + Checkstyle 14.4.0、maven-pmd-plugin 3.28.0 + PMD 7.29.0、spotbugs-maven-plugin 4.10.5.0 + findsecbugs 1.14.0、dependency-check-maven 13.0.0（仅 profile）
- 服务器：Prometheus 3.13.5（LTS 线）、Grafana 13.3.1；systemd 219、宝塔 nginx

**Storage**: 不新增数据库表，不修改 SQLite 结构；新增文件型产物：`config/quality/baseline.json`、`/var/log/oryxos/*.json`、`/var/lib/prometheus`、`/var/lib/grafana`

**Testing**: 现有 `tools/dev/mvnw clean verify`（测试报告门禁）+ 新增测试分组 `f004-observability`（登记到 `tools/dev/test-groups.json`）+ `tools/dev/test_quality_gate.py`（基线判定的 Python 单测，与现有 `test_report_gate.py` 同构）+ 服务器侧验收（[quickstart.md](./quickstart.md) §5）

**Target Platform**: 开发机 macOS / Linux（JDK 21）；CI `ubuntu-latest` + Temurin 21；服务器 OpenCloudOS 7.9（RHEL7 系、glibc 2.17、systemd 219、cgroup v1）

**Project Type**: 既有九模块 Maven 单体（CLI + Web 运行时）的工程基础设施改造；不新增模块

**Performance Goals**:
- SC-001：完整校验比重构前多出的耗时 ≤ 3 分钟（实施第一步先测出重构前的耗时 T0）
- 提交前钩子目标 ≤ 60 秒（不计 Maven 首次下载依赖）
- SC-009：Prometheus + Grafana 常驻内存合计 ≤ 512MB；OryxOS JVM 新增内存开销需实测并记录（重构前 `MemoryCurrent` 约 417MB）

**Constraints**: JVM `-Xmx768m` 不变；运维端点不进入 OpenAPI（19 操作 / 44 schema 零差异）；CLI stdout/stderr 契约与退出码不变；fat JAR 名称与启动方式不变；配置、工作流和脚本中不得包含密钥、Webhook、主机地址或域名；systemd 单元只用 219 支持的指令

**Scale/Scope**: 299 个主代码 + 85 个测试 Java 文件一次性格式化；存量 Checkstyle/PMD/SpotBugs 违规进入基线并逐步清零；单机单实例

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 宪章原则 / 约束 | 结论 | 说明 |
| --- | --- | --- |
| I. 单机内核优先，范围明确 | PASS | 只做工程地基；不引入分布式追踪、告警、集中日志等 spec 明确排除的能力 |
| II. ReAct 与工具执行统一掌控 | PASS | 不触及 ReAct 和工具调度逻辑；`AgentService` 只在执行任务开始和结束时设置、清除 MDC，不改变执行语义 |
| III. 能力职责分离、依赖经契约 | PASS（附说明） | `oryxos-core` 只增加 `slf4j-api` 门面，不依赖 Logback 或 Spring；MDC 传递工具放在 core，追踪过滤器放在 web，日志与 actuator 装配放在 boot；模块依赖方向不变、无循环 |
| IV. 工具操作受控，凭证边界明确 | PASS | 日志脱敏复用运行时 `Redactor`，覆盖消息、异常栈和 MDC；NVD Key 只放 Actions Secret，经环境变量注入；Grafana 密码与域名只放服务器受控文件；运维端点公网 403 |
| V. 审计分阶段与持久化可恢复 | PASS | 不改审计表和持久化；JSON 文件日志属于运维日志，不冒充数据库审计 |
| VI. 用户故事可演示、可检查 | PASS | 每个故事都有 [quickstart.md](./quickstart.md) 场景；新增自动化测试分组；服务器、真实 CI 和分支保护单独报告，不用本地模拟代替 |
| VII. 规格、方案、任务、实现一致 | PASS | P3C 落地方式与 `oryxos-init` 字面不同，已由维护者确认（D-1）；其余均按已确认的 spec 澄清执行 |
| 技术基线（JDK 21 / Boot 3.x / MVC + 虚拟线程） | PASS | 不改；新增依赖版本已在 research 中核实并锁定 |
| 九模块可演进 | PASS | 不增减模块 |
| 治理：保留用户改动、不擅自提交 | PASS | 方案不包含自动提交或推送；`CLAUDE.md` 有用户未提交的修改，同步命令说明时只追加相关段落 |

**Post-Design Re-check（Phase 1 后）**：data-model、contracts 和 quickstart 没有引入新的违例。D-1 属于工具选型偏差，不违反宪章原则，已于 2026-11-21 由维护者确认。Complexity Tracking 无需填写。

## 服务器约束（2026-11-21 检查）

来自维护者在服务器上执行的只读检查及随后完成的前置整治（主机名和 IP 已脱敏为 `<HOST>`），是 FR-007a/FR-020a/SC-009 的设计输入：

- **系统**：OpenCloudOS 7.9（RHEL7 系，glibc 2.17，内核 5.4），2 核，SELinux 关闭；systemd 219 + cgroup v1。→ 单元文件用 `MemoryLimit=` + `MemoryAccounting=yes`，不用 `MemoryMax=`；不支持 `StateDirectory=`/`LogsDirectory=`，目录与用户由幂等脚本创建；脚本不用 `systemctl show --value`。
- **内存（整治后）**：总 3.6GiB，可用约 1.7GB；已新增 2GB swapfile（`vm.swappiness=10`）。占用：OryxOS JVM RSS 约 410MB（最坏约 1.0GB）、4 个 node 容器约 460MB、dockerd 约 95MB（已在 live-restore 下重启，从约 1.0GB 降下来）、宝塔约 110MB；journald 在 `/run` 约 270MB。→ Prometheus、Grafana 各 `MemoryLimit=256M`、`GOMEMLIMIT≈200MiB`、`OOMScoreAdjust=500`；OryxOS 堆满加上监控上限后仍余约 600MB。**残留风险**：有一个容器的健康检查长期失败，dockerd 的内存可能再次上涨（不在 004 范围内）。
- **端口**：9090 空闲；3000/3001/3010/3011 被 Docker 容器以 `*:端口` 占用。→ Prometheus `127.0.0.1:9090`，Grafana `127.0.0.1:3300`；OryxOS `127.0.0.1:8080` 由本机直接采集。
- **磁盘（整治后）**：`/var`、`/var/log`、`/opt` 同在 50G 根分区（ext4），已用 12G、可用 36G。日志 ≤1GB + Prometheus 15 天约 0.3–0.5GB + Grafana <100MB + 程序约 1GB，足够；Prometheus 另设 `retention.size=1GB` 兜底。
- **安装方式**：官方 tar 包 + systemd；不用 yum，也不用 Docker。服务器直连 GitHub 不可用，且 TUNA 只保留 Prometheus 最新版 → 安装包由维护者在本机下载后上传，脚本离线校验 sha256。
- **journald**：未持久化；按 spec 不改系统配置，由 FR-001a 文件日志承担持久化。
- **其他**：`NVD_API_KEY` 已配置为仓库级 Actions Secret（2026-11-21，网页方式，已确认存在）。

## Project Structure

### Documentation (this feature)

```text
specs/004-refactor-engineering-foundation/
├── spec.md                       # 已澄清规格
├── plan.md                       # 本文件
├── research.md                   # Phase 0：选型、版本核实、待实测项
├── data-model.md                 # Phase 1：门禁、基线、追踪上下文、同步状态
├── quickstart.md                 # Phase 1：端到端验证场景
├── contracts/
│   ├── observability.md          # 日志形态、追踪标识、访问日志、运维端点、nginx 屏蔽
│   ├── quality-gates.md          # G1–G7、命令、基线格式、P3C 映射、CI 检查名、钩子
│   └── server-config.md          # 服务器目录、systemd 单元、安装与同步脚本、nginx 入口
├── checklists/requirements.md    # 规格质量清单（已有）
└── tasks.md                      # 下一步由 /speckit-tasks 生成
```

### Source Code (repository root)

```text
pom.xml                                   # 改：工具版本属性；Spotless/Checkstyle/PMD/SpotBugs 插件；profile dependency-scan；logstash 版本管理
.editorconfig                             # 新：与 google-java-format 一致（UTF-8、LF、Java 2 空格、100 列、去行尾空白）
.mvn/jvm.config                           # 新：google-java-format 在 JDK 21 上所需的 --add-exports
.git-blame-ignore-revs                    # 新：记录全仓格式化提交

config/                                   # 新：门禁规则与例外（全部入库，每条例外带理由）
├── checkstyle/{checkstyle.xml,suppressions.xml}
├── pmd/{alibaba-p3c.xml,exclude-pmd.properties}
├── spotbugs/exclude.xml
├── dependency-check/suppressions.xml
└── quality/baseline.json                 # 存量违规基线（文件 + 规则），只减不增

oryxos-core/
├── pom.xml                               # 改：+ slf4j-api
└── src/main/java/.../core/
    ├── observability/MdcContext.java     # 新：跨线程 MDC 快照与包装
    └── agent/AgentService.java           # 改：执行任务内设置/清除 executionId

oryxos-web/src/main/java/.../web/
├── observability/TraceIdFilter.java      # 新：X-Request-ID 校验/生成、MDC、/api/v1 访问日志
├── observability/RequestTrace.java       # 新：currentOrNew()
├── api/ApiResponse.java                  # 改：traceId 取自 RequestTrace（字段与 schema 不变）
├── api/ApiExceptionHandler.java          # 改：未预期异常以 ERROR 记录（脱敏）
└── execution/HttpExecutionAdapter.java   # 改：workers/deadlines/whenFinished 回调传递 MDC

oryxos-boot/
├── pom.xml                               # 改：+ actuator、micrometer-registry-prometheus、logstash-logback-encoder；build-info
├── src/main/resources/logback-spring.xml # 改：dev 文本 / prod JSON + 受保护的滚动文件
├── src/main/resources/application.yml    # 改：management.*（暴露端点、探针、公共标签、show-details=never）
├── src/main/java/.../boot/observability/
│   ├── GuardedRollingFileAppender.java   # 新：目录不可写时只告警、不让启动失败
│   ├── LogRedaction.java                 # 新：持有运行时 Redactor（注册前用纯正则版本）
│   ├── RedactingValueMasker.java         # 新：JSON 全字段脱敏
│   ├── RedactingConverters.java          # 新：文本形态 %redactedMessage / %redactedException
│   ├── LogRedactionRegistrar.java        # 新：容器启动时注册 Redactor
│   └── OryxRuntimeHealthIndicator.java   # 新：readiness 与 /api/v1/health 同源
└── src/test/java/.../boot/observability/ # 新：分组 f004-observability（见下）

tools/dev/
├── build.py                              # 改：新增禁止参数；Maven 后调用基线判定；dependency-scan 时只放行 NVD_API_KEY；CI 选 maven-settings-ci.xml；清理旧质量报告
├── quality-gate.py                       # 新：解析三类报告 + 基线判定 + 例外理由检查
├── quality-baseline                      # 新：--init / --prune / --check-shrink <ref>
├── maven-settings-ci.xml                 # 新：GCS 镜像 + HTTP 重试（仅 GITHUB_ACTIONS=true）
├── install-hooks                         # 新：git config core.hooksPath tools/dev/hooks
├── hooks/pre-commit                      # 新
├── test-groups.json                      # 改：+ f004-observability
├── test_quality_gate.py                  # 新
└── README.md                             # 改：新增命令

.github/workflows/
├── quality-gate.yml                      # 新：作业 "Full Verify"（PR、非 main 分支推送、workflow_call）
├── dependency-scan.yml                   # 新：作业 "Dependency Scan"（PR、每日、手动）
└── deploy-tencent-cloud.yml              # 改：先 uses quality-gate.yml，复用其 JAR 产物；上传 server-config/；路径过滤补充

deploy/tencent-cloud/
├── monitoring/versions.env               # 新：Prometheus/Grafana 版本与 sha256
├── server-config/                        # 新：systemd 单元 + drop-in + Prometheus/Grafana 配置与看板
├── scripts/install-monitoring.sh         # 新：一次性幂等安装
├── scripts/sync-server-config.sh         # 新：oryxos-dropin / monitoring 两段同步
├── scripts/deploy-release.sh             # 改：启动前同步 drop-in（失败则撤销并自动回滚）；自动回滚时 drop-in 随回滚目标恢复；健康通过后同步监控
└── nginx/oryxos-baota-vhost.conf.tpl     # 改：/actuator 403、X-Request-ID、/grafana/、/prometheus/

docs/
├── EngineeringBaseline.md                # 新：FR-024 工程基线说明（门禁、命令、例外、日志、端点、CI、分支保护步骤、存量计数、DoD 核对）
└── TencentCloudDeployment.md             # 改：监控栈安装、nginx 入口、journalctl JSON 过滤、日志目录
```

**新增测试分组 `f004-observability`**（local 模式，登记到 `test-groups.json`）：
- `ProductionLogFormatTest`：`prod` 下 100 行日志都能解析为 JSON 且字段齐全；`dev` 下不写文件。
- `TraceCorrelationTest`：10 个真实 HTTP 请求（含经 `HttpExecutionAdapter` 的执行请求和超时路径）的响应 `traceId` 在日志中命中且互不相同；合法和非法 `X-Request-ID` 的处理；请求后 MDC 不残留。
- `LogRedactionTest`：已注册密钥、URL、`token=` 在消息、异常栈和 MDC 中都被脱敏；文本与 JSON 两种形态一致。
- `GuardedFileAppenderTest`：目录不可写时上下文正常启动，stderr 输出告警；滚动参数可被环境变量覆盖。
- `ActuatorEndpointsTest`：health/liveness/readiness/info/prometheus 的状态码和内容；`application="oryxos"` 标签；`http_server_requests` 计入异步请求；`PAUSED_RECORDING` 时 readiness 503 且与 `/api/v1/health` 一致；`/v3/api-docs` 不含 actuator。
- 既有 `PublishedOpenApiTest`、`EntryPointContractTest`、`CliCommandsIT` 继续作为零差异和 CLI 契约的回归依据。

**Structure Decision**：不新增 Maven 模块。规则与例外集中在根目录 `config/`；门禁判定放在既有统一入口 `tools/dev`；可观测性代码按职责分布在 core（MDC 传递）、web（追踪过滤器）和 boot（日志、actuator 装配）；服务器侧文件集中在 `deploy/tencent-cloud/`。

## 实施顺序与提交边界（供 /speckit-tasks 拆分）

依赖关系：**P0 → P1（US1）→ P2（US2）与 P4（US4）可并行 → P3（US3，依赖 P1 的门禁命令）→ P5（服务器，依赖 P2 的端点和 P3 的部署链）→ P6（US5 文档，最后）**。

| 阶段 | 内容 | 建议提交边界 | 完成判据 |
| --- | --- | --- | --- |
| P0 | 在当前检出上测出 T0；用 `tools/dev` 实测待实测项：google-java-format 在 JDK 21 上运行、PMD 7 加载映射规则集、SpotBugs 在现有代码上运行 | 只有证据，不改代码 | 证据写入 `specs/004-.../evidence/`；任一失败按 research 回退方案处理后再继续 |
| P1a | 父 POM 插件、`config/`、`.editorconfig`、`.mvn/jvm.config`、`tools/dev` 门禁扩展与 Python 单测 | 提交 1：门禁配置 | `spotless:check` 能报告不合规文件 |
| P1b | `spotless:apply` 全仓格式化 | **提交 2：纯格式化**（不夹带任何其他改动），随后把该 SHA 写入 `.git-blame-ignore-revs` | 测试全绿，diff 只包含空白、换行和 import 顺序 |
| P1c | 生成基线；对安全告警分类（设计内的写入抑制并附理由，其余进基线）；记录存量计数 | 提交 3：基线与例外 | `tools/dev/mvnw clean verify` 通过；quickstart §1 全部符合 |
| P2 | 日志、追踪、actuator 及 `f004-observability` | 按 core / web / boot 分若干提交 | quickstart §2 与测试分组通过；OpenAPI 零差异 |
| P3 | 三个工作流、CI 改走 `tools/dev`、提交前钩子 | 一个提交 | 功能分支 `Full Verify` 由红变绿；钩子能拦截格式错误 |
| P4 | dependency-scan profile、工作流、抑制文件 | 一个提交 | quickstart §4.1/4.2 |
| P5 | `server-config/`、两个脚本、`deploy-release.sh`、nginx 片段；维护者首次安装 | 一个提交 + 手工安装 | quickstart §5 |
| P6 | `EngineeringBaseline.md`、部署手册、`tools/dev/README.md`、`CLAUDE.md` 命令段落 | 一个提交 | quickstart §6 |

需要授权的动作（方案不代为执行）：提交、推送、开启分支保护、服务器安装与 nginx 修改、合并到 main 触发部署。

## 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| Logback 初始化出现 ERROR 状态会让 Spring Boot 拒绝启动（日志目录不可写时） | 违反 FR-001a，发布失败 | `GuardedRollingFileAppender` 预检目录，只记 WARN；`GuardedFileAppenderTest` 覆盖 |
| MDC 在某个线程切换点没有传递 | 响应 `traceId` 在日志中检索不到 | data-model §5 列出全部四个传递点；`TraceCorrelationTest` 覆盖同步、执行和超时三条路径 |
| 全仓格式化与并行开发冲突 | 其他分支合并困难 | 格式化单独成一个提交，并登记到 `.git-blame-ignore-revs`；建议在没有并行分支时执行 |
| P3C 映射规则集覆盖不全 | 规约约束比官方 P3C 弱 | 映射表标出缺口；IDE 阿里插件作为补充；D-1 已确认接受这一取舍 |
| CI 改走 `tools/dev` 后多跑 `kernel-soak-harness`，且依赖下载路径变化 | CI 时长上升、缓存失效 | 缓存 `.oryxos-dev/repository`；P3 实测 CI 时长并写入基线说明；超过 45 分钟上限时再讨论分组策略（需维护者决定） |
| Grafana 13 在 glibc 2.17 上无法运行 | 监控栈装不上 | 安装脚本预检并中止；回退到能运行的 Grafana 最新版本（同样锁 sha256），并在 research 中记录 |
| 监控内存超出预算 | 挤压 OryxOS | 硬上限 + `GOMEMLIMIT` + `OOMScoreAdjust`；已有 2GB swap；quickstart §5.2 观察 24 小时 |
| dockerd 内存再次上涨（某容器健康检查长期失败） | 可用内存下降 | 范围外；在工程基线说明的运维注意事项中记录观察命令 |
| 漏洞库更新失败时插件用旧缓存静默通过 | 违反 FR-017 | P4 实测断网行为，必要时在工作流中检查更新日志并显式失败 |

## 偏差与需维护者确认

| 编号 | 事项 | 方案取舍 | 需要的确认 |
| --- | --- | --- | --- |
| **D-1** | `oryxos-init` 写的是 `com.alibaba.p3c:p3c-pmd` 规则集；该构件最新 2.1.1 绑定 PMD 6.15，无法解析本仓 Java 16+ 语法，也与 PMD 7 不兼容 | 改用 PMD 7 + 仓库内 P3C 映射规则集（三组规则，缺口在映射表中标出）；回退方案是 PMD 6.55 + p3c-pmd 2.1.1（research §5-A） | **已确认（2026-11-21，维护者在方案 review 中选择采用映射规则集）** |
| D-2 | SC-003 中"服务输出的任意 100 行日志都是 JSON"，度量对象定为**日志文件**；journal 里还会有 CLI 契约行和 JVM 警告 | 按 FR-004 不把 CLI 契约输出改成 JSON | 属于对 spec 的解释，不改 spec；如有异议请在 review 中提出 |
| D-3 | FR-020a 中"同步失败使发布失败并触发既有回滚"与"监控同步失败不影响主服务回滚判定"同时成立的方式 | drop-in 在启动前同步，失败时先撤销 drop-in 再执行自动回滚（此时旧服务已停，不能直接退出）；新版本健康检查失败时，自动回滚把 drop-in 恢复到回滚目标对应的状态；监控在健康检查通过后同步，失败时恢复监控配置、发布标红，但不回滚 OryxOS | 同上 |
| D-4 | `oryxos-init` 片段要求 Spotless `<importOrder/>`、SpotBugs `threshold=Low` | 不加 importOrder（google-java-format 已排序，避免冲突）；threshold 用 Medium（控制噪声，安全检测器默认就是 Medium 以上） | 无需确认，记录在案 |
| D-5 | 新增一条 `/api/v1/**` INFO 访问日志 | 这是满足 SC-003 必需的最小日志；`/actuator` 和静态资源不记 | 无需确认，记录在案 |
| **D-6** | FR-019 要求 CI 串行执行各项检查且步骤名与本地命令一一对应；`Full Verify` 把 Checkstyle、PMD、SpotBugs 与测试合在一个 `Full verify (tools/dev/mvnw clean verify)` 步骤内 | 保持与本地同一条命令、只编译一次；门禁失败时日志首行写明门禁编号（G1–G6），质量报告作为产物上传，以此满足 US3 场景 2 的“失败原因清晰可辨”；拆成多个步骤会重复编译、拉长 CI | **已确认（2026-11-21，维护者在一致性检查后选择保留单一步骤并登记偏差）** |

## 范围外发现（记录，不在 004 实施）

- 现有 `deploy/tencent-cloud/systemd/oryxos.service` 中的 `ProtectSystem=strict`（systemd 232 引入）和 `ReadWritePaths=`（231 引入）在服务器的 systemd 219 上**不生效**，单元文件中的这部分加固实际被忽略。修正需要单独评估（例如改为 `ProtectSystem=full` + `ReadWriteDirectories=`），建议另开特性处理。
- 3000/3001/3010/3011 容器端口和 rpcbind 111 端口监听在所有网卡上，是否暴露公网取决于安全组；SSH 有暴力破解尝试；生产环境 SpringDoc 默认开启（已由 nginx 403 屏蔽）。

## Complexity Tracking

无宪章违例，无需豁免。
