# 004 验收汇总（T077）

日期 2026-11-23，分支 `004-refactor-engineering-foundation`。已提交：`78b3540`（门禁与配置）、`5b288ba`（纯格式化）；其余改动按授权保持未提交。

更新（2026-11-23 晚）：功能分支已经 PR #2 合入 main，后续修复经 PR #3–#5 合入；第 1、2 节按合入与部署后的结果更新。

## 1. 成功标准逐条核对

| SC | 结论 | 依据 / 未验证原因 |
| --- | --- | --- |
| SC-001 统一命令，额外耗时 ≤ 3 分钟 | **已验证** | 干净检出 `clean verify` 640.6 秒，T0 = 594 秒，多 47 秒（[final-verify.md](./final-verify.md)） |
| SC-002 格式 100%、新代码阻断违规 0、存量有记录与计划 | **已验证** | `spotless:check` 全仓通过；`Quality gate PASS: 460 baseline entries`；存量与清零计划见 `docs/EngineeringBaseline.md` §3 |
| SC-003 100 行 JSON、10/10 traceId 命中 | **已验证** | 本地 prod 形态 2.1、2.2 通过（[US2-local.md](./US2-local.md)）；服务器 `/var/log/oryxos/oryxos.json` 已写入，10/10 traceId 命中（[US2-server.md](./US2-server.md)） |
| SC-004 本机探针 UP、指标可读、公网未认证被拒 | **已验证** | 本地 2.4、2.5 与 `ActuatorEndpointsTest`；`env`、`beans`、`heapdump`、`metrics` 返回 404。公网未认证访问 `/actuator/health`、`/actuator/prometheus` 返回 403；服务器本机探针 `UP`，Prometheus 的 `oryxos` 目标为 `up`（[US2-server.md](./US2-server.md)） |
| SC-005 OpenAPI 零差异、分组不减少且全部通过 | **已验证** | `PublishedOpenApiTest` 2/2；分组 50 → 51；334 例全部通过 |
| SC-006 四类注入 4/4 被拦截、钩子拦截、CI 标红 | **已验证** | 格式、规约、静态安全注入均被拦截且定位到行（[US1-gates.md](./US1-gates.md)）；钩子拦截格式与 PMD/Checkstyle（[US3-hook.md](./US3-hook.md)）；高危 CVE 依赖被 `Dependency Scan` 拦截（[US4-scan.md](./US4-scan.md)）；不合规提交使 CI 标红（[US3-ci.md](./US3-ci.md)） |
| SC-007 推送 main 后部署成功、服务器配置逐文件一致 | **已验证** | 合入 main 后部署成功；服务器配置 8/8 与仓库一致；只改看板时只重启 Grafana；`prometheus.yml` 写错时监控配置不切换（[US3-deploy.md](./US3-deploy.md)）。首次合入的部署因 sudo 白名单失败，已由 PR #3 修复 |
| SC-008 新开发者 30 分钟内完成上手 | **已验证** | 约 11 分钟（[US5-walkthrough.md](./US5-walkthrough.md)）；依赖缓存已就绪，首次下载不在内 |
| SC-009 Grafana 看板、监控内存 ≤ 768MB（原 512MB，见 plan D-7）、公网未认证被拒 | **部分验证** | 监控栈已安装，公网未认证访问 Grafana/Prometheus 返回 401；新版本部署后 Prometheus 与 Grafana 的 `total_rss` 合计 260.2MB（[US2-server.md](./US2-server.md)）；看板已存在。看板数据与 24 小时曲线、24 小时后内存复测待观察（T055） |

## 2. 未完成任务

| 任务 | 状态 | 需要什么 |
| --- | --- | --- |
| T055 服务器监控栈 | 已安装，5.1、5.3、5.4、5.6 通过 | 认证后确认看板有数据（5.5）；运行 24 小时后复测 `total_rss` 与看板曲线（5.2）；重启主机后确认旧日志仍在（5.6） |
| T067 漏洞库不可达时的行为 | 部分验证 | 无缓存时 NVD 返回 503 会使作业失败（[US4-cve-triage.md](./US4-cve-triage.md)）；“已有旧缓存且更新失败”的情况尚未实测 |

## 3. 敏感信息扫描（T075）

范围：相对改造前提交 `9055902` 新增或修改的全部 266 个文件（含未跟踪文件）。生产域名在扫描命令中从 nginx 模板读入变量，输出只显示“文件:行号”。

| 检查 | 结果 |
| --- | --- |
| 生产域名 | 只出现在 `deploy/tencent-cloud/nginx/oryxos-baota-vhost.conf.tpl`（2 行）与 `docs/TencentCloudDeployment.md`（3 行），命中数与改造前完全相同，本次没有新增 |
| IPv4 | 只有 `127.0.0.1`；另两个匹配值 `4.10.5.0`、`3.51.3.0` 是版本号 |
| URL | 占位地址（`example.invalid`、`example.com` 等）、本机地址、公开 API 主机、文档与下载链接；没有 Webhook 地址 |
| 密钥形态 | 只命中测试中的 canary 值（`sk-live-OBSERVABILITY-0042`、`fixture-secret-do-not-log`、`SCRIPT_SECRET_CANARY`），用于验证脱敏 |
| 本地密钥文件 | `.oryxos-dev/real-secrets.env` 未读取、未复制到任何副本 |

## 4. 一致性检查：实现与 spec / plan / contracts / tasks 的差异

以下差异不影响验收结论。A1–A5 已于 2026-11-23 经维护者同意同步到设计文档。

| # | 位置 | 差异 | 处理 |
| --- | --- | --- | --- |
| A1 | plan.md 文件树、T038 | 只列了 `%redactedMessage`、`%redactedException`；实际还有 `%redactedMdc`，用于满足 T027“MDC 值也要脱敏”；异常转换器的内部类名为 `RedactingConverters$Throwable`（避开 SpotBugs `NM_CLASS_NOT_EXCEPTION`） | 已同步 plan 文件树与 T038 |
| A2 | contracts/quality-gates.md §4 日志行 | 写的是“两处 CLI 契约输出登记为带理由的例外”；实际是三处，新增 `GuardedRollingFileAppender`（可观测性契约要求的 stderr 告警） | 已改为三处并写明来源 |
| A3 | contracts/quality-gates.md §6、research.md | 钩子执行内容写的是 `spotless:check pmd:pmd checkstyle:checkstyle`；实际是 `compile pmd:pmd checkstyle:checkstyle`（`compile` 会先经过 `spotless:check`，不先编译时 `pmd:pmd` 无法解析兄弟模块） | 已同步契约与 research；T060 与 P0-compat 保留原文加实施注，作为历史记录 |
| A4 | contracts/quality-gates.md §2 | 依赖扫描命令写成 `NVD_API_KEY=... ./tools/dev/mvnw ...`，与“Key 不进命令参数”的约束不一致 | 已改为“先 export 再执行”，与工程基线说明一致 |
| A5 | contracts/quality-gates.md §5 | `Full Verify` 的步骤列表没有 `Upload boot JAR`；该步骤供部署工作流使用门禁产出的 JAR | 已补充 |
| A6 | tasks.md | 两处任务外修复：`tools/dev/build.py` 创建 `.oryxos-dev/evidence`（否则全新检出上 `WriterIsolationTest` 必败，CI 首跑会红）；`quality-gate.py --prune` 保留 `createdAt`/`baseCommit` 且不做无变化改写 | 已记录在 final-verify.md 与 US5-walkthrough.md |
| A7 | US2 实现细节 | 日志参数使用 `springProperty`、`AgentService` 新增 DEBUG 行 `execution_finished`、`validate-group-membership: false`、健康指示器由 `@Bean("oryxRuntime")` 注册 | 已在 US2-local.md“与任务描述的差异”中说明 |

覆盖检查：tasks.md 共 77 个任务，已完成 75 个；未完成 2 个（T055、T067），分别等待 24 小时观察与旧缓存场景实测。偏差 D-1 至 D-6 的处理与 plan 一致，D-1、D-6 已获维护者确认。
