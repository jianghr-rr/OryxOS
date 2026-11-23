# US2：服务器监控栈首次安装（T055，quickstart §5.1–5.6）

日期 2026-11-23。目标服务器：腾讯云 CVM（OpenCloudOS，systemd 219，glibc 2.17）。主机名、公网 IP 与域名均已脱敏，下文用 `<域名>` 表示。

## 安装过程

| 步骤 | 结果 |
| --- | --- |
| 预检 | systemd 219；glibc 2.17；`/` 剩余 36G；9090、3300 空闲；`ss`、`sha256sum`、`openssl`、`tar`、`useradd`、`python3` 齐全 |
| 安装包 | 维护者本机下载 Prometheus 3.13.5（99.7M）与 Grafana 13.3.1（462M），sha256 与 `versions.env` 一致。用 FinalShell 上传时 Prometheus 包第一次只传了 30.6M、sha256 不符，重传后一致 |
| glibc 兼容 | `grafana` 二进制为 `statically linked`，不依赖 glibc |
| 部署文件 | 因改动尚未推送，从维护者本机上传当前 `deploy/tencent-cloud/` 到服务器临时目录 |
| `install-monitoring.sh` | 8 步全部完成：`promtool` 校验通过；生成 Grafana 管理员密码文件（只打印路径）；写入 `monitoring.env` 占位；两个服务启动并设置开机自启 |
| `monitoring.env` | 已填入实际站点地址，两个服务已重启 |

## 验收

| # | 检查 | 结果 | 结论 |
| --- | --- | --- | --- |
| 5.1 | `ss -ltn` | 只有 `127.0.0.1:3300` 与 `127.0.0.1:9090` | 通过 |
| 5.2 | 内存 | Prometheus `MemoryCurrent` 约 37MB。Grafana 见下节：放宽上限后 cgroup `total_rss` 224MB、`total_swap` 0 | 首次读数通过；24 小时后复测待做 |
| 5.3 | 采集目标 | `prometheus` 为 up；`oryxos` 为 down，因为带 `/actuator/prometheus` 的新版本尚未部署 | 部分通过，待 T063 部署后复查 |
| 5.4 | 公网未认证访问 | `/grafana/` 401、`/prometheus/` 401、`/actuator/health` 403、`/actuator/prometheus` 403；原有 `/admin/` 仍为 401 | 通过 |
| 5.5 | 认证后打开 Grafana 看板 | 维护者先通过 Basic Auth，再以 `admin` 登录，子路径 `/grafana/` 正常；`OryxOS` 文件夹下已有预配置看板 `OryxOS JVM & HTTP`（标签 `oryxos`） | 看板存在已确认；部署后已有数据，见下文"看板数据（5.5）" |
| 5.6 | `/var/log/oryxos/oryxos.json` | 新版本尚未部署，`prod` drop-in 未生效 | 待 T063 |
| — | 本机健康接口 | `prometheus/-/ready` 返回 `Prometheus Server is Ready.`；Grafana `/api/health` 返回 `database: ok`、`version: 13.3.1` | 通过 |

## Grafana 内存上限调整（偏差 D-7）

原设计为 Grafana `MemoryLimit=256M`、`GOMEMLIMIT=200MiB`。安装后实测：

- cgroup `total_rss` 约 225MB、`total_cache` 约 20MB，进程 RSS 约 342MB，已超出上限；
- 启动后约 90 秒 `memory.failcnt` 达 63720，内核日志没有 OOM。超出部分被换到 swap 上，因为 cgroup v1 的 `MemoryLimit` 不限制 swap。

维护者选择放宽 Grafana：改为 `MemoryLimit=512M`、`GOMEMLIMIT=400MiB`；Prometheus 保持 256M；SC-009 合计目标由 512MB 改为 768MB。修改后通过 `sync-server-config.sh monitoring` 同步，只重启了 Grafana：

| 指标 | 值 |
| --- | --- |
| `MemoryLimit` | 536870912 |
| `total_rss` | 235048960（224MB） |
| `total_cache` | 284684288（文件页缓存，可回收） |
| `total_swap` | 0 |
| `MemoryCurrent` | 529629184（包含缓存，接近上限属于正常） |

**口径说明**：cgroup v1 的 `MemoryCurrent` 包含文件页缓存，长期会贴近上限；缓存触顶时内核先回收缓存，这时 `failcnt` 会增长，但不影响服务。SC-009 的“常驻内存”因此按 `memory.stat` 中的 `total_rss` 计算。当前合计约 260MB（Grafana 224MB 加 Prometheus 约 37MB），在 768MB 以内。

## 与仓库模板的差异

服务器现有站点用的 Basic Auth 文件是 `/www/server/nginx/conf/oryxos.htpasswd`，与 nginx 模板原写的 `/www/server/panel/vhost/htpasswd/oryxos.htpasswd` 不同。新增的 `/grafana/`、`/prometheus/` 沿用实际文件，模板和 `contracts/server-config.md` 已改为实际路径。`location /` 中已加入 `proxy_set_header X-Request-ID $request_id;`。

## 新版本部署后复查（2026-11-23 17:30 前后）

运行版本为 `/opt/oryxos/releases/20261123090650-5626ac4/`（PR #3 合入后的部署，Run 14 的 sudo 问题已修复）。维护者在服务器上执行只读命令，输出由本次会话判定。

| # | 检查 | 结果 | 结论 |
| --- | --- | --- | --- |
| 5.3 | `api/v1/targets` | `oryxos` 与 `prometheus` 均为 `up` | 通过（此前 `oryxos` 为 down） |
| 5.6 | `/var/log/oryxos/` | 出现 `oryxos.json`（8336 字节），首行为 logstash 格式 JSON，`message` 以 `Starting OryxOsApplication` 开头；目录合计 16K | 写入通过，`prod` drop-in 已生效；"重启主机后旧文件仍在"未验证 |
| 5.10 | 按级别过滤 journal 中的 JSON 日志 | 解析正常，近 1 小时只有 2 条 WARN：SpringDoc 提示 `/v3/api-docs` 与 `/swagger-ui.html` 默认开启 | 通过；这两个地址在公网已被 nginx 返回 403，WARN 属既有行为，见下文"遗留" |
| — | `/actuator/health`（本机） | `{"status":"UP","groups":["liveness","readiness"]}` | 通过（SC-004 服务器本机部分） |
| — | traceId 检索（SC-003 服务器部分） | 10 个指向不存在会话的请求，`traceId` 两两不同，在 `oryxos.json` 中各命中 1 次 | 10/10 通过 |

### 内存（T076、SC-009）

口径与上文一致：cgroup v1 的 `MemoryCurrent` 含文件页缓存，常驻内存以 `memory.stat` 的 `total_rss` 为准。

| 服务 | `total_rss` | `total_cache` | `total_swap` | `MemoryCurrent` |
| --- | --- | --- | --- | --- |
| oryxos | 440242176（419.8 MiB） | 52842496 | 0 | 499187712（476.1 MiB） |
| prometheus | 31387648（29.9 MiB） | 14520320 | 0 | 47759360 |
| grafana | 228790272（218.2 MiB） | 186900480 | 270336 | 434040832 |

- **SC-009**：Prometheus 与 Grafana 的 `total_rss` 合计 248.1 MiB（260.2MB），在 768MB 以内。Grafana 有 264KB swap，量很小，24 小时复测时继续观察。
- **T076**：重构前只记录了 `MemoryCurrent`（417MB），没有 `total_rss`，因此只能按同一口径比较：现为 476.1 MiB，比重构前多约 59MB（约 14%）；其中包含日志文件写入带来的页缓存（`total_cache` 约 50MB，可回收）。现在的真实常驻内存为 419.8 MiB。JVM 堆上限 `-Xmx768m` 未变。读数取自部署后约 30 分钟，24 小时复测时一并记录。

### 看板数据（5.5）

2026-11-23 20:06 前后，维护者认证后打开 `OryxOS JVM & HTTP` 看板（时间范围 24 小时），截图由本次会话判定：

| 面板 | 读数 | 结论 |
| --- | --- | --- |
| Uptime | 4.11 分钟 | 有数据；数值小是因为 PR #5 部署刚重启 OryxOS |
| Live threads | 14 | 有数据 |
| JVM memory used | 堆约 100–150 MiB 波动，`heap max` 768 MiB | 有数据，上限与 `-Xmx768m` 一致 |
| GC pauses | G1 Young Generation 停顿时间与次数 | 有数据 |
| HTTP requests per second by uri / status | 以 `/actuator/prometheus`、`/actuator/health` 为主，另有 `/api/v1/*` | 有数据 |
| HTTP latency p50 / p95 | 有曲线 | 有数据 |
| HTTP 5xx ratio (5m) | No data | 没有 5xx 请求，因此不存在对应序列，属于预期 |

曲线从 17 点前后开始，与带 actuator 的新版本首次部署时间一致。5.5 通过。看板可改为无数据时显示 0，属于后续优化。

## 遗留

- 观察 24 小时后再记录三个服务的 `total_rss`，以及看板中 24 小时曲线的采样间隔（SC-009、5.2）。
- 重启主机后确认 `/var/log/oryxos/` 中的旧日志仍在（5.6）。
- SpringDoc 在 prod 下仍开启 `/v3/api-docs` 与 `/swagger-ui.html`，每次启动打 2 条 WARN。公网已被 nginx 屏蔽，本机仍可访问；是否在 prod 关闭 Swagger UI 需另行决定（`PublishedOpenApiTest` 依赖 `/v3/api-docs`，不能一并关闭）。
