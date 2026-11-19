# Research & Technical Decisions: 云端天气与通知 Agent 配置及自动交付

**Feature**: `003-cloud-weather-notify-agents`  
**Date**: 2026-11-19  
**Spec**: [spec.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/spec.md)

## 1. 现状根因核查（代码级确认）

通过对照 `oryxos-core`、`oryxos-tool`、`oryxos-cli`、`deploy/tencent-cloud/` 与 `.github/workflows/deploy-tencent-cloud.yml`，确认生产管理台仅显示不可用 `default` Agent 的完整技术链如下：

1. **云端首次初始化仅产生 `default`**：`deploy-release.sh` 在 `/var/lib/oryxos/workspace/workspace.json` 不存在时执行 `oryxos-boot.jar --root /var/lib/oryxos/workspace init`，而 `InitCommand` / `WorkspaceInitializer` 仅写入内置占位模板 `agents/default/AGENT.md`（绑定未配置的 `configure-provider`）。
2. **CI/CD 未下发托管 Agent 与配置模板**：`.github/workflows/deploy-tencent-cloud.yml` 当前仅上传 `oryxos-boot.jar`、`shared-lib`、发布/回滚脚本和 `secrets.local.yaml`，既不包含任何业务 `agents/` 定义，也不会同步更新已落盘的 `runtime.yaml`。
3. **云端 `runtime.yaml.template` 字段与运行时解析器不匹配**：
   - `deploy/tencent-cloud/config/runtime.yaml.template` 中 `providers.deepseek` 使用了错误的 `type` / `base_url` / `default_model`，而 `RuntimeConfigLoader.java` 实际要求 `protocol: openai_chat_completions`、`endpoint`、`completions_path`、`api_key`。
   - 模板顶层 `notifications:` 不被 `RuntimeConfigLoader` 读取（通知渠道必须声明在各 `AGENT.md` 的 `notify_channels` 中，由 `AgentDefinitionReader` 与 `NotificationTargetResolver` 解析）。
   - 模板 `tools.http.allowed_targets: []` 为空，导致 `HttpTargetPolicy` 拦截所有 `http_get` 与 `notify` 出站请求。
4. **密钥占位符命名差异**：CI/CD 渲染 `secrets.local.yaml` 时写入 `DEEPSEEK_API_KEY` 与 `FEISHU_WEBHOOK_URL`，而本地开发工作区 `.oryxos-dev/real-tools-workspace/agents/` 使用了 `${TEST_WEBHOOK_URL}`，在云端迁移时必须统一为 `${FEISHU_WEBHOOK_URL}`。

---

## 2. 技术决策记录

### D1: 云端 `runtime.yaml.template` 对齐当前运行时 Schema

- **Decision**: 重写 `deploy/tencent-cloud/config/runtime.yaml.template` 以严格符合 `RuntimeConfigLoader.java`、`FileAccessPolicy` 与 `HttpTargetPolicy.java` 的解析契约：
  - `providers.deepseek` 使用 `protocol: openai_chat_completions`、`endpoint: https://api.deepseek.com`、`completions_path: /chat/completions`、`api_key: '${DEEPSEEK_API_KEY}'`、`connect_timeout_seconds: 5`；移除无效字段 `type`、`base_url`、`default_model` 及顶层 `notifications:`。
  - `tools.file` 使用工作区相对路径 `allowed_read_paths: [output, memory]` 与 `allowed_write_paths: [output]`（兼容云主机 `/var/lib/oryxos/workspace` 与本地 `@TempDir` 测试工作区）。
  - `tools.http.allowed_targets` 仅放行最小必需的三个 HTTPS 443 目标：`open.feishu.cn`、`api.open-meteo.com`、`geocoding-api.open-meteo.com`（不写 `HttpTargetPolicy` 不读取的 `path_prefix` 冗余键）。
- **Rationale**: `HttpTargetPolicy` 仅按 `scheme + host + port` 进行精确匹配校验；`tools.file` 相对路径由 `RuntimeConfigLoader` 基于 `workspace.root().resolve(...)` 解析，使用相对路径可同时通过零网络单元测试与线上部署验证。
- **Alternatives considered**:
  - 保留绝对路径 `/var/lib/oryxos/workspace/output`：在 CI 临时测试工作区中会因 `startsWith(workspace.root())` 校验失败而无法直接复用模板测试，故放弃。
  - 开启通配符域名允许所有 HTTPS 目标：违反宪章原则 IV 最小白名单边界，故放弃。

### D2: 上架 `weather` 与 `feishu-notify` 两个托管 Agent，清理占位 `default`（依据 Q1=B, Q2=C）

- **Decision**:
  - 在仓库 `deploy/tencent-cloud/config/agents/` 下新增 `weather/AGENT.md` 与 `feishu-notify/AGENT.md` 两个托管 Agent 模板。
  - 两者均绑定 `provider: {name: deepseek, model: deepseek-chat}`，声明 `default_target: feishu` 及 `notify_channels.feishu`（`type: webhook`、`url: '${FEISHU_WEBHOOK_URL}'`、飞书文本载荷 `{msg_type: text, content: {text: '$CONTENT'}}`、JSON 确认 `{mode: json_equals, pointer: /code, expected: 0}`），且均显式设置 `schedules: []`（无自动定时推送，纯手动触发）。
  - `weather` 授权 `tools: [http_get, notify]`，指令基于 Open-Meteo `forecast` 与 `geocoding-api` 查询真实天气并发送简明中文摘要，严禁编造数据或对失败/未确认通知自动重发。
  - `feishu-notify` 授权 `tools: [notify]`，用于独立向飞书目标发送通知与验证投递确认。
  - 同步流程清理 `agents/default` 目录。经仓库全量检索，`default` 仅由 `InitCommand`/`WorkspaceInitializer` 首次初始化创建，核心引擎、`RuntimeHttpService` 与 `AgentCatalog` 均不依赖 `default` 存在。
- **Rationale**: 满足用户确认的 `Q1=B`（保留两类核心业务 Agent 并移除不可用的 `default`）与 `Q2=C`（无 cron 定时推送，避免误发群消息），同时保持与 `.github/workflows/deploy-tencent-cloud.yml` 中 `FEISHU_WEBHOOK_URL` 密钥命名完全一致。
- **Alternatives considered**:
  - 修改 `oryxos-cli` 内置 `/templates/AGENT.md`：内置模板面向所有通用本地工作区初始化，不应耦合腾讯云具体的 DeepSeek + 飞书 + Open-Meteo 业务配置；在部署配置层托管并清理 `default` 更符合职责分离（原则 III）。

### D3: 基于发布包快照的托管工作区同步脚本 `sync-workspace-config.sh`（依据 Q3=A）

- **Decision**:
  - 新增 `deploy/tencent-cloud/scripts/sync-workspace-config.sh`，接口为 `sync-workspace-config.sh <config_source_dir> [workspace_dir]`。
  - GitHub Actions 在打包发布阶段把 `deploy/tencent-cloud/config/`（显式排除 `secrets.local.yaml*`）同步到 `${REMOTE_RELEASE_DIR}/config/`，并安装最新的 `sync-workspace-config.sh` 到 `/opt/oryxos/bin/sync-workspace-config.sh`。
  - `deploy-release.sh` 在停旧服务、完成首次 `init`（如缺失）之后，启动新服务之前，调用 `sync-workspace-config.sh "${RELEASE_DIR}/config" "/var/lib/oryxos/workspace"`：
    1. 将 `runtime.yaml.template` 通过同目录临时文件 + `mv` 原子替换写入 `${WORKSPACE_DIR}/runtime.yaml`（属主 `oryxos:oryxos`，权限 `0640`）。
    2. 仅遍历 `<config_source_dir>/agents/` 下的托管 Agent 目录（`weather`、`feishu-notify`），以原子 staging 方式刷新 `${WORKSPACE_DIR}/agents/<name>/AGENT.md`（目录 `0750`、文件 `0640`、`oryxos:oryxos`），**不删除**运维自行添加的其他非托管 Agent 目录。
    3. 若存在 `${WORKSPACE_DIR}/agents/default`，则移除该占位目录。
    4. **严格不触碰** `oryxos.db`、`oryxos.db-wal`、`oryxos.db-shm`、`memory/`、`output/`、`runtime/`、`workspace.json` 与 `secrets.local.yaml`（保持 `0600`）。
    5. 支持环境变量 `ORYXOS_SKIP_CHOWN=true` 仅用于非 root 本地临时目录演练测试（默认 `false`，生产始终执行 `chown`）。
- **Rationale**: 满足 `Q3=A`（每次自动发布同步托管配置，同时 100% 保留持久化状态与非托管自定义 Agent），且避免停服状态下因远程编辑文件不原子或权限缺失引发的启动失败。
- **Alternatives considered**:
  - 在 `deploy-release.sh` 中直接 `cp`：缺乏目录权限规范、`default` 清理与可控测试隔离能力；拆分为独立脚本后可同时被 `bootstrap-server.sh`、`deploy-release.sh`、`rollback.sh` 及本地干跑测试复用。

### D4: 发布回滚时的配置版本一致性保障

- **Decision**: 由于每个版本目录 `/opt/oryxos/releases/<ver>/` 均携带当次发布的 `config/` 目录，当 `deploy-release.sh` 健康探针失败触发自动回滚，或运维执行 `rollback.sh` 手动切回 `previous` 时，若 `${ROLLBACK_TARGET}/config/runtime.yaml.template` 存在，先执行 `sync-workspace-config.sh "${ROLLBACK_TARGET}/config" "/var/lib/oryxos/workspace"` 再重启旧版 JAR；若回滚目标是较早未包含 `config/` 的历史版本，则跳过同步以保持向后兼容。
- **Rationale**: 防止将来版本引入新工具或新配置格式后回滚 JAR 时发生“旧 JAR + 新配置”不兼容。
- **Alternatives considered**:
  - 回滚时不还原配置：可能导致旧版本因读取到新版本 `runtime.yaml` 或 `AGENT.md` 报错退出。

### D5: 发布后 Agent 可用性非阻断诊断日志

- **Decision**: 在 `deploy-release.sh` 的 `check_health` 通过之后，请求本地 `http://127.0.0.1:8080/api/v1/profiles`，提取并打印脱敏的 Agent 名称、可用状态与安全诊断摘要（如 `weather: AVAILABLE`、`feishu-notify: AVAILABLE`）至发布输出日志；此诊断输出仅作可观测性展示（`|| true`），避免在初次未配置业务密钥的场景误触发整包回滚。
- **Rationale**: 让运维在 GitHub Actions 部署日志中无需登录服务器即可确认两个托管 Agent 已正确加载。
- **Alternatives considered**:
  - 若任一 Agent 为 `UNAVAILABLE` 则回滚整次发布：若某次发布前 Secret 尚未更新或外部测试密钥暂空，会导致连程序本身也被回滚；打印非阻断诊断即可同时满足可观测性与部署韧性。

### D6: 零网络 JUnit 5 配置模板契约测试（`TencentCloudConfigTemplateTest`）

- **Decision**: 在 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/TencentCloudConfigTemplateTest.java` 新增自动化测试，在 `@TempDir` 中复制仓库真实的 `deploy/tencent-cloud/config/runtime.yaml.template` 与 `deploy/tencent-cloud/config/agents/*/AGENT.md`，写入 `0600` 的占位 `secrets.local.yaml`（含 `DEEPSEEK_API_KEY` 与 `FEISHU_WEBHOOK_URL` 测试桩），调用真实的 `RuntimeConfigLoader`、`AgentCatalog`、`HttpTargetPolicy` 与 `NotificationTargetResolver` 验证：
  - `deepseek` 提供方成功解析且不可用错误为空；
  - `weather` 与 `feishu-notify` 均加载为可用，tools 与 `feishu` 通知目标校验通过，`schedules` 为空，`default` 不存在；
  - `HttpTargetPolicy` 放行 `open.feishu.cn`、`api.open-meteo.com`、`geocoding-api.open-meteo.com`（HTTPS 443），且拒绝未授权外部地址与明文 HTTP 80 请求；
  - `AgentCatalog`、`NotificationTargetResolver` 与 `Redactor` 输出不包含测试密钥明文；
  - 模拟缺失 `FEISHU_WEBHOOK_URL` 时仅相关 Agent 降级不可用且不泄漏凭证。
- **Rationale**: 直接用真实 Java 内核代码作为 CI 构建门禁验证模板文件，从源头杜绝后续改代码或改模板导致的 schema 漂移。
- **Alternatives considered**:
  - 仅在 shell 中用 YAML 解析器做语法检查：无法验证字段语义、工具名、HTTP 白名单策略和密钥占位符解析规则。
