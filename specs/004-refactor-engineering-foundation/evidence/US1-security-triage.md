# US1：SpotBugs 安全告警分类（T022）

来源：格式化提交 `5b288ba` 之后的 `./tools/dev/mvnw clean verify` 报告（2026-11-21）。SpotBugs 共报告 48 条，以 `(tool, rule, file)` 去重前计数；其中 Find Security Bugs 类 8 条，逐条判定如下。

| 位置 | Bug pattern | 判定 | 去向 | 理由 |
| --- | --- | --- | --- | --- |
| `tool/shell/ShellTool.java:45` | `COMMAND_INJECTION` | 设计内正确行为 | `config/spotbugs/exclude.xml`（类 + pattern） | shell 工具的职责就是执行命令；argv、工作目录、环境变量均由 `ProcessPolicy`（`WhitelistSandbox`）授权后给出 |
| `tool/mcp/SanitizedStdioTransport.java:97` | `COMMAND_INJECTION` | 设计内正确行为 | exclude.xml | MCP stdio 服务的启动命令来自维护者编写的 `mcp_servers.yaml`，不来自模型输入 |
| `storage/query/SqliteReadOnlyQueries.java:197` | `SQL_INJECTION_JDBC` | 误报 | exclude.xml | `where` 只由代码内常量列名拼接（`condition(...)` 追加 `column=?`），所有值走参数绑定 |
| `storage/session/JpaSessionStore.java:297` | `SQL_INJECTION_JDBC` | 误报 | exclude.xml | 动态部分只有 `IN (?,?,...)` 占位符个数，值全部 `setString` 绑定 |
| `storage/transaction/StoredSql.java:97` | `SQL_INJECTION_JDBC` | 误报 | exclude.xml | 调用方传入的 SQL 均为 `static final` 常量（`INSERT_SESSION`、`SELECT_CONTEXT` 等），值走 `setObject` |
| `storage/transaction/WorkspaceWriteCoordinator.java:452` | `SQL_INJECTION_JDBC` | 误报 | exclude.xml | `PRAGMA busy_timeout=` 拼接的是计算得到的 `int`，不是外部输入 |
| `storage/migration/SchemaMigrator.java:60` | `UNSAFE_HASH_EQUALS` | 误报 | exclude.xml | 比较的是迁移脚本的公开校验和，不是秘密，计时侧信道无意义 |
| `core/runtime/Uuids.java:11` | `PREDICTABLE_RANDOM` | **待维护者决定** | 基线 | 会话、执行、追踪 ID 用 `ThreadLocalRandom` 生成。当前规格是受信本地/内网、无认证，ID 不作为访问凭证，因此暂不构成漏洞；若将来把会话 ID 当作访问凭证，必须改为 `SecureRandom`。不登记为“设计内正确行为”，留在基线中便于追踪 |

其余 40 条为非安全类（`NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`、`REC_CATCH_EXCEPTION`、`AT_STALE_THREAD_WRITE_OF_PRIMITIVE`、`USO_UNSAFE_*_SYNCHRONIZATION`、`WMI_WRONG_MAP_ITERATOR`），全部进入基线，按“只减不增”逐步清理。

例外登记后重新生成报告，上述 7 条不再出现；`quality-gate.py` 的抑制文件检查通过（每条 `<Match>` 前都有 `reason:` 与 `registered:`）。
