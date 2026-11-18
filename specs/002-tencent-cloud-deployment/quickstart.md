# Quickstart & Validation Guide: 腾讯云单机安全部署

**Feature**: `002-tencent-cloud-deployment`  
**Date**: 2026-11-18  

本指南用于验证整套腾讯云部署与运维体系可端到端运行（具体可执行脚本与模板将在 `speckit-tasks` / `speckit-implement` 阶段生成至 `deploy/tencent-cloud/` 与 `.github/workflows/`）。

## 1. 前置准备（一次性云主机初始化）

1. **DNS 与宝塔面板**：
   - 在域名控制台为 `oryxos.curlyhair.cn`（示例子域名）添加 A 记录指向腾讯云主机。
   - 在宝塔面板新建站点，申请并开启 HTTPS（强制 HTTPS），配置 Basic Auth（生成 `.htpasswd`），并按 [deployment-contract.md](file:///Users/jianghaoran/github/OryxOS/specs/002-tencent-cloud-deployment/contracts/deployment-contract.md#L4) 配置反向代理到 `http://127.0.0.1:8080` 与请求限流。
   - 确认腾讯云安全组 **未放行 `8080` 端口**。
2. **云主机运行基线**：
   - 运行仓库提供的初始化脚本（`bootstrap-server.sh`）：安装/校验 Java 21 与 `sqlite3`、创建 `oryxos` 用户、初始化 `/opt/oryxos`、`/var/lib/oryxos/workspace`、`/var/backups/oryxos`，安装 `oryxos.service` 与定时备份 timer。
3. **GitHub Repository Secrets**：
   - 配置 `TENCENT_SSH_HOST`、`TENCENT_SSH_USER`、`TENCENT_SSH_KEY`（以及 `TENCENT_SSH_PORT`、`DEEPSEEK_API_KEY`、`FEISHU_WEBHOOK_URL`）。

## 2. 端到端验收场景

1. **CI/CD 首次发布（US-3）**：
   - 推送代码至目标分支或手动触发 `deploy-tencent-cloud.yml`（`workflow_dispatch`）。
   - 验证 Runner 通过 `./mvnw -B -ntp verify` 打包 Fat JAR，安全传输并切换 `/opt/oryxos/current`，本机 `http://127.0.0.1:8080/api/v1/health` 返回 `200`。
2. **公网访问安全边界验证（US-1）**：
   - 外部直接请求 `<公网IP>:8080` → 连接拒绝或超时（`SC-001`）。
   - 外部未带 Basic Auth 请求 `https://oryxos.curlyhair.cn/admin/` → 返回 `401 Unauthorized`。
   - 带正确 Basic Auth 访问 `https://oryxos.curlyhair.cn/admin/` → 页面加载正常且 `/api/v1/health`、`/api/v1/profiles` 返回有效数据。
3. **重启自愈与数据持久化验证（US-2）**：
   - 通过管理台或 API 创建一条测试会话；在云主机执行 `sudo reboot`（或 `sudo systemctl kill -s KILL oryxos`）。
   - 验证 60 秒内服务自动恢复在线，且重启前的会话记录仍可查询（`SC-002`）。
4. **备份与恢复演练（US-2）**：
   - 执行一次手动备份脚本生成 `/var/backups/oryxos/oryxos-backup-*.tar.gz`。
   - 停服后使用恢复脚本演练还原至测试工作区（或原地还原），重启服务并确认健康检查与历史数据查询 `100%` 正常（`SC-005`）。
5. **故障回滚演练（US-3）**：
   - 部署一个故意失败的版本（或触发回滚脚本 `rollback.sh`），验证 3 分钟内自动/手动切回 `previous` 版本，工作区数据零丢失（`SC-003`）。
6. **DeepSeek + 飞书 Webhook 云端联调（US-4）**：
   - 在 `/admin/` 手动触发已迁入云端的天气定时推送任务（或 `POST /api/v1/schedules/{id}/run`），确认飞书群收到消息、执行历史落库，且日志与审计中无明文 Key/Webhook 泄露。
