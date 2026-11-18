# Implementation Plan: 腾讯云单机安全部署与运维体系

**Branch**: `002-tencent-cloud-deployment` | **Date**: 2026-11-18 | **Spec**: [spec.md](file:///Users/jianghaoran/github/OryxOS/specs/002-tencent-cloud-deployment/spec.md)  
**Input**: Feature specification from `/specs/002-tencent-cloud-deployment/spec.md`

## Summary

本方案在不修改 OryxOS 核心应用业务逻辑的前提下，构建一套面向 **腾讯云北京三区 CVM（2核 4GiB，CentOS 系 x86_64，已装宝塔面板）** 的安全发布、常驻托管与运维恢复体系：
- **CI/CD 发布**：通过 GitHub Actions 在 `ubuntu-latest` Runner 完成 Java 21 构建与 `./mvnw -B -ntp verify` 回归门禁，借助 GitHub Secrets（SSH 密钥对 + 运行时凭证）将 Fat JAR 推送至云主机 `/opt/oryxos/releases/<ver>/`，并基于 `current` / `previous` 软链接实现停旧启新、健康探测与自动/手动回滚。
- **安全边界**：OryxOS 保持仅监听 `127.0.0.1:8080`，公网通过已备案域名 `curlyhair.cn` 下的独立子域名（如 `oryxos.curlyhair.cn`）由宝塔 Nginx 提供 **HTTPS + Basic Auth + 单 IP 限流** 反向代理。
- **常驻、隔离与备份**：以专用非 root 用户 `oryxos` 运行 `systemd` 服务（限制 JVM 堆 `-Xms256m -Xmx768m` 以兼容同机服务），将程序包（`/opt/oryxos`）、工作区（`/var/lib/oryxos/workspace` 含 `0600` 的 `secrets.local.yaml`）与备份（`/var/backups/oryxos` 每日 `sqlite3 .backup` 一致性快照）严格分离，并支持首期 DeepSeek + 飞书 Webhook 定时任务云端联调。

## Technical Context

**Language/Version**: Java 21（应用运行时）；Bash + YAML（CI/CD Workflow、systemd 单元、Nginx 配置模板与运维脚本）  
**Primary Dependencies**: GitHub Actions (`actions/checkout@v4`, `actions/setup-java@v4`)、Maven Wrapper (`./mvnw`)、OpenSSH / `scp`、`systemd`、`sqlite3` CLI、宝塔面板 Nginx（TLS + `auth_basic`）  
**Storage**: 单机 SQLite（`/var/lib/oryxos/workspace/oryxos.db`，WAL + `synchronous=FULL`）与本地文件系统（`runtime.yaml`、`secrets.local.yaml`、`agents/`、`memory/`、`output/`）  
**Testing**: 构建门禁 `./mvnw -B -ntp verify`（本地/Runner 322+ 回归用例）；部署后端到端验收（公网 8080 阻断测试、HTTPS + Basic Auth 测试、`reboot`/kill 自愈、SQLite `.backup` 还原演练、版本回滚演练、DeepSeek + 飞书真实推送触发）  
**Target Platform**: 腾讯云 CVM（北京三区，标准型 S4，2核 4GiB，3Mbps，CentOS 系 x86_64，已部署宝塔面板）  
**Project Type**: 单机 Java Web/CLI 运行时的基础设施自动化、CI/CD 工作流与运维文档（不新增业务微服务）  
**Performance Goals**: 正常发布耗时 ≤ 5 分钟；故障回滚恢复耗时 ≤ 3 分钟；主机/进程重启后 60 秒内恢复健康（`SC-002`、`SC-003`）  
**Constraints**: 严格禁止将真实公网 IP、宝塔端口、实例 ID、SSH 凭证、DeepSeek Key、飞书 Webhook URL 写入 Git 受跟踪文件；2C4G 主机需控制 JVM 堆内存上限（`-Xmx768m`）避免与宝塔/同机服务发生 OOM；`oryxos` 单工作区互斥锁要求先停旧实例再启新实例  
**Scale/Scope**: 单节点、单工作区、低并发个人/团队管理台与定时任务调度

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 宪章原则 | 检查结果 | 说明 |
| --- | --- | --- |
| **I. 单机内核优先，范围明确** | PASS | 保持 OryxOS 单机 Fat JAR + SQLite 形态，不引入分布式协调或多租户改造，公网访问通过反代层解决。 |
| **II. ReAct 与工具执行由 OryxOS 统一掌控** | PASS | 本特性不修改 ReAct 循环与工具调度逻辑。 |
| **III. 能力职责分离，依赖通过契约连接** | PASS | 基础设施层（CI/CD、systemd、Nginx、备份）与业务代码完全解耦，通过标准 CLI 参数（`--root ... serve`）与 HTTP 健康端点连接。 |
| **IV. 工具操作受控，凭证与数据边界明确** | PASS | 强制 `127.0.0.1:8080` + HTTPS Basic Auth 边界；所有密钥仅存于 GitHub Secrets 与服务器 `0600` 的 `secrets.local.yaml`，零入库、零明文日志。 |
| **V. 分阶段落实审计，持久化结果可恢复** | PASS | 工作区与程序包分离，发布/回滚不触碰 `oryxos.db`，并引入 `sqlite3 .backup` 在线一致性备份与恢复演练。 |
| **VI. 每个用户故事都可演示、可检查** | PASS | `quickstart.md` 为 US-1~US-4 定义了可重复的外部探测、重启自愈、备份还原、回滚及真实飞书联调验收步骤。 |
| **VII. 规格、方案、任务与实现保持一致** | PASS | 本计划严格对应 `spec.md` 中 `FR-001`~`FR-010` 及 `SC-001`~`SC-005`，无未授权范围扩张。 |

*Post-Design Re-check*: Phase 0 (`research.md`) 与 Phase 1 (`data-model.md`、`contracts/deployment-contract.md`、`quickstart.md`) 均已核对，无宪章违例，无需填写 Complexity Tracking。

## Project Structure

### Documentation (this feature)

```text
specs/002-tencent-cloud-deployment/
├── spec.md                              # 已澄清并定稿的需求规格
├── plan.md                              # 本实施计划
├── research.md                          # Phase 0 架构与技术决策记录
├── data-model.md                        # Phase 1 部署实体与状态流转模型
├── quickstart.md                        # Phase 1 端到端部署与验收指南
├── contracts/
│   └── deployment-contract.md           # Phase 1 Secrets、目录、systemd、Nginx 与回滚契约
├── checklists/
│   └── requirements.md                  # 规格质量检查清单
└── tasks.md                             # Phase 2 任务清单（由 $speckit-tasks 生成）
```

### Source Code & Deployment Artifacts (repository root)

```text
.github/
└── workflows/
    └── deploy-tencent-cloud.yml         # 新增：GitHub Actions 构建、verify、SSH/SCP 发布与健康探针回滚工作流

deploy/
└── tencent-cloud/                       # 新增：云端部署模板与幂等运维脚本（全部脱敏，不含真实 IP/密钥）
    ├── scripts/
    │   ├── bootstrap-server.sh          # 云主机一次性初始化（Java 21、oryxos 用户、目录、systemd/timer 注册）
    │   ├── deploy-release.sh            # 云主机侧发布切换脚本（sha256 校验、停旧、切换 current/previous、启新、探针与回滚）
    │   ├── rollback.sh                  # 手动一键回滚至 previous 版本脚本
    │   ├── backup-workspace.sh          # sqlite3 .backup 一致性快照 + 配置/记忆归档 + 14 天清理
    │   └── restore-workspace.sh         # 从指定备份归档安全还原工作区脚本
    ├── systemd/
    │   ├── oryxos.service               # systemd 服务单元模板
    │   ├── oryxos-backup.service        # 定时备份服务单元
    │   └── oryxos-backup.timer          # 每日凌晨自动备份定时器
    ├── nginx/
    │   └── oryxos-baota-vhost.conf.tpl  # 宝塔面板 Nginx 反向代理 + HTTPS + Basic Auth + 限流参考配置片段
    └── config/
        ├── runtime.yaml.template        # 云端生产工作区 runtime.yaml 模板（仅含 ${VAR_NAME} 占位符）
        └── secrets.local.yaml.template  # 密钥结构示例（仅占位符，严禁填写真实值）

docs/
└── TencentCloudDeployment.md            # 新增：脱敏版腾讯云部署、宝塔配置、备份还原与 PAUSED_RECORDING 运维手册
```

**Structure Decision**: 保持原有 `oryxos-*` 九个 Java Maven 模块不动，将所有云端自动化、基础设施模板与运维手册收敛在 `.github/workflows/`、`deploy/tencent-cloud/` 与 `docs/TencentCloudDeployment.md`，实现业务代码与部署基础设施清晰分离。

## Complexity Tracking

> 无宪章违例，无需豁免。
