# 契约：服务器侧组件、配置同步与访问入口

对应需求：FR-001a、FR-007a、FR-008、FR-020a、SC-007、SC-009。所有路径与端口都不包含公网地址或域名（FR-025）。

## 1. 目录与所有权

| 路径 | 所有者 / 权限 | 来源 | 内容 |
| --- | --- | --- | --- |
| `/opt/monitoring/prometheus/<ver>/`，`current` → `<ver>` | root:root 0755 | 安装脚本（一次） | Prometheus 程序，含 `promtool` |
| `/opt/monitoring/grafana/<ver>/`，`current` → `<ver>` | root:root 0755 | 安装脚本（一次） | Grafana 程序 |
| `/var/lib/prometheus` | prometheus:prometheus 0750 | 安装脚本 | TSDB 数据（保留 15 天或 1GB，先到先清） |
| `/var/lib/grafana` | grafana:grafana 0750 | 安装脚本 | Grafana SQLite 与插件目录 |
| `/etc/oryxos-monitoring/monitoring.env` | root:root 0640 | 维护者手工创建 | `GF_SERVER_ROOT_URL`、`PROMETHEUS_EXTERNAL_URL`（含域名，**不入库**） |
| `/etc/oryxos-monitoring/secrets/grafana-admin-password` | root:grafana 0640 | 安装脚本生成随机值（已存在则不覆盖） | Grafana 管理员密码（**不入库、不打印**） |
| `/opt/oryxos/monitoring-config/<sha256>/`，`current` → `<sha256>` | root:root 0755 | 每次发布同步 | 来自仓库 `deploy/tencent-cloud/server-config/monitoring/` |
| `/etc/systemd/system/oryxos.service.d/10-observability.conf` | root:root 0644 | 每次发布同步 | 来自仓库 `deploy/tencent-cloud/server-config/systemd/oryxos.service.d/` |
| `/etc/systemd/system/{prometheus,grafana}.service` | root:root 0644 | 每次发布同步 | 来自仓库 `deploy/tencent-cloud/server-config/systemd/` |
| `/var/log/oryxos/` | oryxos:oryxos 0750（已存在） | bootstrap（已有） | OryxOS JSON 日志 |

## 2. 仓库内文件（新增）

```text
deploy/tencent-cloud/
├── monitoring/
│   └── versions.env                       # PROMETHEUS_VERSION/SHA256、GRAFANA_VERSION/SHA256
├── scripts/
│   ├── install-monitoring.sh              # 一次性幂等安装（离线校验 + 安装 + 启用）
│   └── sync-server-config.sh              # 发布时调用：drop-in 段 / 监控段
└── server-config/
    ├── systemd/
    │   ├── oryxos.service.d/10-observability.conf
    │   ├── prometheus.service
    │   └── grafana.service
    └── monitoring/
        ├── prometheus/prometheus.yml
        └── grafana/
            ├── grafana.ini
            ├── provisioning/datasources/prometheus.yaml
            ├── provisioning/dashboards/oryxos.yaml
            └── dashboards/oryxos-jvm-http.json
```

## 3. systemd 单元要点（systemd 219 兼容）

**`oryxos.service.d/10-observability.conf`**

```ini
[Service]
Environment=SPRING_PROFILES_ACTIVE=prod
Environment=ORYXOS_LOG_DIR=/var/log/oryxos
```

不修改 `ExecStart` 与 `-Xmx768m`（JVM 堆上限不变）。

**`prometheus.service` / `grafana.service` 共同约束**

| 指令 | 值 |
| --- | --- |
| `User` / `Group` | `prometheus` / `grafana`（系统用户，`/sbin/nologin`） |
| `MemoryAccounting` | `yes` |
| `MemoryLimit` | Prometheus `256M`，Grafana `512M`（合计 768MB，SC-009） |
| `Environment` | Prometheus `GOMEMLIMIT=200MiB`，Grafana `GOMEMLIMIT=400MiB` |
| `EnvironmentFile` | `-/etc/oryxos-monitoring/monitoring.env` |
| `OOMScoreAdjust` | `500`（内存紧张时优先于 OryxOS 被回收） |
| `NoNewPrivileges` / `ProtectSystem` / `ProtectHome` / `PrivateTmp` | `yes` / `full` / `yes` / `yes` |
| `Restart` | `on-failure` |

禁止使用（219 不支持或语义不同）：`MemoryMax=`、`StateDirectory=`、`LogsDirectory=`、`ProtectSystem=strict`、`ReadWritePaths=`。脚本中禁止 `systemctl show --value`，改用 `systemctl show -p X | cut -d= -f2`。

**监听与关键参数**

- Prometheus：`--web.listen-address=127.0.0.1:9090`、`--web.route-prefix=/prometheus`、`--web.external-url=${PROMETHEUS_EXTERNAL_URL}`、`--storage.tsdb.retention.time=15d`、`--storage.tsdb.retention.size=1GB`；采集间隔 15 秒；目标 `127.0.0.1:8080/actuator/prometheus`（job `oryxos`）与自身（job `prometheus`）。
- Grafana：`http_addr=127.0.0.1`、`http_port=3300`、`serve_from_sub_path=true`；`auth.anonymous.enabled=false`、`users.allow_sign_up=false`、`auth.basic.enabled=false`；`admin_password=$__file{...}`；`unified_alerting.enabled=false`、`analytics.reporting_enabled=false`、`analytics.check_for_updates=false`、`plugins.preinstall_disabled=true`。

## 4. nginx 参考片段

宝塔面板手工维护（FR-020a）。仓库只更新 `deploy/tencent-cloud/nginx/oryxos-baota-vhost.conf.tpl`：

```nginx
location ^~ /grafana/ {
    auth_basic "OryxOS Admin Restricted";
    auth_basic_user_file /www/server/nginx/conf/oryxos.htpasswd;
    proxy_set_header Authorization "";       # 不把 Basic Auth 凭证转给 Grafana
    proxy_set_header Host $host;
    proxy_pass http://127.0.0.1:3300;
}

location ^~ /prometheus/ {
    auth_basic "OryxOS Admin Restricted";
    auth_basic_user_file /www/server/nginx/conf/oryxos.htpasswd;
    proxy_pass http://127.0.0.1:9090;
}
```

同时加入 [observability.md §4](./observability.md#4-公网访问控制nginx-参考片段的变更) 中的 `/actuator` 403 规则。腾讯云安全组和防火墙不得放行 9090、3300。

## 5. 一次性安装脚本 `install-monitoring.sh`

```text
用法：sudo bash install-monitoring.sh --dist-dir <含两个 tar 包的目录>
```

依次执行，每步都可以重复执行：

1. 预检：root、systemd 版本、`127.0.0.1:9090` 与 `127.0.0.1:3300` 未被占用（被本脚本安装的服务占用视为已安装）、磁盘剩余 ≥ 3GB。
2. 按 `versions.env` 校验两个 tar 包的 sha256，不匹配即中止。
3. 创建系统用户 `prometheus`、`grafana` 与第 1 节中的目录。
4. 解压到 `<ver>/` 目录，切换 `current` 链接；执行 `grafana --version` 与 `prometheus --version` 预检（glibc 兼容），失败即中止。
5. 管理员密码文件不存在时用 `openssl rand` 生成。只打印文件路径，不打印密码。
6. `monitoring.env` 不存在时写入带注释的占位文件，并提示维护者填写域名。
7. 用仓库当前的 `server-config/` 执行一次 `sync-server-config.sh monitoring`，然后 `systemctl enable --now prometheus grafana`。
8. 输出验收命令（端口监听、`MemoryCurrent`、本机 `curl` 检查）。

脚本不修改 nginx、防火墙、Docker 和 OryxOS 主服务。

## 6. 发布时同步 `sync-server-config.sh`

```text
用法：sync-server-config.sh oryxos-dropin <release_dir>/server-config
      sync-server-config.sh oryxos-dropin --restore
      sync-server-config.sh monitoring     <release_dir>/server-config
```

| 阶段 | 调用时机（`deploy-release.sh`） | 变化判定 | 生效动作 | 失败处理 |
| --- | --- | --- | --- | --- |
| `oryxos-dropin` | 停旧服务后、启动新版本前 | 目标文件与新文件 sha256 不同 | 临时文件 + `mv` 原子替换，保留 `.prev`，`systemctl daemon-reload` | 恢复 `.prev` 并 `daemon-reload`，退出非零；`deploy-release.sh` 随后执行 `auto_rollback`（旧服务此时已停、`current` 已切换，不能直接退出） |
| `monitoring` | OryxOS 健康检查通过后 | 新目录的内容 hash 与 `current` 不同；单元文件 sha256 不同 | 复制到 `<hash>/`，执行 `promtool check config`，切换 `current` 链接；单元变化时 `daemon-reload`；只重启配置有变化的服务 | 链接切回原目标并重启受影响服务，发布以非零码失败；**不回滚 OryxOS** |

- 监控服务尚未安装（单元不存在）时，`monitoring` 阶段只同步文件、跳过重启，并输出 `monitoring not installed; skipped restart` 提示，不算失败。这样可以在执行安装脚本前就合入代码。
- 每次同步结束都在 `/opt/oryxos/monitoring-config/server-config.sha256` 写出逐文件清单，SC-007 用它与仓库文件比对。
- 保留最近 3 份 `<hash>/` 目录。
- `oryxos-dropin --restore`：撤销最近一次 drop-in 同步。有 `.prev` 时恢复 `.prev`，本次为新建时删除该 drop-in，然后 `daemon-reload`。
- 自动回滚（`deploy-release.sh` 中的 `auto_rollback`）在启动回滚目标前处理 drop-in：回滚目标含 `server-config/` 时用它执行 `oryxos-dropin`，不含时执行 `oryxos-dropin --restore`，回滚后不残留新版本的 drop-in（FR-020a）。
- 发布 004 之前的旧发布包时，包里没有 `server-config/`，两个阶段都跳过。
