# Data & Configuration Model: 云端天气与通知 Agent 配置及自动交付

**Feature**: `003-cloud-weather-notify-agents`  
**Date**: 2026-11-19  
**Spec**: [spec.md](file:///Users/jianghaoran/github/OryxOS/specs/003-cloud-weather-notify-agents/spec.md)

## 1. 核心配置实体与运行时校验约束

### 1.1 云端运行配置 (`CloudRuntimeConfig` -> `runtime.yaml`)

对应模板文件：`deploy/tencent-cloud/config/runtime.yaml.template` → 云端路径：`/var/lib/oryxos/workspace/runtime.yaml`（权限 `0640`，属主 `oryxos:oryxos`）。

| 字段路径 | 必填 | 运行时校验约束（依据 `RuntimeConfigLoader.java` / `HttpTargetPolicy.java`） | 本特性云端取值 |
| --- | --- | --- | --- |
| `schema_version` | Yes | 必须等于整数 `1` | `1` |
| `timezone` | Yes | 有效 IANA `ZoneId` | `Asia/Shanghai` |
| `providers.deepseek.protocol` | Yes | 必须严格等于 `"openai_chat_completions"` | `openai_chat_completions` |
| `providers.deepseek.endpoint` | Yes | 合法 `http`/`https` 根地址，不得包含路径（除空或 `/`）、userinfo、query 或 fragment | `https://api.deepseek.com` |
| `providers.deepseek.completions_path` | Yes | 以 `/` 开头，无 `//`、无 query/fragment、无 `/v1/v1` | `/chat/completions` |
| `providers.deepseek.api_key` | Yes | 必须匹配 `\$\{[A-Za-z_][A-Za-z0-9_]*\}` 且在 `secrets.local.yaml` 或环境变量中非空解析 | `'${DEEPSEEK_API_KEY}'` |
| `providers.deepseek.connect_timeout_seconds` | No | 正整数（默认 `5`） | `5` |
| `memory.backend` | Yes | 枚举 `md` \| `sqlite` \| `mem0` | `md` |
| `memory.archival_context_characters` | Yes | 正整数 | `4000` |
| `tools.file.allowed_read_paths` | Yes | 列表元素必须位于工作区根目录之内（相对路径优先） | `[output, memory]` |
| `tools.file.allowed_write_paths` | Yes | 列表元素必须位于工作区根目录之内 | `[output]` |
| `tools.shell` | Yes | `allowed_commands` 须为绝对路径列表；`timeout_seconds` 为正整数 | `allowed_commands: []`, `allowed_script_paths: []`, `timeout_seconds: 20` |
| `tools.http.allowed_targets` | Yes | `{scheme: "http"\|"https", host: string, port: 1..65535}` 列表 | `open.feishu.cn:443`, `api.open-meteo.com:443`, `geocoding-api.open-meteo.com:443` (均 `https`) |
| `tools.http.*_seconds` / `max_response_bytes` | Yes | 正整数 | `connect: 5`, `read: 20`, `max_response_bytes: 65536` |

---

### 1.2 托管 Agent 定义 (`ManagedAgentDefinition` -> `agents/<name>/AGENT.md`)

对应模板目录：`deploy/tencent-cloud/config/agents/{weather,feishu-notify}/AGENT.md` → 云端路径：`/var/lib/oryxos/workspace/agents/<name>/AGENT.md`（目录 `0750`，文件 `0640`，属主 `oryxos:oryxos`）。

| 字段路径 | 必填 | 运行时校验约束（依据 `AgentDefinitionReader.java` / `ToolConfiguration.java`） | `weather` 取值 | `feishu-notify` 取值 |
| --- | --- | --- | --- | --- |
| `schema_version` | Yes | 必须等于整数 `1` | `1` | `1` |
| `name` | Yes | 匹配 `[a-z0-9][a-z0-9-]{0,63}` 且必须等于父目录名；文件与目录不可为符号链接；单文件不超过 `1 MiB` | `weather` | `feishu-notify` |
| `description` | Yes | 字符串 | 天气查询与飞书播报描述 | 独立飞书群通知描述 |
| `provider.name` | Yes | 必须在 `runtime.yaml` 的 `providers` 中存在且可用 | `deepseek` | `deepseek` |
| `provider.model` | Yes | 非空字符串 | `deepseek-chat` | `deepseek-chat` |
| `tools` | Yes | 列表且不可重复，每项须在 `ToolRegistry` 中可用 | `[http_get, notify]` | `[notify]` |
| `mcp_servers` | Yes | 列表，若非空需存在 `mcp_servers.yaml` | `[]` | `[]` |
| `bootstrap` | Yes | 相对工作区且非敏感文件的列表 | `[]` | `[]` |
| `default_target` | Yes | 字符串且必须为 `notify_channels` 中已声明的键 | `feishu` | `feishu` |
| `notify_channels.feishu.type` | Yes | 必须等于 `"webhook"` | `webhook` | `webhook` |
| `notify_channels.feishu.url` | Yes | 密钥占位符引用且解析为无 userinfo 的 `http`/`https` URI | `'${FEISHU_WEBHOOK_URL}'` | `'${FEISHU_WEBHOOK_URL}'` |
| `notify_channels.feishu.payload` | Yes | Map，包含 `$CONTENT` 文本替换占位符 | `{msg_type: text, content: {text: '$CONTENT'}}` | `{msg_type: text, content: {text: '$CONTENT'}}` |
| `notify_channels.feishu.acknowledgement` | Yes | `mode` 为 `http_2xx` 或 `json_equals`；`json_equals` 需 `/` 开头 `pointer` 与 `expected` | `{mode: json_equals, pointer: /code, expected: 0}` | `{mode: json_equals, pointer: /code, expected: 0}` |
| `schedules` | Yes | 列表（按 `Q2=C` 纯手动触发，不配置 cron） | `[]` | `[]` |
| `settings.max_iterations` | Yes | `1..100` 正整数 | `8` | `6` |
| `settings.max_history_turns` | Yes | 正整数 | `20` | `20` |

---

### 1.3 云端受控密钥 (`CloudSecretStore` -> `secrets.local.yaml`)

- **路径**: `/var/lib/oryxos/workspace/secrets.local.yaml`
- **权限约束（依据 `SecretResolver.java`）**:
  - 不得为符号链接；
  - POSIX 权限位**禁止**包含任何 `GROUP_*` 或 `OTHERS_*`（即必须为 `0600`，属主 `oryxos:oryxos`）；
  - 必填键：`DEEPSEEK_API_KEY`、`FEISHU_WEBHOOK_URL`（由 GitHub Actions `Render secrets.local.yaml` 步骤或管理员离线维护）；
  - 未解析的引用或空白值会导致使用该引用的 Provider / Agent 降级为 `UNAVAILABLE` 并经 `Redactor` 脱敏诊断，禁止打印明文。

---

## 2. 发布同步边界与工作区状态流转

### 2.1 工作区路径分类矩阵

| 工作区路径 | 分类 | `sync-workspace-config.sh` 行为 | 权限/属主约束 |
| --- | --- | --- | --- |
| `runtime.yaml` | **托管配置** | 从 `<config_dir>/runtime.yaml.template` 原子覆盖刷新 | `0640` `oryxos:oryxos` |
| `agents/weather/**` | **托管 Agent** | 从 `<config_dir>/agents/weather/` 原子替换刷新 | 目录 `0750` / 文件 `0640` `oryxos:oryxos` |
| `agents/feishu-notify/**` | **托管 Agent** | 从 `<config_dir>/agents/feishu-notify/` 原子替换刷新 | 目录 `0750` / 文件 `0640` `oryxos:oryxos` |
| `agents/default` | **废弃占位目录** | 存在即清理删除（按 `Q1=B`） | N/A |
| `agents/<custom>`（非模板目录） | **非托管自定义 Agent** | **原样保留**，不删除不覆盖 | 保持不变 |
| `oryxos.db`, `oryxos.db-wal`, `oryxos.db-shm` | **持久化状态** | **禁止触碰** | 保持不变 |
| `memory/`, `output/`, `runtime/`, `workspace.json` | **持久化工作区数据** | **禁止触碰** | 保持不变 |
| `secrets.local.yaml` | **受控敏感密钥** | **禁止读取、覆盖或放宽权限** | 保持 `0600` `oryxos:oryxos` |

### 2.2 状态流转（每次发布/回滚）

1. **停旧释放锁**：`systemctl stop oryxos`（等待工作区锁释放）。
2. **首次初始化（仅缺失 `workspace.json` 时）**：执行 `oryxos-boot.jar --root /var/lib/oryxos/workspace init`。
3. **托管配置同步**：执行 `sync-workspace-config.sh "${RELEASE_DIR}/config" "/var/lib/oryxos/workspace"`：
   - 原子写入修正版 `runtime.yaml`；
   - 原子更新托管 `agents/weather` 与 `agents/feishu-notify`；
   - 删除 `agents/default`。
4. **启新与健康探测**：`systemctl start oryxos` → 轮询 `/api/v1/health` 与 `/api/v1/runtime/state`（非 `PAUSED_RECORDING`）→ 成功后请求 `/api/v1/profiles` 输出各 Agent 可用状态摘要。
5. **失败自动回滚 / 手动回滚**：切换 `current -> previous` 后，若 `${PREVIOUS_REAL}/config/runtime.yaml.template` 存在，先同步恢复该版本对应的托管配置，再重启服务并做健康检查。
