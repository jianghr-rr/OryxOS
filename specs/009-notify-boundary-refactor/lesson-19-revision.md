# 第 19 节课件修订建议（Notify 模块边界对齐）

**对应规格**：`specs/009-notify-boundary-refactor/spec.md`
**对齐基线**：宪章 v1.0.0、spec 001~008、`docs/TechnicalSolution.md` §6.8 / §10

---

## 1. 保留偏差与代码对照表

| 编号 | 课件原写法 | 现行生产实现 | 代码位置 | 保留依据 |
| --- | --- | --- | --- | --- |
| D1 | 工具类名为 `NotifyTools`，使用 Spring AI `@Tool` 注解声明 `notify(content, channel)`，直接注入 `Sandbox`、`ProfileContext` | 保留 `NotifyTool implements OryxTool`（工具名 `"notify"`，参数 `content` + 可选 `targetName`），注入 `NotificationTargetResolver` 与接口 `NotifyChannelAdapter`；由 `ToolRegistry` + `ToolExecutor` 统一校验、调度与落库审计 | `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyTool.java`、`oryxos-boot/src/main/java/io/github/oryxlabs/oryxos/boot/config/ToolConfiguration.java` | 宪章 II 禁止 Spring AI 自动执行工具；全仓内置工具统一采用 `OryxTool` 契约，避免破坏装配层与 CLI 查询 |
| D2 | `WebhookNotifyAdapter` 使用 Spring `RestClient` 发固定 `{"content": ...}`，5xx 抛异常 | 基于 `ControlledHttpClient`（Apache HttpClient 5 + `HttpTargetPolicy` 白名单 + 超时 + 响应上限 + 禁用重定向），支持 `$CONTENT` JSON 安全转义、自定义 payload 模板与 `http_2xx`/`json_equals` 确认；5xx/业务拒绝返回 `ToolResult(ERROR, NOTIFY_REJECTED)` 不重试，断连返回 `UNKNOWN` 不重试 | `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java`、`oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/http/ControlledHttpClient.java` | `oryxos-tool` 不引入 `spring-web`；宪章 II / IV 要求副作用不隐式重试且对未知结果诚实标记 |
| D3 | `NotifyTarget(String channelType, Map<String, String> config)`，Profile 中 `notify_channels` 为简化列表 | `NotifyTarget(String channelType, Map<String, Object> config)`（`toString()` 脱敏）无损承载结构化 `payload`/`headers`/`acknowledgement` 同时兼容极简 `Map.of("url", ...)`；`AGENT.md` 的 `notify_channels` 为按目标名索引的映射并强制 URL/Header 走密钥引用 | `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyTarget.java`、`oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/agent/AgentDefinitionReader.java` | 宪章 IV 禁止明文密钥与配置泄露；多目标与企业 IM 业务确认需要结构化配置 |
| D4 | 权限校验调用 `Sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))` | 通过 `ToolExecutor` → `NotifyTool.authorize` → `NotifyChannelAdapter.authorize(NotifyTarget)` → `ControlledHttpClient.authorize(url)` 实现白名单前置拦截；`NotifyChannelAdapter.authorize` 注释已标明第 24 节 `Sandbox` 统一收敛点 | `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyChannelAdapter.java`、`oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyTool.java` | 统一 `Sandbox` 抽象归属第 24 节，不在本节提前发明 24 节对外接口 |
| D5 | 课件说明 `NotifyTools` 与 `NotifyToolsTest` 需等 20/24 节后补跑，且测试示例使用 Mockito/MockWebServer | 本仓库 spec 001 已交付 `ToolExecutor` 与 `HttpTargetPolicy`，因此 `WebhookNotifyAdapterTest` 与 `NotifyToolsTest` 本节已全部落地；使用 JDK `HttpServer` 与轻量录制替身 `RecordingAdapter` 替代 Mockito/MockWebServer | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapterTest.java`、`NotifyToolsTest.java` | 不新增未计划第三方依赖，同时以自定义 `NotifyChannelAdapter` 实现直接证明接口可替换性 |

---

## 2. 验收 Harness 映射表

全部纳入 `tools/dev/test-groups.json` 的 `us4-tools` 分组（21 → 29 项）：

| 课件测试类 | 仓库路径 | 覆盖回归点 |
| --- | --- | --- |
| `WebhookNotifyAdapterTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapterTest.java` | `send_postsContentToTargetConfiguredUrl`、`send_returnsErrorWithoutRetryOnServer5xx`、`send_returnsErrorWhenBusinessAckRejected`、`send_returnsUnknownWithoutRetryWhenConnectionDrops` |
| `NotifyToolsTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/NotifyToolsTest.java` | `notify_failsExplicitlyWhenNotifyChannelsNotConfigured`、`notify_usesDefaultChannelWhenTargetNameOmitted`、`notify_enforcesAuthorizationStrictlyBeforeSend`、`notify_worksWithCustomAdapterAndWritesToolAudit` |
| 既有 `NotifyContractTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/NotifyContractTest.java` | 保持 4 项零改动全绿（转义/业务 ack/密钥防泄露、缺省与越权拒绝、断连 UNKNOWN、GET/POST 重试边界） |
