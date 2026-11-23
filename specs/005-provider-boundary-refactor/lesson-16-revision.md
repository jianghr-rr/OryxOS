# 第 16 节课件修订建议（FR-013）

**对象**：仓库外课件 `第16节：Agent Provider 原理解析、实现与代码讲解.md`。本文件只给修订建议，**课件原文未修改**；
仓库内用户自行放入的 `docs/第16节…md` 副本同样未改动。

代码位置均为 spec 005 重构后的真实路径；`P/` = `oryxos-provider/src/main/java/io/github/oryxlabs/oryxos/provider/`，
`PT/` = `oryxos-provider/src/test/java/io/github/oryxlabs/oryxos/provider/`。

## 一、正文写法与现行实现

| 课件章节 | 课件原写法 | 现行写法 | 代码位置 | 依据 |
| --- | --- | --- | --- | --- |
| 三、核心类 | 核心类 `ProviderService` | 一致：入口类就叫 `ProviderService`，实现 `ProviderPort`；下面分两层：`AuditedProvider`（审计）和 `SpringAiProvider`（一次低层请求） | `P/ProviderService.java`、`P/AuditedProvider.java`、`P/SpringAiProvider.java` | spec 005 FR-006；技术方案 §1、§10 |
| 三、对外方法 | `chat(sessionId, Profile, Prompt)` | `ProviderReply call(ProviderRequest)`；请求对象携带 provider 名、模型、system、有序消息、工具 schema、名字映射、剩余时限、executionId 和 iteration | `oryxos-core/.../core/provider/ProviderRequest.java` | 审计按执行关联，执行再关联会话（spec 005“保留的偏差”第 4 条） |
| 三、第零步 | `.oryxos/profiles/*.yaml` + `ProfileLoader`/`ProfileRegistry` | Agent 唯一定义源是 `agents/<name>/AGENT.md` 的 frontmatter，由 `AgentCatalog` 加载 | `oryxos-core/.../core/agent/AgentCatalog.java` | spec 001 FR-002（“保留的偏差”第 1 条） |
| 三、第一步 | 全局层写在 `application.yaml` 的 `oryxos.providers` | 工作区 `runtime.yaml` 的 `providers` 是权威配置，重启加载；凭证用 `${ENV}` 或 `secrets.local.yaml` | `oryxos-core/.../core/config/RuntimeConfigLoader.java` | spec 001 FR-003（第 2 条） |
| 三、第一步 | 温度写在每个 Agent 的 Profile | 温度等协议选项属于 provider 定义的 `options`；Kimi 禁止发送 temperature | `P/ProviderOptionsMapper.java` | contracts/configuration.md（第 3 条） |
| 三、第二步 | `Map<String, ChatModel>` | `Map<String, ProviderPort>`，构造时按 `runtime.yaml` 逐条建表；未知名或配置错误返回 `PROVIDER_UNAVAILABLE`，只影响引用它的 Agent | `P/ProviderService.java` 构造器与 `call` | 坑一原则不变，载体不同 |
| 三、第三步 | `model.call(request(..., false))` 关闭自动执行 | 根本不使用 `ChatModel`，只用 `OpenAiApi` 低层单次请求，因此不存在自动执行开关；由结构守护测试防止回退 | `P/SpringAiProvider.java`、`P/OpenAiClientFactory.java`；`PT/ProviderArchitectureGuardTest.java` | plan 001 选用低层调用以保留 `reasoning_content`、`thinking` 等字段（第 6 条） |
| 三、第三步 | `catch (RuntimeException e) { audit.record(..., false, e.getMessage()); throw e; }` | 先写 STARTED，成功写 SUCCEEDED，失败写 FAILED + `error_code`，再抛出已分类的 `OryxError`；不落原始错误文本；记录写入失败时报 `RECORDING_UNAVAILABLE` 或 `PARTIAL_RECORDING_FAILURE`，不会被当成成功 | `P/AuditedProvider.java` | 宪章 IV 脱敏（第 5 条） |
| 三、第四步 | `llm_calls` 含 `success`/`error_message` 列 | `status`（STARTED/SUCCEEDED/FAILED）+ `error_code`，手工迁移脚本 | `oryxos-storage/src/main/resources/db/migration/V004__call_audits.sql`、`oryxos-storage/.../audit/LlmCallEntity.java` | 第 5 条 |
| 三、第四步 | 工具格式适配器 `toSpringAiTools` | 工具 schema 翻译在请求选项映射中完成，MCP 名字经 `ToolNameMap` 映射为 wire 名；原先只做转发的 `ToolWireNameMapper` 已删除 | `P/ProviderOptionsMapper.java`、`oryxos-core/.../core/model/ToolNameMap.java` | spec 005 FR-007 |
| （课件未写） | — | 一次模型调用只在 `ReActLoop` 获取一次运行准入，Provider 内部不再重复准入；请求合法性只在 `ProviderService` 入口校验一次 | `oryxos-core/.../core/react/ReActLoop.java`；`P/ProviderService.java#validate` | spec 005 FR-001 ~ FR-004；建议课件补一句“准入属于调用方” |

## 二、第四部分测试类名对照

| 课件测试类 | 现行测试类 | 说明 |
| --- | --- | --- |
| `ProfileLoaderTest` | `oryxos-core/.../agent/AgentCatalogContractTest` | Agent 定义解析、坏定义隔离、`${ENV}` 解析 |
| `ProviderServiceTest`（按名路由、未知名） | `PT/ProviderRoutingTest` | 双 provider 不串台、凭证缺失与空任务不发请求、非法请求零审计 |
| `ProviderServiceTest`（成功/失败都落审计） | `PT/ProviderAuditTest` | 失败分类不泄露、记录失败不当成功、失败调用先记 FAILED 再抛出 |
| `ProviderServiceTest`（自动执行关闭） | `PT/ProviderArchitectureGuardTest`；`PT/ProviderToolSchemaTest#returnsStructuredCallsAndReplaysReasoningWithoutExecutingTools` | 结构守护和行为测试各一个 |
| `ToolSchemaAdapterTest` | `PT/ProviderToolSchemaTest` | schema 翻译、MCP 名字映射与冲突检测、未知 wire 名 |
| `LlmCallRepositoryTest` | `oryxos-storage/.../PersistentStorageContractTest`、`oryxos-boot/.../RecordingFailureIT` | 手工迁移脚本建出的 `llm_calls` 可读写，记录失败语义 |
| `ProviderSmokeIT`（真实调用，`@Tag("integration")`） | `PT/ProviderSmokeTest`（回环演示）+ `PT/RealProviderIT`（`real-integration`，显式 `mode=real` 才运行） | 课件没有的：`PT/ProviderProtocolTest`（时限、重定向、断连）、`PT/ProviderTransportTest`（连接复用与计时）、`PT/ProviderAdmissionTest`（单次准入） |

## 三、课件中可直接沿用的部分

“职责划窄”“坑一：显式映射”“坑三：先确认依赖可解析”“先别做 fallback/hedge/熔断”这几段与现行实现一致，不需要改。
