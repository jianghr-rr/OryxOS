# OryxOS 个人入口、CLI 与运维指南

OryxOS 单机运行时打包在 `oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar`，个人工程统一通过仓库内置包装器 `./tools/dev/mvnw` 构建、通过 `./tools/dev/oryxos` 运行，隔离缓存在被忽略的 `.oryxos-dev/` 下，不修改全局 Java、Maven、ACSP 或官方课程目录。

---

## 1. 构建与个人入口

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS

# 完整构建并执行本地回归（322 项必跑用例，含打包）
./tools/dev/mvnw -B -ntp verify

# 运行个人 CLI 入口（不传 --root 时默认使用当前工作目录的 .oryxos）
./tools/dev/oryxos --version
./tools/dev/oryxos --help
```

- **工作区定位优先级**：`--root <path>` > `-Doryxos.root=<path>` > `ORYXOS_ROOT` > `cwd/.oryxos`。同一工作区同时由文件锁保护，只允许一个 `chat`、`serve` 或 `gateway` 实例持有（冲突退出码 `4`，`WORKSPACE_BUSY`）。
- **端口配置**：`serve` / `gateway` 默认监听 `127.0.0.1:8080`；如需更换端口，通过环境变量 `SERVER_PORT=<port>` 指定（`oryxos serve` 不接受 `--port` 参数）。

---

## 2. 12 项 CLI 子命令总览

| 命令 | 类型 | 作用 |
| --- | --- | --- |
| `oryxos init` | 轻 | 幂等创建缺失的 `.oryxos/` 目录、`runtime.yaml`、`agents/default/AGENT.md` 与模板 |
| `oryxos status` | 轻 | 检查工作区文件、配置与进程锁；不建库、不探测外部服务（标注数据库未检查） |
| `oryxos chat [--profile <name>] [--user <id>] [--session <uuid>]` | 重 | 基于正式 SQLite 与 ReAct 循环进行交互式多轮对话（`/quit` 或 EOF 退出） |
| `oryxos serve` | 重 | 启动持久化 HTTP API（19 个操作）、`/admin/` 同源管理台与已启用的文件调度 |
| `oryxos gateway` | 重 | 与 `serve` 共享同一 HTTP/调度宿主的守护入口（当前版本不含外部 IM 入站适配器） |
| `oryxos profile list` | 轻 | 列出 `agents/<name>/AGENT.md` 配置状态与通知渠道名 |
| `oryxos profile create <name>` | 轻 | 生成最小 `agents/<name>/AGENT.md` 模板（不覆盖已有目录） |
| `oryxos profile show <name>` | 轻 | 查看指定 Agent 的 `AGENT.md` 内容与派生信息 |
| `oryxos profile delete <name>` | 轻 | 停机状态下将 Agent 移入 `agent-archive/`，保留历史会话与审计记录 |
| `oryxos provider list` | 轻 | 列出 `runtime.yaml` 中声明的 Provider（仅配置检查，不发外部请求） |
| `oryxos tool list` | 轻 | 列出当前注册或静态内置工具（离线 MCP 明确标为 `NOT_DISCOVERED`） |
| `oryxos session list [--profile <name>] [--limit N]` | 轻 | 只读查询 `oryxos.db` 中的会话摘要；库未创建时返回退出码 `3`（`STORAGE_UNAVAILABLE`） |

所有命令支持 `--root <path>`、`--json`、`--help` 与 `--version`。轻命令不启动 Spring 容器、不创建数据库、不连接外部模型或 MCP。

---

## 3. 工作区与配置最小前提

执行 `./tools/dev/oryxos --root "$WS" init` 后，目录结构如下：

```text
$WS/
├── runtime.yaml                 # 全局 Provider / Memory / Sandbox / Timezone 配置（需 schema_version: 1）
├── secrets.local.yaml           # 可选本地密钥（权限须为 0600，仅本地使用）
├── oryxos.db                    # 首次启动重命令（chat/serve/gateway）时自动创建的 SQLite 库
├── agents/<name>/AGENT.md       # Agent 定义（须含 schema_version: 1、name、provider.name/model）
├── memory/                      # 长期记忆（默认 md 后端）
└── output/                      # 受控输出目录
```

- **密钥隔离**：`runtime.yaml` 中用 `${VAR_NAME}` 引用环境变量或 `secrets.local.yaml`（权限 `0600`），严禁将明文密钥写入 `AGENT.md`、`runtime.yaml` 或日志。
- **真实模型联调前提**（当前仓库尚未执行真实公网联调）：需自备有效的 DeepSeek / Kimi API Key、账号可用模型名、直连 HTTPS 网络及明确批准的测试 Webhook / 可信 MCP Server。

---

## 4. 19 个 HTTP 操作与同源管理台

`serve` 启动后在 `http://127.0.0.1:${SERVER_PORT:-8080}` 提供以下已验收接口（契约见 `specs/001-single-node-agent-runtime/contracts/http-api.json`）：

- **健康与只读查询**：`GET /api/v1/health`、`GET /api/v1/info`、`GET /api/v1/profiles`、`GET /api/v1/tools`、`GET /api/v1/memory`、`GET /api/v1/runtime/state`
- **无状态调用与会话**：`POST /api/v1/agents/{name}/invoke`、`POST /api/v1/sessions`、`GET /api/v1/sessions`、`GET /api/v1/sessions/{id}`、`DELETE /api/v1/sessions/{id}`、`POST /api/v1/sessions/{id}/messages`
- **执行与调度**：`GET /api/v1/executions`、`GET /api/v1/executions/{id}`、`GET /api/v1/schedules`、`PUT /api/v1/schedules/{id}/enabled`、`GET /api/v1/schedules/{id}/executions`、`POST /api/v1/schedules/{id}/run`
- **故障人工恢复**：`POST /api/v1/runtime/recover`
- **管理台与文档**：`http://127.0.0.1:${SERVER_PORT:-8080}/admin/`（原生 ES Module 页面，同源调用 `/api/v1/**`）与 `/swagger-ui/index.html`、`/v3/api-docs`。

---

## 5. 故障核查、恢复与当前限制

### 常见问题与人工恢复
1. **`STORAGE_UNAVAILABLE`（退出码 `3`）**：新工作区尚未运行过 `chat`/`serve` 时执行 `session list` 属正常提示（只读查询不建空库）；若库文件损坏，先检查磁盘与锁后排查。
2. **`WORKSPACE_BUSY`（退出码 `4`）**：同一工作区已有 `chat` 或 `serve` 运行；轻命令可并发只读查询，重命令须先停掉旧进程。
3. **`PAUSED_RECORDING` / `HTTP 503`**：关键持久化或审计写入失败后，运行时会立即暂停接单以防止静默丢账；先通过 `GET /api/v1/runtime/state`、`GET /api/v1/executions/{id}` 与 SQLite 审计记录核查状态，确认无误后通过 `/admin/` 页面或 `POST /api/v1/runtime/recover` 人工确认恢复（不会自动重放中断任务）。
4. **`AGENT_UNAVAILABLE`（`409`）**：检查 `AGENT.md` 是否包含 `schema_version: 1`、`provider.name`、`provider.model`，以及对应环境变量或 `secrets.local.yaml`（`0600`）是否就位。

### 当前版本边界与限制
- **单机内核**：仅支持单节点 SQLite（WAL + `synchronous=FULL`）与单机进程互斥，未实现多节点分布式集群或跨机同步。
- **无内置登录认证**：默认面向回环/受控本地使用，`X-Oryx-User-Id` 仅用于会话身份隔离标识，不提供账号认证或 RBAC 鉴权。
- **应用层沙箱**：文件/Shell/HTTP 白名单校验在 JVM 应用层执行，不提供操作系统级容器或强沙箱隔离。
- **真实外部与性能缺口**：真实 DeepSeek/Kimi、自托管 Mem0、真实 Webhook/MCP 验收（`T027`、`T042`、`T057`、`T070`、`T071`、`T098–T100`）尚未在真实凭证下执行；`T101`（100 并发 `<= 50ms` 内部开销）尚未达标（当前本地短程最大 `112.14ms`）。
