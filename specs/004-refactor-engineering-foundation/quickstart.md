# Quickstart：工程地基重构验证指南

本指南列出证明 004 特性端到端可用的验证场景。命令都在仓库根目录执行，除非注明"服务器"。
前置：`export ORYXOS_JAVA_HOME=/absolute/path/to/jdk-21/Contents/Home`；系统有 `python3`、`git`、`curl`。
契约细节见 [contracts/](./contracts/)，此处不重复。

## 0. 重构前基线测量（实施第一步，SC-001 对照）

```bash
time ./tools/dev/mvnw clean verify        # 记录重构前耗时 T0，写入 evidence
```

## 1. US1：本地一条命令复现全部门禁

| # | 操作 | 期望 |
| --- | --- | --- |
| 1.1 | `time ./tools/dev/mvnw clean verify` | 成功；输出含 `Test evidence PASS` 与 `Quality gate PASS`；耗时 ≤ T0 + 3 分钟（SC-001） |
| 1.2 | 在任一主代码文件中把一行改成 4 空格缩进，执行 `./tools/dev/mvnw spotless:check` | 失败并列出该文件；执行 `./tools/dev/mvnw spotless:apply` 后再次检查通过 |
| 1.3 | 新建 `oryxos-core/src/main/java/.../GateProbe.java`，写 `catch (Exception e) {}`，执行 `./tools/dev/mvnw verify` | 失败：`G6 pmd EmptyCatchBlock oryxos-core/.../GateProbe.java:<行>` |
| 1.4 | 在 `GateProbe` 中写 `Runtime.getRuntime().exec("sh -c " + arg)` | 失败：`G6 spotbugs COMMAND_INJECTION ...:<行>` |
| 1.5 | 执行 `./tools/dev/mvnw verify -Dspotbugs.skip=true` | `tools/dev` 以退出码 2 拒绝 |
| 1.6 | 删除 `GateProbe.java` | `verify` 恢复通过 |

## 2. US2：日志、追踪与运维端点（本地）

```bash
./tools/dev/oryxos --root /tmp/oryxos-004 init
SPRING_PROFILES_ACTIVE=prod ORYXOS_LOG_DIR=/tmp/oryxos-004-logs \
  ./tools/dev/oryxos --root /tmp/oryxos-004 serve 2>/tmp/oryxos-004-stderr.log &
```

| # | 操作 | 期望 |
| --- | --- | --- |
| 2.1 | `head -100 /tmp/oryxos-004-logs/oryxos.json \| python3 -c 'import sys,json;[json.loads(l) for l in sys.stdin];print("ok")'` | 输出 `ok`；每行都有 `@timestamp level logger_name thread_name message application`（SC-003） |
| 2.2 | 对 10 个请求，取 `curl -s 127.0.0.1:8080/api/v1/sessions/<不存在的UUID>` 返回的 `traceId`，在日志文件中 `grep` | 10/10 命中，且互不相同 |
| 2.3 | `curl -s -H 'X-Request-ID: probe-abcdef12' .../api/v1/info` | 响应 `traceId` 为 `probe-abcdef12`；把请求头换成 `bad id\n` 时生成新的 UUID |
| 2.4 | `curl -s 127.0.0.1:8080/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness` | 都返回 `UP` |
| 2.5 | `curl -s 127.0.0.1:8080/actuator/prometheus \| grep -E '^(jvm_memory_used_bytes\|http_server_requests_seconds_count\|process_uptime_seconds)' \| grep 'application="oryxos"'` | 三类指标都存在且带标签 |
| 2.6 | `curl -s 127.0.0.1:8080/v3/api-docs \| grep -c actuator` | `0`（FR-009） |
| 2.7 | 不带 `prod` 启动 | stderr 为彩色文本，`/tmp/oryxos-004-logs` 不产生新文件 |
| 2.8 | `ORYXOS_LOG_DIR=/proc/forbidden` 加 `prod` 启动 | 服务正常启动；stderr 有 `[oryxos-logging] WARN log directory not writable` |
| 2.9 | `./tools/dev/oryxos --version`、`./tools/dev/oryxos --root /tmp/oryxos-004 status` | 输出与重构前逐字一致（FR-023） |

就绪联动（PAUSED_RECORDING → readiness 503）、MDC 不残留、脱敏覆盖异常栈由自动化测试分组 `f004-observability` 验证，本地执行 `./tools/dev/mvnw -pl oryxos-boot -am test -Dgroups=f004-observability`。

## 3. US3：CI、分支保护与提交前钩子

| # | 操作 | 期望 |
| --- | --- | --- |
| 3.1 | `./tools/dev/install-hooks`；提交一个格式不合规的改动 | 提交被拒并提示 `spotless:apply`；修复后提交成功 |
| 3.2 | `git commit --no-verify` 后推送到功能分支 | `Full Verify` 标红，失败步骤名可辨 |
| 3.3 | 修复后推送 | `Full Verify` 变绿 |
| 3.4 | 按工程基线说明开启 main 分支保护（必过：`Full Verify`、`Dependency Scan`） | `git push origin main` 被平台拒绝 |
| 3.5 | 通过合并请求合入一个通过门禁的提交 | 部署工作流先跑 `Full Verify`，再完成发布与健康检查（SC-007） |

## 4. US4：依赖漏洞扫描

| # | 操作 | 期望 |
| --- | --- | --- |
| 4.1 | 在验证分支给 `oryxos-boot` 临时加入一个带已知高危 CVE 的旧依赖，发起合并请求 | `Dependency Scan` 失败，报告列出坐标、CVE 编号与 CVSS |
| 4.2 | 移除该依赖 | `Dependency Scan` 通过并上传 HTML/JSON 报告 |
| 4.3 | 在 `suppressions.xml` 中给某条误报登记理由 | 该条被抑制；去掉理由时 `quality-gate.py` 报错 |
| 4.4 | 用 `workflow_dispatch` 在 `NVD_API_KEY` 失效的副本上运行 | 作业失败并说明漏洞库不可用原因；部署工作流不受影响 |

本地显式扫描：`NVD_API_KEY` 已在当前 shell 中 `export` 后执行 `./tools/dev/mvnw -Pdependency-scan dependency-check:aggregate`。

## 5. 服务器：监控栈与配置同步

**首次安装（维护者手工，一次）**

```bash
# 本机：按 deploy/tencent-cloud/monitoring/versions.env 下载两个 tar 包并核对 sha256，然后
scp prometheus-*.linux-amd64.tar.gz grafana-*.linux-amd64.tar.gz <server>:/root/oryxos-monitoring-dist/
# 服务器：
sudo vi /etc/oryxos-monitoring/monitoring.env   # 填写 GF_SERVER_ROOT_URL / PROMETHEUS_EXTERNAL_URL
sudo bash deploy/tencent-cloud/scripts/install-monitoring.sh --dist-dir /root/oryxos-monitoring-dist
# 宝塔面板：按 nginx 参考片段加入 /actuator 403、/grafana/、/prometheus/
```

| # | 操作（服务器） | 期望 |
| --- | --- | --- |
| 5.1 | `ss -lntp \| grep -E ':(9090\|3300)\b'` | 只出现 `127.0.0.1:9090` 与 `127.0.0.1:3300` |
| 5.2 | `for s in prometheus grafana; do systemctl show $s -p MemoryCurrent -p MemoryLimit; done` | 两者 `MemoryCurrent` 合计 ≤ 512MB（SC-009，观察 24 小时后再记录一次） |
| 5.3 | `curl -s 127.0.0.1:9090/prometheus/api/v1/targets \| python3 -m json.tool \| grep health` | `oryxos` 与 `prometheus` 两个目标均为 `up` |
| 5.4 | 公网未认证访问 `https://<域名>/grafana/`、`/prometheus/`、`/actuator/health` | 前两者 401（Basic Auth），`/actuator` 403（SC-004、SC-009） |
| 5.5 | 认证后打开 Grafana 看板 `OryxOS JVM & HTTP` | 最近 24 小时曲线，间隔 ≤ 1 分钟 |
| 5.6 | `ls -l /var/log/oryxos/ && du -sh /var/log/oryxos` | 有 `oryxos.json`；总量 ≤ 1GB；重启主机后旧文件仍在 |
| 5.7 | 合入一次只改看板 JSON 的提交 | 部署日志显示 `monitoring: grafana changed, restarted`，Prometheus 未重启 |
| 5.8 | 比对 `/opt/oryxos/monitoring-config/server-config.sha256` 与本地 `sha256sum` 结果 | 100% 一致（SC-007） |
| 5.9 | 在验证分支故意写错 `prometheus.yml` 并部署 | OryxOS 已切到新版本且健康；监控链接回到原配置；部署工作流失败并指出 `promtool` 错误 |
| 5.10 | `journalctl -u oryxos -o cat \| grep '^{' \| python3 -c 'import sys,json;[print(json.loads(l)["message"]) for l in sys.stdin if json.loads(l)["level"]=="ERROR"]'` | 可按级别过滤 journal 中的 JSON 日志（启动诊断可读性说明） |
| 5.11 | `systemctl show oryxos -p MemoryCurrent`，与重构前的 417MB 对比 | 记录指标采集与 JSON 编码带来的增量 |

## 6. US5：工程基线说明可执行

按 `docs/EngineeringBaseline.md` 中的命令从头执行一遍：每条命令都能运行，结果与描述一致；`oryxos-init` DoD 逐项都有"已满足 / 未满足及原因"（SC-008 计时 ≤ 30 分钟）。
