# US5 T102 新使用者 30 分钟上手验证证据

- **验证日期**：2026-11-18（Asia/Shanghai）
- **对应任务**：`T102`（`FR-041`、`SC-010`）
- **实际耗时**：**`7.22` 秒**（远低于 30 分钟目标）
- **外部前提声明**：本次按无外部付费账号边界使用**本地回环 OpenAI 兼容模型桩**完成新工作区端到端启动、任务执行、查询与管理台联通验证；不冒充真实 DeepSeek/Kimi 联调（`T027`/`T042`/`T098–T100` 仍保持未完成）。

---

## 1. 实测步骤与观察输出

1. **前置发行构建**：已通过 `./tools/dev/mvnw -B -ntp verify`（`runId=5d5857fe-fc39-4461-b760-4978a6469597`，`322` 项必跑用例全绿，生成 `oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar`）。
2. **CLI 入口核查**：
   - `./tools/dev/oryxos --version` -> `OryxOS 1.0.0-SNAPSHOT`（exit `0`）。
   - `./tools/dev/oryxos --help` -> 列出全部 9 个顶层命令及子命令（含 `profile` 4 个子命令共 12 项 CLI 能力）。
   - `./tools/dev/oryxos serve --help` -> 确认 `serve` 不接受 `--port`，端口通过 `SERVER_PORT` 环境变量指定。
3. **全新临时工作区初始化与配置（`/private/var/folders/.../oryxos-t102-l8ipkxa1/ws`）**：
   - `init` 连续两次执行均返回 `0`（幂等不覆盖）。
   - `status --json` 返回 `code=OK`、`backend=md`、`timezone=Asia/Shanghai`。
   - `profile create onboarding-agent` 后写入 `runtime.yaml`（指向本地回环桩）、`secrets.local.yaml`（`chmod 600`）与 `agents/onboarding-agent/AGENT.md`（含 `schema_version: 1`）。
   - `profile list --json` 显示 `onboarding-agent` 为 `CONFIGURED`；`provider list --json` 显示 `stub` 为 `configured=true`、`connectivity=NOT_PROBED`；`tool list --json` 显示 `OFFLINE_STATIC`、`mcpDiscovery=NOT_DISCOVERED`。
   - 首启前 `session list --json` 返回退出码 `3`（`STORAGE_UNAVAILABLE`），未创建空库。
4. **启动 `serve`、执行任务、查询并检查 `/admin/`**：
   - 使用 `SERVER_PORT=<随机可用端口>` 启动 `./tools/dev/oryxos --root "$WS" serve`。
   - `GET /api/v1/health` 返回 `HTTP 200`：`{"code":"OK","data":{"live":true,"ready":true,"state":"READY"}}`。
   - `POST /api/v1/sessions`（`X-Oryx-User-Id: newcomer`）创建会话 ID：`76758df7-fb26-4410-b6a4-794cc649cbac`。
   - `POST /api/v1/sessions/76758df7-fb26-4410-b6a4-794cc649cbac/messages` 触发执行 ID：`fdb852be-eb85-4b33-86bf-90ce8fccb421`，返回 `status=SUCCEEDED`、`content=ONBOARDING_OK`。
   - `GET /api/v1/sessions/76758df7-fb26-4410-b6a4-794cc649cbac` 查回 `2` 条持久化消息；`GET /api/v1/executions/fdb852be-eb85-4b33-86bf-90ce8fccb421` 返回 `status=SUCCEEDED`、`recordingStatus=COMPLETE`。
   - `GET /admin/` 返回 `HTTP 200`（HTML 长度 `3794` 字节）。
5. **停机后跨进程持久化核查**：
   - 终止 `serve` 后运行 `./tools/dev/oryxos --root "$WS" session list --json`，返回退出码 `0` 并列出上述 `http:newcomer:onboarding-agent` 会话。

---

## 2. 文档同步核对结论

- [docs/CliGuide.md](../../../docs/CliGuide.md)：已删除过时的 `mvn package -DskipTests`、`oryxos serve [--port 8080]` 与早期分节接线注释；补齐 `./tools/dev/mvnw` / `./tools/dev/oryxos`、`SERVER_PORT`、`schema_version: 1`、12 项 CLI、19 个 HTTP 操作、`/admin/`、故障人工恢复及当前单机/无认证/应用层沙箱限制。
- [tools/dev/README.md](../../../tools/dev/README.md) 与 [quickstart.md](../quickstart.md)：已更新顶部进度与 `T102` 上手路径及当前限制，保留既有真实外部联调与 `T101` 缺口记录。
