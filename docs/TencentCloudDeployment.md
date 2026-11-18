# OryxOS 腾讯云单机部署与运维指南

本文档描述如何通过 **GitHub Actions + 宝塔面板 Nginx + systemd** 将 OryxOS 安全部署到腾讯云 CVM（2核 4GiB，CentOS 系 x86_64）。所有真实公网 IP、宝塔面板端口、SSH 凭证与业务密钥均已脱敏，严禁写入 Git 仓库。

---

## 1. 架构与安全边界总览

- **进程绑定**：OryxOS 仅监听 `127.0.0.1:8080`（默认配置见 [application.yml](file:///Users/jianghaoran/github/OryxOS/oryxos-boot/src/main/resources/application.yml)），**严禁**在腾讯云安全组或系统防火墙放行 `8080` 端口。
- **公网入口**：使用已备案域名 `curlyhair.cn` 下的独立子域名（建议 `oryxos.curlyhair.cn`），由宝塔面板 Nginx 托管 **HTTPS（强制跳转）+ HTTP Basic Auth + 单 IP 限流**，并屏蔽 `/swagger-ui` 与 `/v3/api-docs` 公网访问。
- **目录分离**：
  - 程序包：`/opt/oryxos/releases/<version>/`（`current` / `previous` 软链接）
  - 运维脚本：`/opt/oryxos/bin/`
  - 持久化工作区：`/var/lib/oryxos/workspace/`（`oryxos:oryxos`，`0750`，含 `0600` 的 `secrets.local.yaml`、`runtime.yaml`、`oryxos.db`、`agents/`、`memory/`、`output/`）
  - 备份归档：`/var/backups/oryxos/`（`root:root`，`0700`，每日 `sqlite3 .backup` 快照保留 14 天）

---

## 2. 一次性云主机初始化

1. **安装 Java 21**：在腾讯云主机安装 OpenJDK / Temurin 21（x86_64），并确保 `/opt/java/jdk-21/bin/java` 可用（或系统 `java -version` 为 21，初始化脚本会自动创建软链接）。
2. **上传并执行初始化脚本**：将仓库 `deploy/tencent-cloud/` 目录同步到云主机临时目录后执行：
   ```bash
   sudo bash deploy/tencent-cloud/scripts/bootstrap-server.sh
   ```
   该脚本幂等完成：`oryxos` 系统用户创建、目录与权限隔离、`oryxos.service` 与 `oryxos-backup.timer` 注册启用。
3. **配置宝塔面板反向代理**：
   - 在域名 DNS 为 `oryxos.curlyhair.cn` 添加 A 记录指向云主机。
   - 在宝塔面板新建站点（纯静态），申请并开启 **强制 HTTPS**。
   - 按 [oryxos-baota-vhost.conf.tpl](file:///Users/jianghaoran/github/OryxOS/deploy/tencent-cloud/nginx/oryxos-baota-vhost.conf.tpl) 配置 Basic Auth（`.htpasswd` 权限 `0640`）、请求限流与反代规则。

---

## 3. 配置 GitHub Repository Secrets

在 GitHub 仓库 `Settings -> Secrets and variables -> Actions` 配置以下 Secrets：

- `TENCENT_SSH_HOST`：腾讯云 CVM 公网 IP
- `TENCENT_SSH_PORT`：SSH 端口（默认 `22`）
- `TENCENT_SSH_USER`：具备 `sudo` 权限的部署用户名
- `TENCENT_SSH_KEY`：SSH 私钥（对应公钥已加入服务器 `~/.ssh/authorized_keys`）
- `DEEPSEEK_API_KEY`：DeepSeek API Key（用于渲染服务器 `0600` 的 `secrets.local.yaml`）
- `FEISHU_WEBHOOK_URL`：飞书机器人 Webhook URL（用于定时推送）

触发方式：推送到 `main` 分支（匹配 `oryxos-*/**`、`pom.xml`、`deploy/tencent-cloud/**` 或 workflow 文件），或在 GitHub Actions 页面手动触发 `Deploy OryxOS to Tencent Cloud`。

---

## 4. 常用运维与故障恢复

### 查看服务与日志
```bash
sudo systemctl status oryxos
sudo journalctl -u oryxos -n 200 --no-pager
curl -fsS http://127.0.0.1:8080/api/v1/health
curl -fsS http://127.0.0.1:8080/api/v1/runtime/state
```

### 手动备份与还原演练
```bash
# 手动触发一次在线一致备份（不中断服务）
sudo /opt/oryxos/bin/backup-workspace.sh

# 停服后从指定归档还原
sudo systemctl stop oryxos
sudo /opt/oryxos/bin/restore-workspace.sh /var/backups/oryxos/oryxos-backup-YYYYMMDD-HHMMSS.tar.gz
sudo systemctl start oryxos
```

### 版本回滚
```bash
sudo /opt/oryxos/bin/rollback.sh
```

### `PAUSED_RECORDING`（HTTP 503）人工恢复
当磁盘空间不足或持久化写入异常导致运行时进入 `PAUSED_RECORDING` 保护状态时：
1. 先排查磁盘空间（`df -h`）、`journalctl -u oryxos` 日志与 `/api/v1/executions` 记录。
2. 确认底层存储正常后，通过 `/admin/` 管理台或本机接口执行人工确认恢复：
   ```bash
   curl -fsS -X POST http://127.0.0.1:8080/api/v1/runtime/recover
   ```
