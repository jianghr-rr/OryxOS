# 契约：日志、追踪标识与运维端点

对应需求：FR-001 ~ FR-009、SC-003、SC-004。本契约描述外部可观察的行为，不规定具体实现类。

## 1. 日志输出形态

| 形态 | 选择方式 | 输出目标 | 格式 |
| --- | --- | --- | --- |
| 开发（默认） | 未激活 `prod` profile | stderr | 文本；终端支持时带颜色（`spring.output.ansi.enabled=detect`）；消息与异常栈已脱敏 |
| 生产 | `SPRING_PROFILES_ACTIVE=prod`（服务器 systemd drop-in 设置） | stderr + `${ORYXOS_LOG_DIR}/oryxos.json` | 每行一条 JSON |

两种形态都**不写 stdout**。CLI 命令的 stdout 业务输出和 stderr 诊断输出（例如 `SERVE: ...` 提示、`CONFIGURATION_ERROR` 诊断）属于命令行契约，格式保持不变，不经过日志门面（FR-004）。

### 1.1 生产 JSON 字段

| 字段 | 必有 | 说明 |
| --- | --- | --- |
| `@timestamp` | 是 | ISO-8601，带时区偏移 |
| `level` | 是 | `TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR` |
| `logger_name` | 是 | 记录器名 |
| `thread_name` | 是 | 线程名（虚拟线程同样输出） |
| `message` | 是 | 已脱敏 |
| `application` | 是 | 固定为 `oryxos` |
| `traceId` | HTTP 请求处理期间 | 与该请求响应信封中的 `traceId` 相同 |
| `executionId` | Agent 执行期间 | 现有执行 ID（HTTP、CLI、定时触发都有） |
| `stack_trace` | 有异常时 | 已脱敏 |

不输出 `HOSTNAME`、调用者文件/行号等字段（体积与主机信息控制）。

### 1.2 文件滚动（生产形态）

| 参数 | 默认 | 覆盖方式（环境变量） |
| --- | --- | --- |
| 目录 | `/var/log/oryxos` | `ORYXOS_LOG_DIR` |
| 单文件上限 | 50MB | `ORYXOS_LOG_MAX_FILE_SIZE` |
| 保留天数 | 14 | `ORYXOS_LOG_MAX_HISTORY` |
| 目录总量上限 | 1GB | `ORYXOS_LOG_TOTAL_SIZE_CAP` |

文件名为 `oryxos.json`，归档文件为 `oryxos.<yyyy-MM-dd>.<n>.json.gz`。
目录不存在且无法创建，或者不可写时：服务**照常启动**，stderr 输出一行以 `[oryxos-logging] WARN log directory not writable:` 开头的告警，文件日志关闭，stderr 日志不受影响。

### 1.3 脱敏

所有字符串字段（含 `message`、`stack_trace`、MDC 值）都经过运行时 `Redactor`：先替换已注册的密钥值为 `[REDACTED]`，再把 URL 替换为 `[REDACTED_URL]`，最后把 `authorization|api_key|token|password` 形式的键值替换为 `[REDACTED]`。口径与现有审计记录一致（FR-005）。

## 2. 请求追踪标识

| 项 | 约定 |
| --- | --- |
| 上游请求头 | `X-Request-ID` |
| 沿用条件 | 值匹配 `^[A-Za-z0-9._:-]{8,64}$`，否则忽略并重新生成 |
| 生成格式 | UUID v4 小写字符串（与现有响应格式一致） |
| 作用范围 | 业务 HTTP 请求从进入过滤器到响应写出；包括虚拟线程派发、超时定时器与执行完成回调中产生的日志 |
| 响应 | 信封字段 `traceId` 的取值等于该标识；字段名、类型和 OpenAPI schema 不变 |
| 非 HTTP 入口 | CLI 与定时执行不生成 `traceId`，日志用 `executionId` 检索（FR-003） |

不同请求的标识互不相同；请求结束后 MDC 必须清除，复用线程上不得残留上一个请求的标识。

### 2.1 访问日志

为保证"用响应 `traceId` 在日志中检索必然命中"（SC-003），每个 `/api/v1/**` 请求结束时（异步请求在异步完成时）输出一条 INFO 日志：

| 字段 | 值 |
| --- | --- |
| `logger_name` | `io.github.oryxlabs.oryxos.web.access` |
| `message` | `http_request method=<M> path=<路径，不含查询串> status=<状态码> durationMs=<毫秒>` |
| `traceId` | 该请求的追踪标识 |

`/actuator/**` 与 `/admin/**` 静态资源不记访问日志（Prometheus 每 15 秒采集一次，记录下来只是噪声）。处理期间抛出的未预期异常（映射为 500）以 ERROR 级别记录，附带已脱敏的异常栈。

## 3. 运维端点

都只监听 `127.0.0.1:8080`（与业务共用），**不出现在 `/v3/api-docs`**。

| 端点 | 方法 | 正常响应 | 未就绪 / 异常 |
| --- | --- | --- | --- |
| `/actuator/health` | GET | 200，`{"status":"UP", "groups":["liveness","readiness"]}` | 运行时未就绪：503，`status=OUT_OF_SERVICE` |
| `/actuator/health/liveness` | GET | 200，`UP` | 只有进程级失效才报 `DOWN` |
| `/actuator/health/readiness` | GET | 200，`UP` | `PAUSED_RECORDING`、存储不可用或启动中：503，`OUT_OF_SERVICE` |
| `/actuator/info` | GET | 200，构建信息（应用名、版本、构建时间） | — |
| `/actuator/prometheus` | GET | 200，`text/plain; version=0.0.4` 或 OpenMetrics 文本 | — |

- 健康详情：`show-details=never`、`show-components=never`。
- 就绪判定与 `/api/v1/health` 使用同一个来源（`WebRuntimePort.health().ready()`），两者结论始终一致。
- 指标至少包含：`jvm_memory_used_bytes`、`jvm_gc_pause_seconds_*`、`jvm_threads_live_threads`、`http_server_requests_seconds_count/sum`（含 `uri` 模板、`status` 标签）、`process_uptime_seconds`；**所有指标都带 `application="oryxos"`**。
- 其他 actuator 端点（`env`、`beans`、`heapdump`、`threaddump`、`metrics` 等）一律不暴露。

### 3.1 既有端点（保持零差异）

`/api/v1/health`、`/api/v1/info` 及全部 19 个已发布操作、44 个 schema 保持不变，由 `PublishedOpenApiTest` 验证。发布脚本与回滚脚本继续使用 `/api/v1/health`，不改为 actuator。

## 4. 公网访问控制（nginx 参考片段的变更）

在 `deploy/tencent-cloud/nginx/oryxos-baota-vhost.conf.tpl` 中：

```nginx
# 运维端点与 OpenAPI 文档一样，对公网一律 403
location ~ ^/actuator(/|$) {
    return 403;
}
```

并在现有 `location /` 中增加 `proxy_set_header X-Request-ID $request_id;`。Grafana 与 Prometheus 的入口见 [server-config.md](./server-config.md#4-nginx-参考片段)。
