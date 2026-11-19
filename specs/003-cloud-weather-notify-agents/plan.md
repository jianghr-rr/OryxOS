# Implementation Plan: 云端天气与通知 Agent 配置及自动交付

**Branch**: `003-cloud-weather-notify-agents` | **Date**: 2026-11-19 | **Spec**: [spec.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/spec.md)  
**Input**: Feature specification from `/specs/003-cloud-weather-notify-agents/spec.md`

## Summary

围绕已确认的设计决策（`Q1=B`、`Q2=C`、`Q3=A`），在不改动 OryxOS Java 核心引擎代码的前提下，修复云端运行配置模板、新增托管天气与通知 Agent，并将托管工作区同步机制接入腾讯云 CI/CD 发布与回滚链路：
- **修正云端配置模板 (`runtime.yaml.template`)**：对齐 `RuntimeConfigLoader` 与 `HttpTargetPolicy` 真实契约（`protocol: openai_chat_completions`、`endpoint`、`completions_path`、工作区相对路径 `tools.file`、`tools.http.allowed_targets` 精确放行 `open.feishu.cn`、`api.open-meteo.com`、`geocoding-api.open-meteo.com` 的 HTTPS 443 端口），移除旧模板的无效与忽略字段。
- **上架托管 Agent (`weather` 与 `feishu-notify`) 并清理 `default`**：在 `deploy/tencent-cloud/config/agents/` 新增 `weather/AGENT.md`（Open-Meteo 天气查询 + 飞书通知）与 `feishu-notify/AGENT.md`（独立飞书通知），均引用 `'${FEISHU_WEBHOOK_URL}'` 与 `/code == 0` 确认回执、设置 `schedules: []`（纯手动触发），并在同步时移除初始化遗留的 `agents/default`。
- **接入发布与回滚托管同步 (`sync-workspace-config.sh`)**：将脱敏后的 `deploy/tencent-cloud/config/`（排除 `secrets.local.yaml*`）随每次发布打包下发到服务器版本目录；`deploy-release.sh` 与 `rollback.sh` 在停服后、启服前执行原子同步与权限收敛（`oryxos:oryxos`、`0640`/`0750`），严格保留 `oryxos.db*`、`memory/`、`output/`、`runtime/`、`workspace.json`、`0600` `secrets.local.yaml` 及非托管自定义 Agent；启动健康后输出脱敏 `/api/v1/profiles` 诊断状态。
- **补齐回归门禁 (`TencentCloudConfigTemplateTest`)**：在 `oryxos-boot` 新增零网络 JUnit 5 测试，直接用内核解析器校验生产模板与 HTTP/通知策略，阻断未来 schema 漂移。

## Technical Context

**Language/Version**: Java 21（JUnit 5 模板契约测试）；Bash + YAML（云端配置模板、同步/发布/回滚脚本、GitHub Actions Workflow）  
**Primary Dependencies**: `oryxos-core` (`RuntimeConfigLoader`, `AgentCatalog`, `SecretResolver`, `Redactor`)、`oryxos-tool` (`HttpTargetPolicy`, `NotificationTargetResolver`, `ToolRegistry`)、Spring Boot / JUnit 5 (`@TempDir`)、OpenSSH / `rsync` / `scp` / `systemd`  
**Storage**: 云主机持久化工作区 `/var/lib/oryxos/workspace/`（`oryxos.db*`、`memory/`、`output/`、`runtime/`、`workspace.json`、`0600` `secrets.local.yaml`、非托管自定义 `agents/*` 与托管同步文件 `runtime.yaml`、`agents/weather`、`agents/feishu-notify`）  
**Testing**: JUnit 5 零网络契约测试 `TencentCloudConfigTemplateTest` + 全量非 real 回归构建 `./tools/dev/mvnw -B -ntp verify` + `bash -n` 脚本语法检查 + 本地 `@TempDir`/临时目录 `sync-workspace-config.sh` 干跑验证 + 仓库脱敏扫描 + 云端 `/admin/#agents` 实测验收  
**Target Platform**: 腾讯云 CVM（Linux x86_64，systemd `oryxos.service` 以非特权 `oryxos` 用户运行）与 GitHub Actions `ubuntu-latest` Runner  
**Project Type**: 单机 Java 应用的云端部署配置交付、发布同步流水线及模板契约测试  
**Performance Goals**: 单次云端发布在原有 5 分钟基线内完成；配置与托管 Agent 同步在停服切换窗口 5 秒内完成；手动运行天气/通知任务 60 秒内完成（`SC-002`）  
**Constraints**: 严格禁止将任何真实 API Key、Webhook URL、服务器公网 IP 或 SSH 凭证写入仓库文件或打印到日志/管理台；同步脚本严禁覆盖或删除 `oryxos.db*`、`memory/`、`output/`、`secrets.local.yaml` 与非托管 Agent 目录；保持 `secrets.local.yaml` 为 `0600`  
**Scale/Scope**: 单机单工作区云端生产环境，托管 2 个业务 Agent（`weather`、`feishu-notify`）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 宪章原则 | 检查结果 | 说明 |
| --- | --- | --- |
| **I. 单机内核优先，范围明确** | PASS | 天气查询与飞书通知全部由配置化 `AGENT.md` 指令与现有内置工具（`http_get`、`notify`）表达，不向通用 Java 内核硬编码业务流程；保持文件配置 + 重启生效。 |
| **II. ReAct 与工具执行由 OryxOS 统一掌控** | PASS | 本特性不修改 ReAct 循环、工具调度或重试策略；通知失败/超时不自动重发。 |
| **III. 能力职责分离，依赖通过契约连接** | PASS | 云端部署模板与同步脚本集中在 `deploy/tencent-cloud/` 与 `.github/workflows/`，不将业务 Agent 耦合进通用 `oryxos-cli` `init` 内置模板，通过标准配置目录契约连接运行时。 |
| **IV. 工具操作受控，凭证与数据边界明确** | PASS | 严格限制 `tools.http.allowed_targets` 仅放行 3 个必需 HTTPS 443 域名；`tools.file` 限工作区相对子目录；全部密钥仅通过 `${DEEPSEEK_API_KEY}` 与 `${FEISHU_WEBHOOK_URL}` 引用服务器 `0600` `secrets.local.yaml`。 |
| **V. 分阶段落实审计，持久化结果可恢复** | PASS | 同步脚本严禁触碰 `oryxos.db*`、`memory/` 与持久化审计记录；回滚一并恢复对应发布快照中的 `config/` 目录，保证版本与配置可一致恢复。 |
| **VI. 每个用户故事都可演示、可检查** | PASS | `quickstart.md` 与 `TencentCloudConfigTemplateTest` 明确区分本地零网络模板校验、本地脚本干跑与云端真实飞书群手工验收，不以模拟替身冒充真实联调。 |
| **VII. 规格、方案、任务与实现保持一致** | PASS | 严格对齐 `spec.md`（`Q1=B`、`Q2=C`、`Q3=A`、`FR-001`~`FR-008`、`SC-001`~`SC-005`），无未授权扩展或偏差。 |

*Post-Design Re-check*: 已对照 [research.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/research.md)、[data-model.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/data-model.md)、[cloud-agents-contract.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/contracts/cloud-agents-contract.md) 与 [quickstart.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/quickstart.md) 复核，全部分项均为 `PASS`，无宪章违例。

## Project Structure

### Documentation (this feature)

```text
specs/003-cloud-weather-notify-agents/
├── spec.md                              # 已澄清并定稿的需求规格（Q1=B, Q2=C, Q3=A）
├── plan.md                              # 本实施计划
├── research.md                          # Phase 0 根因核查与技术决策记录
├── data-model.md                        # Phase 1 配置实体、校验约束与同步状态流转
├── quickstart.md                        # Phase 1 本地校验与云端验收指南
├── contracts/
│   └── cloud-agents-contract.md         # Phase 1 配置模板、Agent 契约与脚本/CI 契约
├── checklists/
│   └── requirements.md                  # 规格质量检查清单（全部就绪）
└── tasks.md                             # Phase 2 任务清单（由 $speckit-tasks 生成）
```

### Source Code & Deployment Artifacts (repository root)

```text
.github/
└── workflows/
    └── deploy-tencent-cloud.yml         # 更新：打包发布 config/（排除 secrets）并同步安装 sync-workspace-config.sh

deploy/
└── tencent-cloud/
    ├── config/
    │   ├── runtime.yaml.template        # 更新：修正 DeepSeek provider 字段与 Open-Meteo/飞书 HTTP 白名单
    │   └── agents/
    │       ├── weather/
    │       │   └── AGENT.md             # 新增：weather 托管 Agent（Open-Meteo + 飞书通知，无 cron）
    │       └── feishu-notify/
    │           └── AGENT.md             # 新增：feishu-notify 独立飞书通知 Agent（无 cron）
    └── scripts/
        ├── sync-workspace-config.sh     # 新增：托管工作区配置原子同步与 default 清理脚本
        ├── deploy-release.sh            # 更新：停服后/启服前执行同步、启后打印 profiles 状态、回滚同步旧配置
        ├── rollback.sh                  # 更新：手动回滚时若存在快照 config/ 则一并同步
        └── bootstrap-server.sh          # 更新：安装 sync-workspace-config.sh 并用于初始化播种

oryxos-boot/
└── src/test/java/io/github/oryxlabs/oryxos/boot/
    └── TencentCloudConfigTemplateTest.java  # 新增：模板、HTTP 白名单与通知解析契约测试（零网络）

docs/
└── TencentCloudDeployment.md            # 更新：补充托管 Agent 列表、自动同步范围与管理台验证说明
```

**Structure Decision**: 保持现有 9 个 Java Maven 模块源码职责与运行契约不变；所有云端交付资产集中在 `deploy/tencent-cloud/`、`.github/workflows/deploy-tencent-cloud.yml` 与 `docs/TencentCloudDeployment.md`，并在 `oryxos-boot` 测试源集中补充模板契约回归测试，确保业务代码零侵入且持续受 CI 回归门禁保护。

## Complexity Tracking

> 无宪章违例，无需豁免。
