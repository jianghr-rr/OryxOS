# Deployment & Operations Contracts: 腾讯云单机安全部署

**Feature**: `002-tencent-cloud-deployment`  
**Date**: 2026-11-18  

本文档定义 GitHub Actions CI/CD、云主机 `systemd` 托管、宝塔 Nginx 反向代理以及运维脚本之间的接口与边界契约（所有真实 IP、域名端口、密钥均已脱敏）。

---

## 1. GitHub Repository Secrets 契约

在 GitHub 仓库 `Settings -> Secrets and variables -> Actions` 中必须配置以下 Secrets（严禁写入仓库文件）：

| Secret Name | Required | Purpose | Format / Notes |
| --- | --- | --- | --- |
| `TENCENT_SSH_HOST` | Yes | 腾讯云 CVM 公网 IP | 仅存 GitHub Secrets |
| `TENCENT_SSH_PORT` | No | SSH 端口 | 默认 `22` |
| `TENCENT_SSH_USER` | Yes | 部署登录用户名 | 建议具有 `sudo` 权限的专用部署账号或受控运维账号 |
| `TENCENT_SSH_KEY` | Yes | SSH 私钥（PEM/OpenSSH） | 对应公钥提前写入服务器 `~/.ssh/authorized_keys` |
| `DEEPSEEK_API_KEY` | Yes (`FR-009`) | DeepSeek 模型密钥 | 渲染写入云端 `0600` 的 `secrets.local.yaml` |
| `FEISHU_WEBHOOK_URL` | Yes (`FR-009`) | 飞书机器人 Webhook URL | 渲染写入云端 `0600` 的 `secrets.local.yaml` |

> 可选扩展：若后续增加其他模型/MCP 凭证，按同样机制以 `secrets.local.yaml` 键名注入，不改动 `runtime.yaml` 的 `${VAR_NAME}` 引用方式。

---

## 2. 云主机目录与权限契约

| Path | Owner | Mode | Purpose |
| --- | --- | --- | --- |
| `/opt/oryxos/releases/<ver>/` | `oryxos:oryxos` | `0755` | 不可变发布版本目录（含 `oryxos-boot.jar`、`oryxos-boot.jar.sha256`） |
| `/opt/oryxos/current` | `oryxos:oryxos` | symlink | 指向当前生效的 release 目录 |
| `/opt/oryxos/previous` | `oryxos:oryxos` | symlink | 指向回滚目标 release 目录 |
| `/opt/oryxos/bin/` | `root:oryxos` | `0750` | 运维脚本目录（`deploy-release.sh`、`rollback.sh`、`backup-workspace.sh`、`restore-workspace.sh`） |
| `/var/lib/oryxos/workspace/` | `oryxos:oryxos` | `0750` | 唯一持久化工作区根目录（`ORYXOS_ROOT`） |
| `/var/lib/oryxos/workspace/secrets.local.yaml` | `oryxos:oryxos` | `0600` | 受控本地密钥文件 |
| `/var/log/oryxos/` | `oryxos:oryxos` | `0750` | 若配置独立落盘日志时使用（主日志由 `journald` 托管） |
| `/var/backups/oryxos/` | `root:root` | `0700` | 工作区离线/在线一致备份归档与 `.sha256` |

---

## 3. `systemd` 服务契约（`oryxos.service`）

- **运行用户**：`User=oryxos`、`Group=oryxos`
- **工作目录**：`WorkingDirectory=/var/lib/oryxos/workspace`
- **环境变量**：
  - `ORYXOS_ROOT=/var/lib/oryxos/workspace`
  - `SERVER_PORT=8080`
- **启动命令**：
  - 显式指定 Java 21 二进制路径（如 `/opt/java/jdk-21/bin/java`）
  - 生产 JVM 参数：`-Xms256m -Xmx768m -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8`
  - 执行目标：`-jar /opt/oryxos/current/oryxos-boot.jar --root /var/lib/oryxos/workspace serve`
- **自愈与优雅停机**：
  - `Restart=on-failure`、`RestartSec=5`
  - `TimeoutStopSec=30`（允许 Spring Boot 与 SQLite 完成 checkpoint 与文件锁释放）
  - `WantedBy=multi-user.target`（开机自启）
- **安全加固**：
  - `NoNewPrivileges=true`
  - `PrivateTmp=true`
  - `ProtectSystem=strict` + `ReadWritePaths=/var/lib/oryxos/workspace`

---

## 4. 宝塔 Nginx 反向代理与认证契约

- **站点域名**：已备案域名 `curlyhair.cn` 下的独立子域名（如 `oryxos.curlyhair.cn`，具体 DNS A 记录指向云主机）。
- **监听与 TLS**：
  - 仅对外开放 `443`（HTTPS，TLS 1.2/1.3）与 `80`（强制 301 跳转 `443`）。
  - 腾讯云安全组与服务器防火墙 **严禁放行 `8080` 端口**。
- **认证与防探测**：
  - 整站（或至少 `/`、`/admin/`、`/api/`、`/swagger-ui/`、`/v3/api-docs`）强制开启 `auth_basic`；生产环境建议对 `/swagger-ui/` 与 `/v3/api-docs` 额外限制仅本机或直接返回 `403`。
  - 配置单 IP 请求速率限制（`limit_req_zone`），防止公网扫描耗尽资源。
- **反代头与超时**：
  - `proxy_pass http://127.0.0.1:8080;`
  - `proxy_set_header Host $host;`
  - `proxy_set_header X-Real-IP $remote_addr;`
  - `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`
  - `proxy_set_header X-Forwarded-Proto $scheme;`
  - `proxy_read_timeout 120s;`（匹配模型长推理响应与 `application.yml` keep-alive 超时）

---

## 5. 健康探针与自动回滚契约

- **探针端点**：`GET http://127.0.0.1:8080/api/v1/health` 与 `GET http://127.0.0.1:8080/api/v1/runtime/state`
- **成功条件**：`HTTP 200`，且运行状态非 `PAUSED_RECORDING`。
- **回滚触发**：新版本启动后 60 秒内探针未通过，或 `systemctl is-active oryxos` 失败：
  1. 若存在 `/opt/oryxos/previous`，自动将 `/opt/oryxos/current` 切回 `previous`。
  2. 执行 `systemctl restart oryxos` 并再次探测健康端点。
  3. 输出最近 200 行 `journalctl -u oryxos -n 200` 日志并使 GitHub Actions Job 失败退出，阻断错误版本标记为成功。
