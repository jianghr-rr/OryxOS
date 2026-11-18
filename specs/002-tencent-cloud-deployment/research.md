# Research & Architectural Decisions: 腾讯云单机安全部署与运维体系

**Feature**: `002-tencent-cloud-deployment`  
**Date**: 2026-11-18  
**Spec**: [spec.md](file:///Users/jianghaoran/github/OryxOS/specs/002-tencent-cloud-deployment/spec.md)

## 1. 公网访问边界与宝塔 Nginx 挂载方式

- **Decision**: 使用已备案主域名 `curlyhair.cn` 下的**独立子域名**（建议 `oryxos.curlyhair.cn`），通过服务器现有宝塔面板 Nginx 配置 **HTTPS（Let's Encrypt / 宝塔托管证书）+ HTTP Basic Auth + 限流**，反向代理至本机 `127.0.0.1:8080`。
- **Rationale**:
  - 经检查 [api.js](file:///Users/jianghaoran/github/OryxOS/oryxos-web/src/main/resources/static/admin/api.js#L30)，`/admin/` 管理台前端使用根相对路径 `fetch('/api/v1...')` 并发起 `same-origin` 请求。使用独立子域名可零改动直接兼容 `/admin/` 与 `/api/v1/**`，无需引入 subpath 重写或修改应用 `context-path`。
  - 宝塔面板已在本机运行，可复用其成熟的站点头/证书签发/续期与站点访问控制（`auth_basic`）。
  - 安全加固细节：在 Nginx 侧显式配置 `proxy_pass http://127.0.0.1:8080`，保留 `X-Forwarded-*` 头；建议在公网反代层对 `/swagger-ui` 与 `/v3/api-docs` 一并纳入 Basic Auth 保护（或直接返回 `404/403` 仅允许本机回环查看），并配置 `limit_req_zone` 限制单 IP 暴力探测。
- **Alternatives considered**:
  - *子路径挂载（`www.curlyhair.cn/oryxos/`）*：会破坏 `/admin/` 对 `/api/v1/**` 的根路径请求，需改动后端路由或维护脆弱的 Nginx `sub_filter`，故放弃。
  - *仅 SSH 隧道*：虽暴露面最小，但用户已明确选择方案 B（域名 + HTTPS + Basic Auth）以便浏览器直接访问。

## 2. GitHub Actions CI/CD 构建与传输策略

- **Decision**: 在 GitHub Actions `ubuntu-latest` Runner 上执行 Java 21 构建与回归门禁（`./mvnw -B -ntp verify`），产出的单个 Spring Boot Fat JAR（`oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar`）连同 `.sha256` 校验文件及部署模板，通过 `scp` 推送至腾讯云主机 `/opt/oryxos/releases/<short-sha>/`；通过 GitHub Secrets 注入 SSH 密钥（`umask 077`）与运行时密钥，并在 `if: always()` 中清理 Runner 临时凭证。
- **Rationale**:
  - 参照 `/Users/jianghaoran/github/monorepo-huashui-ai/.github/workflows/docker-tanstack-app.yml` 的成熟实践，全部敏感配置（SSH 私钥/IP/端口/用户、DeepSeek API Key、飞书 Webhook URL）均存储在 GitHub Repository Secrets，不进入 Git 仓库。
  - 与 `tanstack-app` 需在服务器端 `docker build`（因跨海推送大 Docker 镜像卡顿）不同，OryxOS 的 Fat JAR 体积轻量（仅单个可执行 JAR），在 GitHub Runner 上编译与跑 `verify` 可避免在 2C4G 云主机上占用大量 CPU/内存（且不与宝塔/同机 Grafana+Influx 等现有服务争抢资源）。
  - 注意 `pom.xml` 中 `maven-surefire-plugin` 默认配置了 `-Xms3g -Xmx3g`，在 GitHub `ubuntu-latest`（通常 7GB–16GB RAM）上可正常运行，而若在 4GiB 云主机上跑构建则会直接 OOM；因此 **Runner 构建 + 产物下发** 是唯一稳妥的选择。
- **Alternatives considered**:
  - *传源码到云主机本地 Maven 构建*：2C4G 主机同跑宝塔与监控服务，Surefire 默认 `-Xmx3g` 会触发 OOM，且增加云主机 JDK/Maven 缓存负担，故放弃。
  - *Docker 容器化部署*：增加镜像层管理与 SQLite 卷挂载复杂度，且用户明确要求 `Java 21 + systemd + Fat JAR + secrets.local.yaml (0600)` 原生托管，故放弃。

## 3. Java 21 运行环境与 2C4G 主机内存预算

- **Decision**: 云主机安装 Temurin / OpenJDK 21（`x86_64` RPM 或解压至 `/opt/java/jdk-21` 供 `systemd` 显式引用，不影响系统其他服务的默认 Java），`systemd` 启动参数限制堆内存为 `-Xms256m -Xmx768m`（或 `-Xmx1024m` 视同机负载微调），使用 G1GC（默认，低内存占用；避免在 4GiB 共享主机上启用大堆 ZGC 预分配）。
- **Rationale**:
  - 目标 CVM 为 2核 4GiB，且从控制台实例名称可知同机可能运行宝塔 Nginx 与 Grafana/InfluxDB 等监控组件。将 OryxOS JVM 堆上限控制在 `768m`（总 RSS 约 `1GB` 以内），可为 OS 页缓存（SQLite 性能依赖）、Nginx 与同机服务留足安全余量，防止 OOM Killer 误杀。
  - `logback-spring.xml` 将日志输出到 `STDERR`，由 `systemd` 统一接管（写入 `journald` 并通过 `/etc/systemd/journald.conf` 限制 `SystemMaxUse=500M` 或导出至 `/var/log/oryxos/` 配合 `logrotate`）。
- **Alternatives considered**:
  - *沿用 `pom.xml` 测试用的 `-Xms3g -Xmx3g` ZGC 参数*：在 4GiB 主机上几乎必然触发 OOM，不可用于生产 `systemd` 启动参数。

## 4. 目录隔离、权限模型与服务托管（`systemd`）

- **Decision**: 创建专用无登录系统用户 `oryxos`，严格分离四类目录：
  - `/opt/oryxos/releases/<version>/oryxos-boot.jar` + `/opt/oryxos/current` 软链接（程序包，仅发布时变更）
  - `/var/lib/oryxos/workspace/`（工作区根目录：含 `runtime.yaml`、`0600` 的 `secrets.local.yaml`、`oryxos.db` + `-wal`/`-shm`、`agents/`、`memory/`、`output/`、进程锁）
  - `/var/log/oryxos/`（若从 `journald` 落盘或保留部署日志）
  - `/var/backups/oryxos/`（工作区一致性备份归档）
- **Rationale**:
  - 彻底隔离程序包与状态数据，升级/回滚只切换 `/opt/oryxos/current` 软链接并重启 `oryxos.service`，绝不触碰 `/var/lib/oryxos/workspace/`。
  - `oryxos` 服务采用 `systemd` 的 `Type=simple`、`Restart=on-failure`、`RestartSec=5s`、`TimeoutStopSec=30s`（保障优雅停机释放 SQLite 锁与文件锁，避免下次启动报退出码 `4` `WORKSPACE_BUSY`）。
  - `secrets.local.yaml` 属主为 `oryxos:oryxos`，权限强制 `0600`；由 GitHub Actions 部署时通过安全通道渲染或由首期初始化脚本写入，且 `runtime.yaml` 仅使用 `${VAR_NAME}` 引用。
- **Alternatives considered**:
  - *使用 `root` 运行服务或把工作区放在 `/opt/oryxos/` 下*：违反最小权限原则，且发布清理旧目录时存在误删 SQLite 数据库的风险。

## 5. 发布切换、健康检查与回滚策略

- **Decision**: GitHub Actions 部署脚本采用“**先传包校验 → 停旧服务释放工作区锁 → 原子切换 `current` 软链接 → 启动 `oryxos.service` → 轮询 `http://127.0.0.1:8080/api/v1/health`（最多 60s）→ 失败则自动切回 `previous` 软链接并重启**”的发布事务，最多保留最近 `5` 个历史版本目录。
- **Rationale**:
  - 由于 OryxOS 单工作区强制互斥锁（同一时间仅允许一个 `serve` 持有工作区锁，冲突退出码 `4`），无法采用双实例蓝绿端口并存，必须“先优雅停旧、确认进程退出后再启新”。
  - 自动记录上一版本目标到 `/opt/oryxos/previous`，一旦新版本健康探针失败或触发 `PAUSED_RECORDING`（`503`），脚本可在 30 秒内切回上一版本并拉起，且保留失败日志供排查。
- **Alternatives considered**:
  - *直接覆盖单个 `oryxos.jar` 文件*：一旦新包损坏或启动失败，无法在无重传的情况下立即回滚。

## 6. SQLite 一致性备份与恢复策略

- **Decision**: 每日凌晨通过 `systemd timer`（或 `cron`）执行备份脚本：使用 `sqlite3 /var/lib/oryxos/workspace/oryxos.db ".backup '/tmp/oryxos-backup/oryxos.db'"`（不直接 `cp` 正在写入的 WAL 数据库），同时打包 `runtime.yaml`、`agents/`、`memory/`（**排除或单独加密处理 `secrets.local.yaml`，或仅在同机受控 `0700` 备份目录保留**）生成带时间戳的 `.tar.gz` 到 `/var/backups/oryxos/`，本地保留最近 `14` 份。
- **Rationale**:
  - SQLite 在 WAL 模式下直接 `cp` 主库文件可能漏掉 `-wal` 中的已提交事务导致恢复时数据不一致或损坏；`sqlite3 .backup` API 可在服务在线运行期间安全生成一致的数据库快照。
- **Alternatives considered**:
  - *每次备份前停止 `oryxos.service`*：会导致定时任务或管理台短暂不可用，非必要停机。
