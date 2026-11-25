# Data Model & Test Mapping: 第 19 节 Notify 模块

## 1. 新增与演进的领域契约（`oryxos-tool`）

### 1.1 `NotifyTarget`
- **包路径**：`io.github.oryxlabs.oryxos.tool.notify.NotifyTarget`
- **字段**：
  - `channelType` (`String`, 非空)：渠道类型标识（核心阶段为 `"webhook"`，扩展阶段可为 `"wecom"`、`"feishu"` 等）
  - `config` (`Map<String, Object>`, 不可变拷贝)：渠道特定配置键值表，调用方与工具层不解析内部细节
- **安全约束**：`toString()` 仅输出 `NotifyTarget[channelType=..., config=<redacted>]`，严禁打印 `config` 内容。

### 1.2 `NotifyChannelAdapter`
- **包路径**：`io.github.oryxlabs.oryxos.tool.notify.NotifyChannelAdapter`
- **方法**：
  - `void authorize(NotifyTarget target)`：外发前的权限与白名单校验（24 节对接 `Sandbox.enforce`）
  - `ToolResult send(NotifyTarget target, String content, ExecutionContext ctx)`：将内容推送到目标，返回结构化执行结果

### 1.3 `WebhookNotifyAdapter`
- 实现 `NotifyChannelAdapter`。
- 从 `NotifyTarget.config()` 读取：
  - `url`（必填 `String`）
  - `headers`（可选 `Map<String, String>`，默认空）
  - `payload`（可选 `Map<String, Object>`，默认 `Map.of("content", "$CONTENT")`）
  - `acknowledgement`（可选 `Map<String, Object>`，默认 `Map.of("mode", "http_2xx")`）
- 保留 `authorize(NotificationTargetResolver.Target)` 与 `send(NotificationTargetResolver.Target, String, ExecutionContext)` 兼容方法，内部委托 `target.toNotifyTarget()`。

### 1.4 `NotifyTool`
- 构造器签名：`NotifyTool(NotificationTargetResolver targets, NotifyChannelAdapter adapter)`。
- `authorize(args, ctx)` → `adapter.authorize(targets.resolve(ctx.agent(), args.get("targetName")).toNotifyTarget())`。
- `execute(args, ctx)` → `adapter.send(targets.resolve(ctx.agent(), args.get("targetName")).toNotifyTarget(), (String) args.get("content"), ctx)`。

## 2. 验收 Harness 测试类与回归守点映射

全部新增测试打 `@Tag("us4-tools")` 并登记至 `tools/dev/test-groups.json` 的 `us4-tools` 分组（21 → 29 项）。

### 2.1 `WebhookNotifyAdapterTest`（4 项）
路径：`oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapterTest.java`

| 英文测试方法名 | `@DisplayName`（课件对照） | 守住的验收点 |
| --- | --- | --- |
| `send_postsContentToTargetConfiguredUrl` | 发送后请求发往 NotifyTarget 配置的动态 URL 且 body 包含正确编码的 content | URL 来自 `NotifyTarget.config`，非硬编码；支持引号/换行/Unicode 安全编码 |
| `send_returnsErrorWithoutRetryOnServer5xx` | webhook 返回 5xx 时明确返回失败、不静默吞掉且不重试 | 5xx 返回 `Outcome.ERROR`，服务端调用次数为 1 |
| `send_returnsErrorWhenBusinessAckRejected` | webhook 业务确认失败时明确返回失败且不静默成功 | `json_equals` 校验失败返回 `Outcome.ERROR`（`NOTIFY_REJECTED`） |
| `send_returnsUnknownWithoutRetryWhenConnectionDrops` | 请求发出后连接断开返回 UNKNOWN 且不自动重试 | 返回 `Outcome.UNKNOWN` + `SideEffectState.UNKNOWN`，调用次数为 1 |

### 2.2 `NotifyToolsTest`（4 项）
路径：`oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/NotifyToolsTest.java`

| 英文测试方法名 | `@DisplayName`（课件对照） | 守住的验收点 |
| --- | --- | --- |
| `notify_failsExplicitlyWhenNotifyChannelsNotConfigured` | notify_channels 未配置时明确报错且不触发发送 | 抛出 `OryxError(INVALID_ARGUMENTS)`，适配器调用次数为 0 |
| `notify_usesDefaultChannelWhenTargetNameOmitted` | channel 参数缺省时自动取唯一或默认渠道 | 省略 `targetName` 时正确解析到默认目标并送出 `content` |
| `notify_enforcesAuthorizationStrictlyBeforeSend` | 发送前必须先过白名单校验 | `ToolExecutor` 链路下事件序列严格为 `["authorize", "send"]`；授权抛异常时 `send` 绝不被调用 |
| `notify_worksWithCustomAdapterAndWritesToolAudit` | NotifyTool 仅依赖 NotifyChannelAdapter 接口并复用 ToolExecutor 审计 | 注入自定义非 webhook `NotifyChannelAdapter` 无需改 `NotifyTool`，成败均落 `tool_invocations` 审计 |
