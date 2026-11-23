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
| 5.5 | 认证后打开 Grafana 看板 | 维护者先通过 Basic Auth，再以 `admin` 登录，子路径 `/grafana/` 正常；`OryxOS` 文件夹下已有预配置看板 `OryxOS JVM & HTTP`（标签 `oryxos`） | 看板存在已确认；数据曲线待 T063 部署后查看 |
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

## 待完成

- 新版本部署后（T063）：`oryxos` 目标变为 up；`/var/log/oryxos/oryxos.json` 开始写入；记录 OryxOS 的 `MemoryCurrent` 并与 417MB 对比（T076）。
- 观察 24 小时后再记录两个服务的 `total_rss`，以及看板中 24 小时曲线的采样间隔（SC-009）。
