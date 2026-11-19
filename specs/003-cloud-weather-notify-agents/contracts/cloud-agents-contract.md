# Delivery & Runtime Contracts: 云端天气与通知 Agent 配置及自动交付

**Feature**: `003-cloud-weather-notify-agents`  
**Date**: 2026-11-19  

本文档定义云端配置模板、托管 Agent 接口契约、工作区同步脚本边界及 CI/CD 发布回滚行为约定（所有敏感密钥均已脱敏，仅使用占位符）。

---

## 1. 云端 `runtime.yaml.template` 契约

文件：`deploy/tencent-cloud/config/runtime.yaml.template`

```yaml
schema_version: 1
timezone: Asia/Shanghai

providers:
  deepseek:
    protocol: openai_chat_completions
    endpoint: https://api.deepseek.com
    completions_path: /chat/completions
    api_key: '${DEEPSEEK_API_KEY}'
    connect_timeout_seconds: 5

memory:
  backend: md
  archival_context_characters: 4000

tools:
  file:
    allowed_read_paths:
      - output
      - memory
    allowed_write_paths:
      - output
  shell:
    allowed_commands: []
    allowed_script_paths: []
    timeout_seconds: 20
  http:
    allowed_targets:
      - scheme: https
        host: open.feishu.cn
        port: 443
      - scheme: https
        host: api.open-meteo.com
        port: 443
      - scheme: https
        host: geocoding-api.open-meteo.com
        port: 443
    connect_timeout_seconds: 5
    read_timeout_seconds: 20
    max_response_bytes: 65536
```

**约束要求**：
- 不得再出现旧模板中的 `type`、`base_url`、`default_model`、`notifications:` 或无意义的 `path_prefix` 字段。
- 凭证占位符仅允许 `'${DEEPSEEK_API_KEY}'`。

---

## 2. 托管 Agent 模板契约

### 2.1 `weather` (`deploy/tencent-cloud/config/agents/weather/AGENT.md`)

- **Frontmatter 契约**：
  - `schema_version: 1`
  - `name: weather`
  - `provider: {name: deepseek, model: deepseek-chat}`
  - `tools: [http_get, notify]`
  - `mcp_servers: []`
  - `bootstrap: []`
  - `default_target: feishu`
  - `notify_channels.feishu`:
    - `type: webhook`
    - `url: '${FEISHU_WEBHOOK_URL}'`
    - `payload: {msg_type: text, content: {text: '$CONTENT'}}`
    - `acknowledgement: {mode: json_equals, pointer: /code, expected: 0}`
  - `schedules: []`（纯手动触发）
  - `settings: {max_iterations: 8, max_history_turns: 20}`
- **指令正文契约**：
  - 明确引导通过 `http_get` 调用 `https://api.open-meteo.com/v1/forecast`（及未知城市通过 `https://geocoding-api.open-meteo.com/v1/search` 查经纬度）查询真实天气；
  - 整理简明易读的中文天气摘要（含城市、当前温度、体感温度、高低温、天气现象、湿度、风速与穿衣/出行建议），并在用户要求播报/发送时调用 `notify`（`targetName: "feishu"`）；
  - 数据异常、缺失字段或工具报错时如实汇报，**严禁编造天气数据**；
  - `notify` 返回失败或 `UNKNOWN` 时**严禁自动重发**，提醒人工核对飞书群与执行记录。

### 2.2 `feishu-notify` (`deploy/tencent-cloud/config/agents/feishu-notify/AGENT.md`)

- **Frontmatter 契约**：
  - `schema_version: 1`
  - `name: feishu-notify`
  - `provider: {name: deepseek, model: deepseek-chat}`
  - `tools: [notify]`
  - `mcp_servers: []`
  - `bootstrap: []`
  - `default_target: feishu`
  - `notify_channels.feishu`：与 `weather` 完全一致（`'${FEISHU_WEBHOOK_URL}'`、飞书文本载荷及 `/code == 0` 确认）。
  - `schedules: []`（纯手动触发）
  - `settings: {max_iterations: 6, max_history_turns: 20}`
- **指令正文契约**：
  - 按用户传入的文本或指令提炼清晰简明的通知正文，调用一次 `notify` 工具（`targetName: "feishu"`）发送到配置的飞书群；
  - 仅在工具返回 `SUCCESS`（即接收端返回确认）时向用户报告发送成功；若失败或 `UNKNOWN`，明确提示核查飞书群及执行记录，不自动重发、不捏造已送达。

---

## 3. 工作区配置同步脚本契约 (`sync-workspace-config.sh`)

- **路径**：`deploy/tencent-cloud/scripts/sync-workspace-config.sh`（服务器端安装路径：`/opt/oryxos/bin/sync-workspace-config.sh`，权限 `0750`，属主 `root:oryxos`）
- **调用签名**：
  ```bash
  sync-workspace-config.sh <config_source_dir> [workspace_dir]
  ```
  - `<config_source_dir>`：必填，目录内必须包含 `runtime.yaml.template` 与 `agents/` 子目录。
  - `[workspace_dir]`：可选，默认读取 `${ORYXOS_WORKSPACE:-/var/lib/oryxos/workspace}`。
- **环境变量控制**：
  - `ORYXOS_USER`（默认 `oryxos`）、`ORYXOS_GROUP`（默认 `oryxos`）
  - `ORYXOS_SKIP_CHOWN`（默认 `false`；仅在非 root 本地测试干跑时显式置为 `true`）
- **行为契约**：
  1. 校验 `<config_source_dir>/runtime.yaml.template` 与 `<config_source_dir>/agents` 存在；创建 `${workspace_dir}/agents` 目录（如缺失）。
  2. 通过 `mktemp` 临时文件 + `chmod 0640` +（若非跳过则 `chown`）+ `mv -f` 原子更新 `${workspace_dir}/runtime.yaml`。
  3. 仅遍历 `<config_source_dir>/agents/*`（子目录内含 `AGENT.md`），先拷入同级临时目录并设置目录 `0750`、文件 `0640` 与属主，再原子替换为 `${workspace_dir}/agents/<agent_name>`。
  4. 若 `${workspace_dir}/agents/default` 存在，执行清理删除（`rm -rf`）。
  5. **绝不**读取、修改或删除 `oryxos.db*`、`memory/`、`output/`、`runtime/`、`workspace.json`、`secrets.local.yaml` 及任何不在 `<config_source_dir>/agents/` 中的自定义 Agent 目录。
  6. 幂等性：连续执行两次结果一致且退出码为 `0`。

---

## 4. CI/CD 与发布/回滚切换契约

1. **`.github/workflows/deploy-tencent-cloud.yml`**：
   - 本地准备阶段：将 `deploy/tencent-cloud/config/`（使用 `--exclude='secrets.local.yaml*'`）同步到 `/tmp/oryxos-release/config/`。
   - 远端传输阶段：将 `/tmp/oryxos-release/config/` 通过 `rsync -az --delete` 同步至 `${REMOTE_RELEASE_DIR}/config/`；上传 `sync-workspace-config.sh`（连同 `deploy-release.sh`、`rollback.sh`）并安装至 `/opt/oryxos/bin/sync-workspace-config.sh`（`0750 root:oryxos`）。
2. **`deploy/tencent-cloud/scripts/deploy-release.sh`**：
   - 停旧服务 + 首次缺失 `workspace.json` 时运行 `init` 后、启动新服务前：若 `${RELEASE_DIR}/config/runtime.yaml.template` 存在，调用 `/opt/oryxos/bin/sync-workspace-config.sh "${RELEASE_DIR}/config" "${WORKSPACE_DIR}"`。
   - 启动服务并 `check_health` 通过后：非阻断探测 `GET http://127.0.0.1:8080/api/v1/profiles`，输出脱敏 Agent 可用状态列表（`name`, `availability`, `safeError`）。
   - 若健康探针失败自动回滚到 `${ROLLBACK_TARGET}` 且 `${ROLLBACK_TARGET}/config/runtime.yaml.template` 存在，先同步回滚对应配置快照再启动服务。
3. **`deploy/tencent-cloud/scripts/rollback.sh`**：
   - 停服并切回 `${TARGET}` 后、启动服务前：若 `${TARGET}/config/runtime.yaml.template` 存在，调用 `/opt/oryxos/bin/sync-workspace-config.sh "${TARGET}/config" "${WORKSPACE_DIR}"`。
4. **`deploy/tencent-cloud/scripts/bootstrap-server.sh`**：
   - 同步安装 `sync-workspace-config.sh` 至 `/opt/oryxos/bin/sync-workspace-config.sh`，并在首次初始化时优先使用 `sync-workspace-config.sh` 从 `deploy/tencent-cloud/config` 播种 `runtime.yaml` 与托管 Agent（缺失时保持幂等安全）。
