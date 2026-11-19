# Tasks: 云端天气与通知 Agent 配置及自动交付

**Input**: Design documents from `/specs/003-cloud-weather-notify-agents/`  
**Prerequisites**: [plan.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/plan.md), [spec.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/spec.md), [research.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/research.md), [data-model.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/data-model.md), [cloud-agents-contract.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/contracts/cloud-agents-contract.md), [quickstart.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/quickstart.md)

**Tests**: 包含零网络模板契约回归测试 `TencentCloudConfigTemplateTest`（使用真实 `RuntimeConfigLoader`、`AgentCatalog`、`HttpTargetPolicy`、`NotificationTargetResolver` 验证模板与权限策略）以及本地同步脚本干跑测试与全量构建门禁验证。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行执行（不同文件、无未完成前置依赖）
- **[Story]**: 所属用户故事（`[US1]`、`[US2]`、`[US3]`）；Setup、Foundational、Polish 阶段不标 Story 标签
- 所有任务均包含精确的仓库文件路径与关键校验约束

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 创建云端托管 Agent 配置目录结构

- [X] T001 Create managed cloud Agent template directories `deploy/tencent-cloud/config/agents/weather/` and `deploy/tencent-cloud/config/agents/feishu-notify/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 核对密钥占位符模板与 Git 忽略策略，确保全流程密钥命名一致且零入库

**⚠️ CRITICAL**: Must complete before User Story phases

- [X] T002 Audit `deploy/tencent-cloud/config/secrets.local.yaml.template` and `.gitignore` to verify `DEEPSEEK_API_KEY` and `FEISHU_WEBHOOK_URL` placeholder alignment (`0600` requirement) and ensure `secrets.local.yaml` and `.oryxos-dev/` remain excluded from Git

**Checkpoint**: 目录骨架与密钥占位符边界已核对就绪

---

## Phase 3: User Story 1 - 云端工作区运行配置与模型/外网访问就绪 (Priority: P1) 🎯 MVP

**Goal**: 修正云端 `runtime.yaml.template` 以符合当前 `RuntimeConfigLoader` 与 `HttpTargetPolicy` 校验契约，确保 `deepseek` 模型服务可用且仅放行 Open-Meteo 与飞书 Webhook 所需的最小 HTTPS 白名单。

**Independent Test**: 使用修正后的 `deploy/tencent-cloud/config/runtime.yaml.template` 与测试桩 `0600` `secrets.local.yaml` 加载 `RuntimeConfigLoader` 与 `HttpTargetPolicy`，确认 `deepseek` 可用且仅白名单内 3 个 HTTPS 443 目标被放行。

### Implementation for User Story 1

- [X] T003 [US1] Update `deploy/tencent-cloud/config/runtime.yaml.template` to match `RuntimeConfigLoader` and `HttpTargetPolicy` constraints (`schema_version: 1`, `timezone: Asia/Shanghai`, `providers.deepseek` with `protocol: openai_chat_completions`, `endpoint: https://api.deepseek.com`, `completions_path: /chat/completions`, `api_key: '${DEEPSEEK_API_KEY}'`, `connect_timeout_seconds: 5`, `memory.backend: md`, `memory.archival_context_characters: 4000`, relative `tools.file` paths `allowed_read_paths: [output, memory]` and `allowed_write_paths: [output]`, `tools.http.allowed_targets` limited to `https` port `443` hosts `open.feishu.cn`, `api.open-meteo.com`, `geocoding-api.open-meteo.com`, removing invalid `type`, `base_url`, `default_model`, top-level `notifications:`, and redundant `path_prefix`)

**Checkpoint**: User Story 1 的云端基础运行配置模板已完成对齐并可被内核直接校验

---

## Phase 4: User Story 2 - 云端天气查询与飞书通知 Agent 上架与手动验证 (Priority: P1)

**Goal**: 在仓库提供托管的 `weather` 与 `feishu-notify` 两个可用 Agent 模板（均纯手动触发、无 cron、统一引用 `'${FEISHU_WEBHOOK_URL}'` 与飞书 `/code == 0` 确认回执），并建立零网络 Java 契约测试护栏。

**Independent Test**: 运行 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/TencentCloudConfigTemplateTest.java`，验证 `weather` 与 `feishu-notify` 全部 `AVAILABLE`、`default` 不存在、`schedules` 为空、白名单拦截越界域名、且解析输出无凭证泄露。

### Implementation for User Story 2

- [X] T004 [P] [US2] Create `deploy/tencent-cloud/config/agents/weather/AGENT.md` with exact frontmatter constraints (`schema_version: 1`, `name: weather`, `provider: {name: deepseek, model: deepseek-chat}`, `tools: [http_get, notify]`, `mcp_servers: []`, `bootstrap: []`, `default_target: feishu`, `notify_channels.feishu` with `type: webhook`, `url: '${FEISHU_WEBHOOK_URL}'`, `payload: {msg_type: text, content: {text: '$CONTENT'}}`, `acknowledgement: {mode: json_equals, pointer: /code, expected: 0}`, `schedules: []`, `settings: {max_iterations: 8, max_history_turns: 20}`) and Chinese Open-Meteo query + non-fabrication + no-auto-retry notify instructions
- [X] T005 [P] [US2] Create `deploy/tencent-cloud/config/agents/feishu-notify/AGENT.md` with exact frontmatter constraints (`schema_version: 1`, `name: feishu-notify`, `provider: {name: deepseek, model: deepseek-chat}`, `tools: [notify]`, `mcp_servers: []`, `bootstrap: []`, `default_target: feishu`, `notify_channels.feishu` with `type: webhook`, `url: '${FEISHU_WEBHOOK_URL}'`, `payload: {msg_type: text, content: {text: '$CONTENT'}}`, `acknowledgement: {mode: json_equals, pointer: /code, expected: 0}`, `schedules: []`, `settings: {max_iterations: 6, max_history_turns: 20}`) and concise Feishu notification + delivery acknowledgement instructions
- [X] T006 [US2] Create zero-network contract test in `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/TencentCloudConfigTemplateTest.java` loading `deploy/tencent-cloud/config/runtime.yaml.template` and `deploy/tencent-cloud/config/agents/*/AGENT.md` into a `@TempDir` workspace with `0600` stub `secrets.local.yaml`, asserting `deepseek` availability, `weather` & `feishu-notify` availability (and `default` absence, `schedules` empty, `tools` valid), `HttpTargetPolicy` allow/deny checks, `NotificationTargetResolver` resolution for `feishu`, secret-redaction guarantees, and clean per-agent degradation when `FEISHU_WEBHOOK_URL` is missing (depends on T003, T004, T005)

**Checkpoint**: User Story 1 与 User Story 2 已具备完整模板与自动化内核契约测试保障

---

## Phase 5: User Story 3 - 自动发布托管同步与无损升级 (Priority: P2)

**Goal**: 实现工作区托管配置原子同步脚本，并将其集成到 GitHub Actions 发布、`deploy-release.sh` 启停切换、版本回滚与主机初始化流程中，确保新老服务器均可自动上线 `weather` 与 `feishu-notify`、清理 `default` 并完整保留持久化数据、本地密钥及非托管自定义 Agent。

**Independent Test**: 在本地临时目录模拟带旧坏 `runtime.yaml`、`agents/default`、`oryxos.db`、`memory/`、`0600` `secrets.local.yaml` 与自建 `agents/custom-keep/AGENT.md` 的工作区，连续两次运行 `ORYXOS_SKIP_CHOWN=true bash deploy/tencent-cloud/scripts/sync-workspace-config.sh deploy/tencent-cloud/config "$TEST_WS"` 验证同步更新、清理 `default`、零破坏保留非托管内容及幂等性。

### Implementation for User Story 3

- [X] T007 [US3] Create `deploy/tencent-cloud/scripts/sync-workspace-config.sh` implementing `sync-workspace-config.sh <config_source_dir> [workspace_dir]` with atomic `runtime.yaml` replacement (`0640`), per-managed-agent replacement under `agents/` (directory `0750`, `AGENT.md` `0640`), removal of `agents/default`, strict preservation of `oryxos.db*`, `memory/`, `output/`, `runtime/`, `workspace.json`, `0600` `secrets.local.yaml`, and unmanaged custom Agent directories, `oryxos:oryxos` ownership enforcement (with `ORYXOS_SKIP_CHOWN=true` opt-out for non-root local tests), and idempotent re-execution (depends on T003, T004, T005)
- [X] T008 [P] [US3] Update `deploy/tencent-cloud/scripts/deploy-release.sh` to invoke `/opt/oryxos/bin/sync-workspace-config.sh "${RELEASE_DIR}/config" "/var/lib/oryxos/workspace"` after service stop / first-time `init` and before service start, emit redacted `GET http://127.0.0.1:8080/api/v1/profiles` status summary after `check_health`, and re-sync `${ROLLBACK_TARGET}/config` on auto-rollback when present (depends on T007)
- [X] T009 [P] [US3] Update `deploy/tencent-cloud/scripts/rollback.sh` to invoke `/opt/oryxos/bin/sync-workspace-config.sh "${TARGET}/config" "/var/lib/oryxos/workspace"` when `${TARGET}/config/runtime.yaml.template` exists before starting the rolled-back release (depends on T007)
- [X] T010 [P] [US3] Update `deploy/tencent-cloud/scripts/bootstrap-server.sh` to install `deploy/tencent-cloud/scripts/sync-workspace-config.sh` to `/opt/oryxos/bin/sync-workspace-config.sh` (`0750 root:oryxos`) and seed initial workspace `runtime.yaml` and managed Agents via `sync-workspace-config.sh` (depends on T007)
- [X] T011 [US3] Update `.github/workflows/deploy-tencent-cloud.yml` to package `deploy/tencent-cloud/config/` (explicitly excluding `secrets.local.yaml*`) into `${REMOTE_RELEASE_DIR}/config/` via `rsync`, upload `deploy/tencent-cloud/scripts/sync-workspace-config.sh`, and install it to `/opt/oryxos/bin/sync-workspace-config.sh` (`0750 root:oryxos`) before calling `deploy-release.sh` (depends on T007, T008, T009)

**Checkpoint**: 自动化发布、回滚与初始化全链路均已接入托管同步并保护非托管持久化资产

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 更新运维文档、执行全量脚本/YAML/脱敏检查、本地干跑与 Maven 构建门禁验证

- [X] T012 [P] Update `docs/TencentCloudDeployment.md` to document managed cloud Agents (`weather`, `feishu-notify`), removal of `default`, pure manual execution policy, automatic workspace config sync scope (synced vs preserved), and `/admin/#agents` post-deploy verification steps
- [X] T013 Run `bash -n deploy/tencent-cloud/scripts/*.sh`, YAML syntax validation on `.github/workflows/deploy-tencent-cloud.yml` and `deploy/tencent-cloud/config/**`, and execute local idempotent dry-run of `deploy/tencent-cloud/scripts/sync-workspace-config.sh` verifying old `runtime.yaml` replacement, `default` removal, and preservation of `oryxos.db`, `0600` `secrets.local.yaml`, `memory/`, `output/`, and custom agents (depends on T007, T008, T009, T010, T011)
- [X] T014 Run repository-wide desensitization scan across `specs/003-cloud-weather-notify-agents/`, `deploy/tencent-cloud/`, `.github/workflows/`, and `docs/TencentCloudDeployment.md` confirming 0 leaked keys, webhook URLs, or real host IPs (depends on T003, T004, T005, T011, T012)
- [X] T015 Run `./tools/dev/mvnw -B -ntp verify` confirming `TencentCloudConfigTemplateTest` and the full local regression suite pass with 0 failures and 0 skipped contract checks (depends on T006, T013)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无前置依赖，可立即启动。
- **Foundational (Phase 2)**: 依赖 Setup 核对（可在 Phase 1 后立即执行），阻塞后续 User Story。
- **User Story 1 (Phase 3 - `T003`)**: 依赖 Phase 2 完成。
- **User Story 2 (Phase 4 - `T004`..`T006`)**: `T004`、`T005` 可在 Phase 1/2 后与 `T003` 并行编写；`T006` 契约测试依赖 `T003`、`T004`、`T005` 全部完成。
- **User Story 3 (Phase 5 - `T007`..`T011`)**: `T007` 同步脚本依赖配置模板路径定型（`T003`~`T005`）；`T008`、`T009`、`T010` 在 `T007` 完成后可并行修改；`T011` 汇总集成全部部署脚本并收口。
- **Polish (Phase 6 - `T012`..`T015`)**: `T012` 可与其他阶段并行补充；`T013`~`T015` 在所有代码、模板与脚本改动完成后统一执行。

### Parallel Opportunities

- `T003` (`runtime.yaml.template`)、`T004` (`weather/AGENT.md`)、`T005` (`feishu-notify/AGENT.md`) 与 `T012` (`docs/TencentCloudDeployment.md`) 操作不同文件，可高度并行。
- `T008` (`deploy-release.sh`)、`T009` (`rollback.sh`)、`T010` (`bootstrap-server.sh`) 在 `T007` 契约落盘后可并行修改。

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2)
1. 完成目录创建（`T001`）与密钥边界核对（`T002`）。
2. 修正 `runtime.yaml.template`（`T003`）并产出 `weather`（`T004`）与 `feishu-notify`（`T005`）模板。
3. 通过 `TencentCloudConfigTemplateTest`（`T006`）在本地零网络环境下证明云端全部配置文件可直接被真实内核解析通过。

### Full Automated Delivery (User Story 3 + Polish)
4. 编写 `sync-workspace-config.sh`（`T007`）并接入 `deploy-release.sh`（`T008`）、`rollback.sh`（`T009`）、`bootstrap-server.sh`（`T010`）与 `.github/workflows/deploy-tencent-cloud.yml`（`T011`）。
5. 完成文档更新（`T012`）、干跑与语法扫描（`T013`）、脱敏检查（`T014`）及全量 Maven `verify` 门禁（`T015`）。
6. 经维护者确认后提交并推送 `main`，触发 GitHub Actions 发布到腾讯云并在 `/admin/#agents` 完成真实飞书群手动验证。
