# 第 17 节课件修订建议（FR-010）

**对象**：仓库内副本 `docs/第17节：ReAct 原理解析、实现与代码讲解.md`。本文件只给修订建议，**课件原文未修改**。

代码位置均为 spec 006 完成后的真实路径；`C/` = `oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/`，
`CT/` = `oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/`，
`S/` = `oryxos-storage/src/main/java/io/github/oryxlabs/oryxos/storage/`。

## 一、正文写法与现行实现

| 课件章节 | 课件原写法 | 现行写法 | 代码位置 | 依据 |
| --- | --- | --- | --- | --- |
| 三、主角 ReActLoop | `String run(Session, String userMessage, Profile)` 返回最终文本 | `void run(ExecutionContext, SessionManager.Lease)`；用户消息在入口已落库，结果（状态、停止原因、回复）写入执行记录，CLI/HTTP 从执行记录取回复 | `C/react/ReActLoop.java:37` | spec 001 US2 |
| 三、主角 ReActLoop | 每轮都执行模型提出的工具；转满返回“达到最大轮数，已停止” | 最后一轮提出的工具**不执行**，以 `NOT_EXECUTED`/`ITERATION_LIMIT` 配对回填（其结果再无机会交给模型）；结束文案“达到迭代上限；已确认完成 N 个工具请求，目标未完成，剩余提议未执行。” | `C/react/ReActLoop.java:114`、`:165-171` | spec 001 US2-A5 |
| 三、主角 ReActLoop | `providerService.chat(session.id(), profile, prompt)` | `ProviderPort.call(ProviderRequest)`；请求携带 `executionId` 与 `iteration`，审计按执行关联、执行再关联会话；运行准入只在循环里申请一次 | `C/react/ReActLoop.java`（`react.provider` 段）；`C/provider/ProviderRequest.java` | spec 005 保留偏差第 4 条、FR-001 |
| 三、主角 ReActLoop | `toolExecutor.execute(session.id(), call)` | `ToolExecutor.execute(ExecutionContext, ToolCall)`，返回 `Attempt`（结果 + 校验 + 审计记录） | `C/tool/ToolExecutor.java:35` | 同上 |
| 三、配角一 PromptBuilder | system prompt 末尾附日期时间，再接长期记忆、会话历史、工具 | 一致：系统提示依次为正文 → Bootstrap → `Current workspace time` → CORE 记忆 → ARCHIVAL 记忆；消息为近期历史 → 当前轮；工具 schema 单独字段。日期时间位于“system 部分”末尾、记忆之前 | `C/context/PromptBuilder.java:17-21` | CLAUDE.md PromptBuilder 组合顺序 |
| 三、配角一 PromptBuilder | 历史只留最近 N 轮（默认 20） | 一致：`max_history_turns` 默认 20，按完整轮次截取，不删持久化历史；截取在记忆门面完成，`PromptBuilder` 只拼装 | `C/agent/AgentDefinitionReader.java:172`；`oryxos-memory/.../MemoryService.java` | spec 001 |
| 三、配角二 ToolExecutor | 先过沙箱再执行，成败都记日志 | 一致且更严：先校验参数、再 `authorize`（Sandbox 白名单），先写 STARTED 审计再执行，终态写成功/失败；审计写入失败上抛 `RECORDING_FAILURE` 并暂停工作区；安全瞬态错误按策略有限重试 | `C/tool/ToolExecutor.java:35`、`:312` | 宪章 V |
| 三、配角二 ToolExecutor | `tool_invocations` 含 `success` / `error_message` 列 | `status`（STARTED/SUCCESS/ERROR/DENIED/NOT_EXECUTED/UNKNOWN）+ `error_code` + 副作用状态与纠正链；不落原始错误文本 | `oryxos-storage/src/main/resources/db/migration/V004__call_audits.sql` | 宪章 IV 脱敏；spec 005 保留偏差第 5 条 |
| 三、配角二 ToolExecutor | `ToolInvocation` 实体 + `ToolInvocationRepository` | 实体 `ToolInvocationEntity`，由审计落库组件 `JpaAuditSink` 写入，无独立 Repository 接口（与 `llm_calls` 同一口径） | `S/audit/ToolInvocationEntity.java`、`S/audit/JpaAuditSink.java:86` | spec 005 课件对照（`LlmCallRepositoryTest` 行） |
| 三、编排者 AgentService | `String process(Session, String)`；结束后 `sessionManager.save(session)` | `Execution.Snapshot process(ProcessRequest)`；会话在入口按 channel+user+Agent 身份获取并校验；消息逐条即时落库，结束时只确认轮次完成 | `C/agent/AgentService.java:99` | spec 001 会话身份决定 |
| 三、编排者 AgentService | `ProfileContext.set(profile)` / `finally { ProfileContext.clear(); }` | **一致（spec 006 新增）**：`set` 是循环所在 `try` 的第一句，`clear` 是对应 `finally` 的第一句，异步与内联执行路径都覆盖，且清理先于完成通知 | `C/agent/ProfileContext.java`；`C/agent/AgentService.java:236`、`:284` | 技术方案 §4；spec 006 FR-001 ~ FR-002 |
| 三、编排者 AgentService | 工具“从 `ProfileContext` 读” | 现有工具与记忆后端经 `OryxTool.execute(args, ExecutionContext)` 的 `ctx.agent()` 取 Agent；`ProfileContext` 是并存的补充读取通道，二者同一实例 | `C/tool/OryxTool.java` | spec 006 FR-003 |
| 三、`Profile` / `profileRegistry.get(...)` | `Profile`、`ProfileRegistry` | `AgentProfile`、`AgentCatalog`，唯一定义源是 `agents/<name>/AGENT.md` frontmatter | `C/agent/AgentProfile.java`、`C/agent/AgentCatalog.java` | spec 001 FR-002 |
| 三、供给者 ContextLoader | 按 Profile 的 `bootstrap` 和 `skills` 读文件 | 只有 `bootstrap`；不存在共享技能索引，子指令经 `read_file` 按需读取 | `C/context/ContextLoader.java:11` | 技术方案 §11 |
| 三、供给者 ContextLoader | Bootstrap 缺失至少 WARN | 显式引用的 Bootstrap 缺失、超 1 MiB、越界或指向密钥文件即 `CONTEXT_UNAVAILABLE`，在任何模型调用前失败 | `C/context/ContextLoader.java:62` | 更严格，防“人格悄悄丢了” |

## 二、第四部分测试类名对照

| 课件测试类 | 现行测试类（`us2-loop`） | 说明 |
| --- | --- | --- |
| `ReActLoopTest` | `CT/react/ReActLoopTest` | 课件 `模型一直要调工具_转满最大轮数强制停` → `modelAlwaysRequestingToolsStopsAtMaxIterations`（`@DisplayName` 保留原名）；断言 10 次模型调用，文案按现行“达到迭代上限”，另断言只执行 9 次工具 |
| `PromptBuilderTest` | `CT/context/PromptBuilderTest` | 顺序、日期时间位置、历史截断 |
| `ToolExecutorTest` | `CT/tool/ToolExecutorTest` | 成功/失败/抛异常/审计写失败；失败策略整体（重试、纠正链）另见 `CT/react/FailurePolicyContractTest` |
| `AgentServiceTest` | `CT/agent/AgentServiceTest` | 课件 `处理中抛异常_ProfileContext也必须被清掉` → `profileContextIsClearedWhenProcessingThrows`；现行 `process` 把意外异常收敛为 `FAILED`/`EXECUTION_FAILED` 执行记录而非向调用方抛出，测试断言该记录与 `ProfileContext.current()` 为空；core 无 Mockito，用可控假 Provider 抛 `boom` 代替 `when(reActLoop.run(...)).thenThrow(...)` |
| `ContextLoaderTest` | `CT/context/ContextLoaderTest` | 热读取、Bootstrap 缺失/超限/密钥链接失败 |

## 三、课件中可直接沿用的部分

“ReAct 是什么”“循环只做调度、职责拆出去”“为什么自己写循环”“三个坑（死循环、上下文撑爆、不累积就无法审计）”
“先别做并行工具、Agent 委托、流式输出、上下文压缩”以及 `ProfileContext` 的动机说明与现行实现一致，不需要改。
