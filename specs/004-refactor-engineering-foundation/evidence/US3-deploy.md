# US3：分支保护与合入后的部署验证（T063）

日期 2026-11-23。

## 分支保护

通过 GitHub 公开 API（`GET /repos/jianghr-rr/OryxOS/rulesets` 与 `/rules/branches/main`）读取的实际配置：

| 规则 | 配置 | 对照 FR-019a |
| --- | --- | --- |
| 规则集 | `main`（id 27880595），`enforcement: active`，作用于分支，创建于 2026-11-23T07:09:47Z | 符合 |
| 禁止删除 | `deletion` | 符合 |
| 禁止强制推送 | `non_fast_forward` | 符合 |
| 必须通过 PR | `pull_request`，`required_approving_review_count: 0` | 符合（单人维护，设为 1 会无法合入自己的 PR） |
| 必过检查 | `Full Verify`、`Dependency Scan`；`strict_required_status_checks_policy: true`（合并前分支须与 main 同步） | 符合 |

- 规则集创建早于 PR #3 合入（部署目录时间戳 09:06:50Z），PR #3 即在保护下通过 PR 合入。
- Bypass list 无法通过匿名 API 读取；维护者已在 GitHub 上自行验证保护生效，本次会话未复核具体验证步骤。
- 必过检查名称与 `docs/EngineeringBaseline.md` §8 一致。

## 合入与部署

| 项 | 结果 |
| --- | --- |
| 合入 | PR #2（`004-refactor-engineering-foundation`）合入 main |
| 首次部署（Run 14） | 失败：部署用户的免密 sudo 白名单不包含安装 `sync-server-config.sh` 的命令，报 `no tty present` |
| 修复 | PR #3（`004-fix-deploy-sudo`，merge 提交 `5626ac4`）：`sync-server-config.sh` 随发布目录上传，由以 root 运行的 `deploy-release.sh` 安装，不新增 sudo 命令 |
| 修复后部署 | 服务器正在运行 `/opt/oryxos/releases/20261123090650-5626ac4/oryxos-boot.jar`，说明发布目录切换与健康检查已通过 |

部署工作流运行页面本次未查询（本机没有 `gh`，匿名 GitHub API 被限流），结论依据是服务器上的实际运行版本。

## 服务器状态

| 检查 | 结果 | 结论 |
| --- | --- | --- |
| 本机健康探针 | `/actuator/health` 为 `UP`，含 liveness、readiness 分组 | 通过 |
| 生产日志 | `/var/log/oryxos/oryxos.json` 已写入，为 logstash 格式 JSON | 通过，drop-in 中的 `prod` profile 已生效 |
| Prometheus 采集 | `oryxos` 目标为 `up` | 通过 |
| traceId | 10/10 在服务器日志中命中且互不相同 | 通过 |

详细读数见 [US2-server.md](./US2-server.md) 的"新版本部署后复查"一节。

## SC-007：服务器配置与仓库逐文件一致

服务器上的 `/opt/oryxos/monitoring-config/server-config.sha256`（由 `sync-server-config.sh` 在每次同步后写出），与本地用 `git archive 5626ac4 deploy/tencent-cloud/server-config` 导出的文件逐个计算的 sha256 进行 `diff`：

| 文件 | sha256 前 12 位 | 一致 |
| --- | --- | --- |
| `monitoring/grafana/dashboards/oryxos-jvm-http.json` | `8ed6bf97f8f7` | 是 |
| `monitoring/grafana/grafana.ini` | `5c3859838430` | 是 |
| `monitoring/grafana/provisioning/dashboards/oryxos.yaml` | `045ca071b621` | 是 |
| `monitoring/grafana/provisioning/datasources/prometheus.yaml` | `fa5ac85dd07c` | 是 |
| `monitoring/prometheus/prometheus.yml` | `ac8aff0e591c` | 是 |
| `systemd/grafana.service` | `370d5a875a4d` | 是 |
| `systemd/oryxos.service.d/10-observability.conf` | `914c9f2fba6a` | 是 |
| `systemd/prometheus.service` | `c271be1e5768` | 是 |

8/8 一致，清单中没有 `MISSING` 条目。SC-007 中"服务器配置与该提交仓库内容一致"与"OryxOS 输出 JSON 日志"两项通过。

## 部署工作流的分支限制

核对时发现 `deploy-tencent-cloud.yml` 有两处缺口：

- `workflow_dispatch` 不限分支，任何分支都能手动触发生产部署，绕过上面的分支保护。
- 并发组为 `deploy-tencent-cloud-${{ github.ref }}`，`main` 推送与其他分支的手动部署可以同时对同一台服务器停服、切换版本。

分支 `004-deploy-guard` 给 `quality` 与 `build-and-deploy` 两个 job 都加了 `if: github.ref == 'refs/heads/main'`，并发组固定为 `deploy-tencent-cloud`。同一提交给看板 JSON 补了 `description` 字段，用作 quickstart 5.7 的验证改动。

## quickstart 5.9：`prometheus.yml` 写错时监控配置不切换

2026-11-23 17:58 前后由维护者在服务器（主机名已脱敏）上以 root 执行，当时运行的是 `5626ac4` 发布。步骤：把当前发布的 `server-config` 复制到 `mktemp -d` 临时目录，在其中的 `prometheus.yml` 末尾追加 `scrape_configs: [ {job_name: broken`，然后执行 `/opt/oryxos/bin/sync-server-config.sh monitoring <临时目录>/server-config`。

```text
Checking /opt/oryxos/monitoring-config/ee134d8f63b5a894/prometheus/prometheus.yml
  FAILED: parsing YAML file .../prometheus.yml: yaml: line 16: did not find expected ',' or '}'
[sync-server-config] ERROR: monitoring: promtool rejected prometheus.yml; keeping 08bbb76ae99dbdcc
exit=1
link-same
manifest-same
no-restart
{"status":"UP","groups":["liveness","readiness"]}
```

| 预期 | 结果 |
| --- | --- |
| 脚本非零退出并指出 `promtool` 错误 | 退出码 1，错误指向第 16 行 YAML |
| `monitoring-config/current` 链接不变 | `link-same`，仍为 `08bbb76ae99dbdcc` |
| 清单 `server-config.sha256` 不变 | `manifest-same` |
| Prometheus、Grafana 未重启 | 两者的 `ActiveEnterTimestamp` 前后一致 |
| OryxOS 保持健康 | `UP` |

"监控同步排在 OryxOS 健康检查之后、失败时保留新版本"没有在真实部署中触发，由 `deploy-release.sh` 的执行顺序和 `tools/dev/test_sync_server_config.py` 覆盖。

## 尚未完成

- quickstart 5.7：等 `004-deploy-guard` 合入并部署后，查看部署日志中是否有 `monitoring: grafana changed, restarted`，且没有 Prometheus 重启的记录。
