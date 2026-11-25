# Research: 第 19 节 Notify 模块现状与接口设计核查

## 1. 本地依赖树核实

已运行 `./tools/dev/mvnw -B -o -pl oryxos-tool dependency:tree -DoutputType=text`（JDK 21.0.13）：
- `org.apache.httpcomponents.client5:httpclient5:5.6.5`
- `io.github.oryx-labs:oryxos-core:1.0.0-SNAPSHOT`
- `io.modelcontextprotocol.sdk:mcp:0.18.3`
- `org.springframework.ai:spring-ai-model:1.1.8`（传递 `jackson-databind:2.21.4`）
- `com.networknt:json-schema-validator:1.5.9`
- `org.junit.jupiter:junit-jupiter:5.12.2`（test）

结论：
- `oryxos-tool` 未引入 `spring-web`（无 `RestClient`），也未引入 `mockito` 或 `mockwebserver`。
- 遵循“不新增 plan 未列明的第三方依赖”原则：HTTP 推送沿用既有 `ControlledHttpClient`；假 webhook 使用 JDK 内置 `com.sun.net.httpserver.HttpServer`；`InOrder` 顺序断言通过实现 `NotifyChannelAdapter` 的轻量录制替身（记录 `List<String>` 事件序列）完成，比 mock 框架更直接验证接口可替换性。

## 2. 既有代码与课件对照分析

### 2.1 `oryxos-tool` notify 包现状
- `NotificationTargetResolver`：从 `AgentProfile.notifications()` 解析并持有内部 `Target(url, headers, payload, acknowledgement, defaultTarget)`，已通过 `SecretResolver` 延迟解析密钥且 `toString()` 脱敏。
- `WebhookNotifyAdapter`：独立具体类（未实现任何接口），提供 `authorize(Target)` 与 `send(Target, String, ExecutionContext)`，支持 `$CONTENT` 占位符 JSON 转义、`http_2xx` / `json_equals` 业务确认，以及断连返回 `UNKNOWN` 不重试。
- `NotifyTool`：实现 `OryxTool`（工具名 `"notify"`，参数 `content`、可选 `targetName`），构造器直接绑定具体类 `WebhookNotifyAdapter`。

### 2.2 `oryxos-boot` 与其他模块引用核查
- `ToolConfiguration.notifyTool(...)`：`new NotifyTool(new NotificationTargetResolver(...), new WebhookNotifyAdapter(http))`
- `BootCliQueries`：`NotifyTool.staticDescriptor()`
- `NotifyContractTest`、`RealNotificationIT`、`TencentCloudConfigTemplateTest`：均构造 `new NotifyTool(resolver, new WebhookNotifyAdapter(http))`

结论：将 `NotifyTool` 构造器参数放宽为 `NotifyChannelAdapter`，并让 `WebhookNotifyAdapter implements NotifyChannelAdapter` 同时保留既有 `Target` 重载方法，可实现 **100% 源码兼容与零改动回归**。

## 3. `NotifyTarget` 与 `NotifyChannelAdapter` 设计决策

- **决策**：`NotifyTarget(String channelType, Map<String, Object> config)` 采用 `Map<String, Object>` 而非 `Map<String, String>`。
  - **理由**：既有 webhook 配置包含结构化 `payload`（`Map<String, Object>`）、`headers`（`Map<String, String>`）与 `acknowledgement`（`Map<String, Object>`），使用 `Map<String, Object>` 既能无损承载现行生产配置，又完全兼容极简构造 `new NotifyTarget("webhook", Map.of("url", url))`（缺失 `payload`/`headers`/`acknowledgement` 时使用安全默认值：`{"content": "$CONTENT"}`、空 headers、`http_2xx`）。
  - **安全**：`NotifyTarget` 紧凑构造器防御性拷贝 `config`，重写 `toString()` 屏蔽 `config` 明文，杜绝日志或异常泄露 webhook URL 与私有 Header。
- **决策**：`NotifyChannelAdapter` 定义 `authorize(NotifyTarget)` 与 `send(NotifyTarget, String, ExecutionContext)` 两个方法。
  - **理由**：与 `OryxTool` 两阶段生命周期（`authorize` → `execute`）精准对应，确保 `ToolExecutor` 先验白名单再执行外发；第 24 节引入统一 `Sandbox` 后只需在 `authorize` 内部对接 `Sandbox.enforce`，无需改动 `NotifyChannelAdapter` 或 `NotifyTool` 签名。
  - **接口中立性检验**：若未来新增企业微信官方 SDK 适配器（`WeComNotifyAdapter`），只需从 `target.config()` 读取 `corpId`/`agentId`/`secretRef` 并在 `authorize` / `send` 中完成校验与发送，接口签名无需任何修改。
