# Final Verification Evidence: 第 19 节 Notify 模块边界对齐

- **Date**: 2026-11-24
- **Branch**: `009-lesson19-notify-boundary`
- **JDK**: OpenJDK 21.0.13 (`/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home`)

---

## 1. 全量构建与静态门禁证据（DoD #1）

### 1.1 `us4-tools` 分组门禁
命令：
```bash
ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home \
  ./tools/dev/mvnw -B -o clean test -Dgroups=us4-tools
```
结果：
- 10/10 模块 `BUILD SUCCESS`（20.349s）
- `Test evidence PASS: 29 required cases; run 3113baf6-ede1-427a-b3a1-c17bc0a0a77f`

### 1.2 全量 `clean verify`（含 Spotless / Checkstyle-P3C / PMD / SpotBugs-FindSecBugs / 全量回归）
命令：
```bash
ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home \
  ./tools/dev/mvnw -B -o clean verify
```
结果：
- 10/10 模块 `BUILD SUCCESS`（10:40 min）
- `You have 0 Checkstyle violations.`（全模块）
- `oryxos-core` 61/61、`oryxos-tool` 30/30、`oryxos-boot` 123/123 等全量测试 0 失败、0 错误、0 跳过
- `Test evidence PASS: 373 required cases; run f77e7b56-10e0-4cbc-9623-20a06ff9b043`
- `Quality gate PASS: 444 baseline entries, 3993 report-only findings`

### 1.3 质量基线只减不增
命令：
```bash
./tools/dev/quality-baseline --check-shrink origin/main
```
结果：`Baseline did not grow against origin/main: 444 entries`

---

## 2. 课件 Harness 映射核对（DoD #2）

| 测试类 | 路径 | 用例数 | 关键回归点 |
| --- | --- | --- | --- |
| `WebhookNotifyAdapterTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapterTest.java` | 4 | 动态目标 URL + 正文安全编码；5xx 失败不静默不重试；业务 ack 拒绝；断连返回 `UNKNOWN` 不重试 |
| `NotifyToolsTest` | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/NotifyToolsTest.java` | 4 | `notify_channels` 未配置明确报错零发送；缺省目标名取默认渠道；`authorize` 严格先于 `send`（拒绝时阻断发送）；自定义非 webhook 适配器 + `ToolExecutor` 审计 |
| `NotifyContractTest`（既有） | `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/NotifyContractTest.java` | 4 | 零改动全绿 |

---

## 3. 本节交付物存在性核对（DoD #3）

- `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyChannelAdapter.java`（存在）
- `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyTarget.java`（存在）
- `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java`（存在，已实现 `NotifyChannelAdapter`）
- `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyTool.java`（存在，已解耦依赖 `NotifyChannelAdapter`）
- `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotificationTargetResolver.java`（存在，新增 `Target.toNotifyTarget()`）
- `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapterTest.java`（存在）
- `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/NotifyToolsTest.java`（存在）
- `notify_channels` Profile 配置解析：`AgentDefinitionReader` 与 `AgentProfile` 已就位，前序回归全绿

---

## 4. 前序节全部测试回归（DoD #4）

第 16 节（Provider）、第 17 节（ReAct/ToolExecutor/AgentService/PromptBuilder/ContextLoader）、第 18 节（CLI/SessionManager/SessionRepository）全部回归包含于本次 `clean verify`（373 项必跑用例全部通过，0 失败、0 错误、0 跳过）。

---

## 5. H4 六条全局不变量自查（DoD #5）

1. **涉外 IO 首行过白名单/Sandbox 校验**：通过。`ToolExecutor` 在调用 `NotifyTool.execute` 之前严格调用 `NotifyTool.authorize` → `NotifyChannelAdapter.authorize(NotifyTarget)` → `ControlledHttpClient.authorize(url)`；`NotifyChannelAdapter.authorize` 注释已标明第 24 节 `Sandbox.enforce` 对接点；`NotifyToolsTest.notify_enforcesAuthorizationStrictlyBeforeSend` 已用事件序列钉死。
2. **LLM/工具调用成败均落审计**：通过。`NotifyTool` 复用 `ToolExecutor` 统一审计路径，`NotifyToolsTest.notify_worksWithCustomAdapterAndWritesToolAudit` 验证成功（`STARTED` + `SUCCESS`）与拒绝（`DENIED`）均落审计且不泄露凭证。
3. **无明文密钥**：通过。`grep -rnE "sk-|Bearer |secret-key|api_key=|token=" oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/` 返回 CLEAN；`NotifyTarget.toString()` 显式脱敏 `<redacted>`。
4. **`session_id` 只在 `SessionManager` 内拼接/生成**：通过。本节未触碰 `oryxos-core` 会话层。
5. **无 Reactor/`CompletableFuture`/自建线程池**：通过。`grep -rnE "Mono|Flux|CompletableFuture|Executors\.|new Thread" oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/` 返回 CLEAN。
6. **无 Spring AI 自动工具执行路径**：通过。`grep -rnE "@Tool|toolCallbacks|internalToolExecutionEnabled" oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/` 返回 CLEAN。

---

## 6. 剩余人工验收项（DoD #6）

1. **真实 webhook 推送验证**：在私有工作区配置真实企业微信/飞书/钉钉群机器人 webhook 环境变量并运行 `RealNotificationIT`（或经 `oryxos chat` 触发 `notify`），确认群内实际收到推送消息。
2. **接口中立性人工复核**：对照 `NotifyChannelAdapter` 与 `NotifyTarget`，确认切换为企业微信/飞书官方 SDK 实现时无需改动接口签名或 `NotifyTool`。
