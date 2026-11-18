# Tasks: 腾讯云单机安全部署与运维体系

**Input**: Design documents from `/specs/002-tencent-cloud-deployment/`  
**Prerequisites**: [plan.md](file:///Users/jianghaoran/github/OryxOS/specs/002-tencent-cloud-deployment/plan.md), [spec.md](file:///Users/jianghaoran/github/OryxOS/specs/002-tencent-cloud-deployment/spec.md), [research.md](file:///Users/jianghaoran/github/OryxOS/specs/002-tencent-cloud-deployment/research.md), [data-model.md](file:///Users/jianghaoran/github/OryxOS/specs/002-tencent-cloud-deployment/data-model.md), [deployment-contract.md](file:///Users/jianghaoran/github/OryxOS/specs/002-tencent-cloud-deployment/contracts/deployment-contract.md)

**Tests**: 本特性为基础设施、CI/CD 工作流与运维脚本交付，不新增业务单元测试；每个用户故事均包含独立可验证的实操与脚本语法/安全检查任务。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件、无前置依赖）
- **[Story]**: 所属用户故事（`[US1]`、`[US2]`、`[US3]`、`[US4]`）
- 所有任务均包含精确的仓库文件路径

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 创建云端部署目录骨架并确保仓库忽略规则覆盖本地敏感配置

- [x] T001 Create deployment directory structure under `deploy/tencent-cloud/scripts/`, `deploy/tencent-cloud/systemd/`, `deploy/tencent-cloud/nginx/`, and `deploy/tencent-cloud/config/`
- [x] T002 [P] Audit `.gitignore` at `.gitignore` to ensure `secrets.local.yaml`, `*.pem`, `*.key`, `.htpasswd`, and local deployment scratch files are ignored before any script/template generation

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 云主机目录规范、最小权限运行用户、Java 21 基线与脱敏配置模板，所有用户故事均依赖此基础

**⚠️ CRITICAL**: Must complete before User Story phases

- [x] T003 [P] Create desensitized production workspace configuration template (with `schema_version: 1` and `${VAR_NAME}` placeholders only) in `deploy/tencent-cloud/config/runtime.yaml.template`
- [x] T004 [P] Create desensitized local secrets template (placeholders only, documenting `0600` requirement) in `deploy/tencent-cloud/config/secrets.local.yaml.template`
- [x] T005 Create idempotent server bootstrap script (install/verify Java 21 & `sqlite3`, create non-login `oryxos` user, create `/opt/oryxos/releases`, `/opt/oryxos/bin`, `/var/lib/oryxos/workspace` with `0750`, `/var/backups/oryxos` with `0700`, and install systemd units) in `deploy/tencent-cloud/scripts/bootstrap-server.sh`

**Checkpoint**: Foundational templates and server bootstrap script ready

---

## Phase 3: User Story 1 - 安全上线与访问边界控制 (Priority: P1) 🎯 MVP

**Goal**: 确保 OryxOS 仅监听 `127.0.0.1:8080`，公网仅通过 `curlyhair.cn` 子域名 + 宝塔 Nginx HTTPS + HTTP Basic Auth + 单 IP 限流访问，且仓库零敏感信息泄露

**Independent Test**: 服务启动后外部直连公网 `8080` 端口被拒绝/超时；未带 Basic Auth 访问域名返回 `401`；通过 Basic Auth 访问 `/admin/` 与 `/api/v1/health` 正常返回。

### Implementation for User Story 1

- [x] T006 [P] [US1] Create Baota Nginx reverse-proxy vhost template (`curlyhair.cn` subdomain, TLS 1.2/1.3, `auth_basic`, `limit_req_zone` rate limit, `proxy_pass http://127.0.0.1:8080`, `proxy_read_timeout 120s`, and `/swagger-ui`/`/v3/api-docs` access restriction) in `deploy/tencent-cloud/nginx/oryxos-baota-vhost.conf.tpl`
- [x] T007 [P] [US1] Verify default loop-back binding (`server.address: 127.0.0.1`, `server.port: 8080`) in `oryxos-boot/src/main/resources/application.yml` and document firewall/security-group port `8080` block rule in `deploy/tencent-cloud/nginx/oryxos-baota-vhost.conf.tpl`

**Checkpoint**: User Story 1 access boundary artifacts ready for Baota deployment and independent verification

---

## Phase 4: User Story 2 - 持久化工作区、服务常驻与数据备份 (Priority: P1)

**Goal**: 通过 `systemd` 实现开机自启、崩溃自动拉起与优雅停机释放工作区锁；通过 `sqlite3 .backup` 定时器实现每日在线一致性备份与可验证还原

**Independent Test**: 触发云主机重启或 `systemctl kill` 后 60 秒内服务自动恢复且历史会话保留；执行备份并还原至目标工作区后服务可正常启动并通过健康检查。

### Implementation for User Story 2

- [x] T008 [P] [US2] Create `systemd` service unit (`User=oryxos`, `WorkingDirectory=/var/lib/oryxos/workspace`, `-Xms256m -Xmx768m -XX:+ExitOnOutOfMemoryError`, `--root /var/lib/oryxos/workspace serve`, `Restart=on-failure`, `TimeoutStopSec=30`, `ProtectSystem=strict`) in `deploy/tencent-cloud/systemd/oryxos.service`
- [x] T009 [P] [US2] Create online consistent backup script using `sqlite3 ... ".backup ..."` plus `runtime.yaml`, `agents/`, `memory/` archiving to `/var/backups/oryxos/` with `.sha256` and 14-day retention pruning in `deploy/tencent-cloud/scripts/backup-workspace.sh`
- [x] T010 [P] [US2] Create workspace restore script (verifies `.sha256`, requires stopped service/no active lock, restores `oryxos.db`, `runtime.yaml`, `agents/`, `memory/` with `oryxos:oryxos` ownership) in `deploy/tencent-cloud/scripts/restore-workspace.sh`
- [x] T011 [P] [US2] Create `systemd` backup service unit in `deploy/tencent-cloud/systemd/oryxos-backup.service`
- [x] T012 [US2] Create daily `systemd` backup timer unit (`OnCalendar=*-*-* 03:30:00`, `Persistent=true`) in `deploy/tencent-cloud/systemd/oryxos-backup.timer` (depends on T011)

**Checkpoint**: User Story 2 daemonization, persistence isolation, and backup/restore scripts ready

---

## Phase 5: User Story 3 - 基于 GitHub Actions 的平滑发布与快速回滚 (Priority: P2)

**Goal**: 实现 GitHub Actions 自动构建、回归门禁（`./mvnw -B -ntp verify`）、SSH/SCP 下发 Fat JAR、停旧启新、60 秒健康探针与自动/手动秒级回滚

**Independent Test**: 触发 GitHub Actions 工作流完成新版本发布与健康探测；模拟失败版本验证自动切回 `previous` 软链接并重启成功，工作区数据零丢失。

### Implementation for User Story 3

- [x] T013 [P] [US3] Create remote release switch script (`sha256sum` check, stop `oryxos.service` & wait lock release, update `previous` & `current` symlinks, start service, poll `http://127.0.0.1:8080/api/v1/health` & `/api/v1/runtime/state` for `PAUSED_RECORDING`, auto-rollback to `previous` on failure, prune releases keeping latest 5) in `deploy/tencent-cloud/scripts/deploy-release.sh`
- [x] T014 [P] [US3] Create manual one-click rollback script (switch `current` back to `previous`, restart `oryxos.service`, verify health) in `deploy/tencent-cloud/scripts/rollback.sh`
- [x] T015 [US3] Create GitHub Actions deployment workflow (`push` + `workflow_dispatch`, `concurrency`, Java 21 setup, `./mvnw -B -ntp verify`, `sha256sum` generation, `umask 077` SSH config from `TENCENT_SSH_*` secrets, `scp` Fat JAR & scripts, remote `deploy-release.sh` execution, `if: always()` runner key cleanup) in `.github/workflows/deploy-tencent-cloud.yml` (depends on T013)

**Checkpoint**: User Story 3 CI/CD pipeline and automated/manual rollback ready

---

## Phase 6: User Story 4 - 外部模型与通知定时任务云端联调 (Priority: P3)

**Goal**: 支持通过 GitHub Secrets 安全渲染云端 `0600` 权限的 `secrets.local.yaml`（DeepSeek API Key + 飞书 Webhook URL），并在云端工作区启用定时推送 Agent

**Independent Test**: 通过 GitHub Actions 同步渲染 `0600` 的 `secrets.local.yaml` 后，手动触发定时任务（`POST /api/v1/schedules/{id}/run` 或 `/admin/`），飞书群收到推送且日志/审计中无明文凭证。

### Implementation for User Story 4

- [x] T016 [US4] Update `.github/workflows/deploy-tencent-cloud.yml` to optionally/safely render `secrets.local.yaml` on runner (`umask 077`) from `DEEPSEEK_API_KEY` and `FEISHU_WEBHOOK_URL` GitHub Secrets, `scp` to `/var/lib/oryxos/workspace/secrets.local.yaml`, enforce `chmod 0600` & `chown oryxos:oryxos`, and clean up runner temp file in `if: always()` (depends on T015)
- [x] T017 [P] [US4] Document cloud Agent/schedule activation steps (referencing `secrets.local.yaml` via `${DEEPSEEK_API_KEY}` and `${FEISHU_WEBHOOK_URL}` without hardcoding secrets) in `deploy/tencent-cloud/config/runtime.yaml.template`

**Checkpoint**: User Story 4 cloud secrets injection and schedule readiness complete

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 运维手册编写、全量脚本语法与脱敏扫描、端到端发布前置校验

- [x] T018 [P] Write desensitized operations & troubleshooting guide (covering GitHub Secrets setup, Baota Nginx/Basic Auth/SSL steps, `bootstrap-server.sh`, backup/restore, `PAUSED_RECORDING` manual recovery, and `rollback.sh`) in `docs/TencentCloudDeployment.md`
- [x] T019 Validate all shell scripts (`bash -n deploy/tencent-cloud/scripts/*.sh`) and GitHub workflow YAML syntax
- [x] T020 Run repository-wide desensitization scan (`grep` for real IP, Baota port, instance ID, SSH keys, DeepSeek/Feishu secrets) across `deploy/`, `.github/workflows/`, `docs/`, and `specs/002-tencent-cloud-deployment/`
- [x] T021 Verify `./mvnw -B -ntp verify` passes cleanly with new deployment files present and run `quickstart.md` pre-flight checks

---

## Dependencies & Execution Order

- **Setup (Phase 1)** → **Foundational (Phase 2)**: Blocks all user stories.
- **User Story 1 (P1)** & **User Story 2 (P1)**: Can proceed in parallel after Phase 2.
- **User Story 3 (P2)**: Depends on Phase 2 (`bootstrap-server.sh` directory contract) and integrates with `oryxos.service` from US2.
- **User Story 4 (P3)**: Extends `.github/workflows/deploy-tencent-cloud.yml` from US3.
- **Polish (Phase 7)**: Runs after all user stories are implemented.

### Parallel Opportunities

- Within Phase 2: `T003` and `T004` can run in parallel.
- Within Phase 3: `T006` and `T007` can run in parallel.
- Within Phase 4: `T008`, `T009`, `T010`, `T011` can all run in parallel.
- Within Phase 5: `T013` and `T014` can run in parallel before `T015`.

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2)
1. Complete Phase 1 + Phase 2 (`bootstrap-server.sh` & config templates).
2. Complete Phase 3 (Baota Nginx HTTPS + Basic Auth boundary) & Phase 4 (`systemd` + backup/restore).
3. Complete Phase 5 (`deploy-tencent-cloud.yml` + `deploy-release.sh` + `rollback.sh`) so deployment is automated end-to-end.
4. Complete Phase 6 (DeepSeek + Feishu Webhook GitHub Secrets injection) & Phase 7 (ops doc + security/syntax checks).
