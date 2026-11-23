# Phase 1 Data Model: 工程地基重构

本特性不新增数据库表，也不改变 SQLite 结构与已发布 DTO。下列"实体"都是配置文件、报告或运行期上下文，按 spec Key Entities 展开。

## 1. QualityGate（质量门禁）

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `id` | `G1`–`G7` | 见 [contracts/quality-gates.md §1](./contracts/quality-gates.md#1-门禁清单) |
| `name` | string | 与 CI 步骤名一一对应 |
| `tool` / `version` | string | 版本在父 POM `<properties>` 中锁定 |
| `phase` | Maven 阶段或 `post-maven` | G7 只在 profile `dependency-scan` 中 |
| `blocking` | `all` / `baseline-delta` / `report-only` | G1 为 `all`；G2–G4 主代码为 `baseline-delta`，测试代码为 `report-only` |
| `ruleFile` / `suppressionFile` | 仓库相对路径 | 必须存在；抑制项必须带理由 |

**规则**：同一门禁在本地 `tools/dev/mvnw verify` 与 CI `Full Verify` 中的配置来源相同（父 POM + `config/`），不允许 CI 单独覆盖。

## 2. Violation（违规，报告归一化结果）

| 字段 | 来源 |
| --- | --- |
| `tool` | `checkstyle` / `pmd` / `spotbugs` |
| `rule` | Checkstyle 模块名（`source` 最后一段）/ PMD `rule` / SpotBugs `type` |
| `file` | 仓库相对路径，`/` 分隔 |
| `line` / `message` / `severity` | 报告原值，仅用于输出 |
| `scope` | `main` / `test`（按路径中 `/src/test/` 判定） |

**归一化键**：`(tool, rule, file)`。同一键出现多次只算一个基线条目。

## 3. BaselineEntry 与 Baseline 文件

文件：`config/quality/baseline.json`，格式见 [contracts/quality-gates.md §3](./contracts/quality-gates.md#3-基线文件格式configqualitybaselinejson)。

| 字段 | 约束 |
| --- | --- |
| `tool`, `rule`, `file` | 与 Violation 归一化键一致；只登记 `scope=main` |
| 文件级 `baseCommit` | 创建基线时的提交；之后只能删除条目 |

**状态流转**：

```text
             创建（重构合入前一次性生成）
                      │
                      ▼
   ┌──────────── ACTIVE（报告中仍出现）
   │                  │ 违规被修复，报告中不再出现
   │                  ▼
   │             STALE（gate 提示 --prune）
   │                  │ quality-baseline --prune
   │                  ▼
   │              REMOVED（从文件删除）
   │
   └─ 不允许：新增条目（--check-shrink 失败）
```

## 4. Suppression（例外登记）

| 文件 | 条目格式 | 必填 |
| --- | --- | --- |
| `config/spotbugs/exclude.xml` | `<Match>` + `<Bug pattern>` / `<Class>` | XML 注释 `reason:`、`registered:` |
| `config/pmd/exclude-pmd.properties` | `全限定类名=规则名[,规则名]` | 上一行注释写 `reason`、`registered` |
| `config/checkstyle/suppressions.xml` | `<suppress checks files>` | XML 注释 `reason`、`registered` |
| `config/dependency-check/suppressions.xml` | 官方 `<suppress>` | `<notes>` 写理由与登记日期 |

**规则**：`quality-gate.py` 检查每条抑制都有 `reason` 与 `registered`，缺失即失败。抑制只能用于"设计内的正确行为"或"已评估的误报"，存量待修违规只能进基线。

## 5. TraceContext（请求追踪上下文）

| 字段 | 生命周期 | 存放 |
| --- | --- | --- |
| `traceId` | HTTP 请求进入 `TraceIdFilter` → 响应写出后清除 | SLF4J MDC；跨线程时用快照传递 |
| `executionId` | `AgentService` 执行任务开始 → 结束（`finally` 清除） | SLF4J MDC |

**跨线程传递点**（必须全部覆盖，由测试验证）：

1. `HttpExecutionAdapter.workers.execute(...)`（虚拟线程派发）
2. `HttpExecutionAdapter.deadlines.schedule(...)`（60 秒超时定时器）
3. `AgentService.Handle.whenFinished(...)` 回调（执行完成时构造响应）
4. `AgentService.workers.execute(...)`（执行任务，同时设置 `executionId`）

**不变量**：任意线程在任务结束后 MDC 中不残留 `traceId`/`executionId`；`ApiResponse.traceId` 与该请求日志中的 `traceId` 相同。

## 6. LogOutputProfile（日志形态）

| 值 | 触发 | 目标 | 编码 |
| --- | --- | --- | --- |
| `dev` | 默认 | stderr | 脱敏文本（带颜色） |
| `prod` | `SPRING_PROFILES_ACTIVE` 含 `prod` | stderr + 滚动文件 | 脱敏 JSON |

`prod` 中的文件 appender 状态：`ENABLED`（目录可写）或 `DISABLED_UNWRITABLE`（启动时目录不可写，stderr 已告警）。运行期磁盘写满时 Logback 记录内部错误，进程继续运行。

## 7. OperationalEndpoint（运维端点）

| 端点 | 状态来源 | 状态值 |
| --- | --- | --- |
| liveness | `LivenessState` | `UP` / `DOWN` |
| readiness | `ReadinessState` + `oryxRuntime`（`WebRuntimePort.health().ready()`） | `UP` / `OUT_OF_SERVICE` |
| health（整体） | 全部指示器聚合 | `UP` / `OUT_OF_SERVICE` / `DOWN` |

**不变量**：`readiness == UP` ⇔ `/api/v1/health` 返回 200。

## 8. MonitoringStack 与 ServerConfigBundle

| 实体 | 字段 | 约束 |
| --- | --- | --- |
| `MonitoringVersions`（`versions.env`） | `PROMETHEUS_VERSION`、`PROMETHEUS_SHA256`、`GRAFANA_VERSION`、`GRAFANA_SHA256` | sha256 必须与官方发布校验和一致 |
| `ServerConfigBundle`（发布包内 `server-config/`） | 文件集合 + 内容 hash | 不含域名、IP、密码 |
| `SyncManifest`（`server-config.sha256`） | 每个已部署文件的 sha256 | 每次同步重写；SC-007 比对依据 |

**`monitoring` 同步状态流转**：

```text
UNCHANGED ──(hash 相同)──────────────────────────────▶ 结束（不重启）
    │
    └─(hash 不同)─▶ STAGED(<hash>/) ─(promtool 失败)─▶ FAILED_RESTORED ─▶ 发布失败（不回滚 OryxOS）
                        │
                        └─(校验通过)─▶ SWITCHED(current→<hash>) ─▶ 重启变化服务
                                             │
                                ┌────────────┴────────────┐
                           active ▼                         ▼ 重启失败
                            APPLIED                 FAILED_RESTORED（链接切回）
```

**`oryxos-dropin` 同步状态**：`UNCHANGED` → 结束；`CHANGED` → `REPLACED`（`.prev` 保留）→ `daemon-reload` → 交给 `deploy-release.sh` 启动；任一步失败 → `RESTORED` → 发布失败，走既有回滚。
