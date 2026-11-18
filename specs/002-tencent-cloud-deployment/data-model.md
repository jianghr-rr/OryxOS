# Data Model & Operational Entities: 腾讯云单机安全部署与运维体系

**Feature**: `002-tencent-cloud-deployment`  
**Date**: 2026-11-18  
**Spec**: [spec.md](file:///Users/jianghaoran/github/OryxOS/specs/002-tencent-cloud-deployment/spec.md)

本特性不修改 OryxOS 核心业务数据库表结构（`oryxos.db` 仍沿用 `001-single-node-agent-runtime` 既有 schema），而是定义云端部署与运维生命周期中的**四大核心实体及其状态流转与权限约束**。

## 1. 云端发布包 (Release Artifact)

- **存储路径**: `/opt/oryxos/releases/<timestamp>-<short-sha>/`
- **关键成员**:
  - `oryxos-boot.jar`: 由 GitHub Actions 构建并通过 `./mvnw -B -ntp verify` 的 Spring Boot Fat JAR。
  - `oryxos-boot.jar.sha256`: 构建端生成的 SHA-256 校验摘要，云主机部署脚本在切换前强制校验。
  - `release.json`（可选元数据）: 记录 `git_sha`、`built_at`、`github_run_id`。
- **指针软链接**:
  - `/opt/oryxos/current` → 当前正在运行的 release 目录。
  - `/opt/oryxos/previous` → 上一个已知健康可用的 release 目录（用于一键回滚）。
- **保留规则**: 最多保留最近 `5` 个 release 目录（排除 `current` 与 `previous` 指向的目录）。

## 2. 云端工作区 (Cloud Workspace)

- **存储路径**: `/var/lib/oryxos/workspace/`（属主 `oryxos:oryxos`，目录权限 `0750`）
- **关键成员**:
  - `runtime.yaml`: 全局运行配置（`schema_version: 1`、Provider/Memory/Sandbox/Timezone，仅使用 `${VAR_NAME}` 占位符，不含明文密钥）。
  - `secrets.local.yaml`: 云端受控本地密钥文件（权限强制 `0600`，属主 `oryxos:oryxos`）。
  - `oryxos.db`（含 `oryxos.db-wal`、`oryxos.db-shm`）: 单机 SQLite 持久化数据库（WAL + `synchronous=FULL`）。
  - `agents/<name>/AGENT.md`: Agent 定义（含 `default` 及云端启用的定时推送 Agent，如天气日报）。
  - `memory/`: 长期记忆 Markdown 后端目录。
  - `output/`: Agent 受控输出目录。
  - `.oryxos.lock`（或工作区互斥锁文件）: 防止多实例并发写入。
- **不变量**: 任何发布、升级、回滚操作均不得删除或覆盖 `/var/lib/oryxos/workspace/` 下的持久化数据。

## 3. 受控密钥配置 (Secret Configuration)

- **分层存储**:
  1. **CI/CD 层（GitHub Repository Secrets）**: 仅存于 GitHub 加密托管中，不入库：
     - SSH 接入：`TENCENT_SSH_HOST`、`TENCENT_SSH_PORT`、`TENCENT_SSH_USER`、`TENCENT_SSH_KEY`
     - 业务凭证（按 `FR-009` 启用）：`DEEPSEEK_API_KEY`、`FEISHU_WEBHOOK_URL`（以及可选其它扩展凭证）
  2. **云主机层（`/var/lib/oryxos/workspace/secrets.local.yaml`）**: 权限 `0600`，属主 `oryxos:oryxos`。
  3. **反代认证层（宝塔 Nginx `.htpasswd`）**: 独立存放于宝塔站点配置受控目录，权限 `0640`（`root:www` 或宝塔 Nginx 运行用户可读），与 OryxOS 工作区隔离。
- **安全约束**: 严禁写入 Git 受跟踪文件、日志、`AGENT.md` 或 `runtime.yaml`。

## 4. 备份快照 (Backup Snapshot)

- **存储路径**: `/var/backups/oryxos/oryxos-backup-YYYYMMDD-HHMMSS.tar.gz`（目录权限 `0700`，属主 `root:root` 或 `oryxos:oryxos`）
- **快照内容**:
  - 通过 `sqlite3 ... ".backup ..."` 生成的一致性 `oryxos.db` 快照
  - `runtime.yaml`
  - `agents/`
  - `memory/`
- **校验与留存**: 生成 `.sha256` 校验文件；按日定时执行，保留最近 `14` 天副本，超期自动清理。

## 5. 服务运行与发布状态流转

```text
[Building & Verifying on GitHub Runner]
        │ (verify 通过 + 生成 sha256)
        ▼
[Uploading Artifact to /opt/oryxos/releases/<ver>]
        │ (sha256 校验通过)
        ▼
[Stopping old oryxos.service] ──(等待进程完全退出，释放工作区锁)──┐
                                                                 ▼
[Switching /opt/oryxos/current -> <ver> & Starting service]
        │
        ├──(60s 内 /api/v1/health == 200 & 非 PAUSED_RECORDING)──▶ [Healthy Serving]
        │
        └──(超时 / 退出码 != 0 / 503 PAUSED_RECORDING)───────────▶ [Auto Rollback to previous]
                                                                       │
                                                                       ▼
                                                            [Restart previous & Alert CI Failure]
```
