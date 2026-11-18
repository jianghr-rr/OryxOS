# 运行时端口与执行契约

本文是接口设计，不是Java实现。core中的接口只使用项目类型／JDK类型；
Spring AI DTO、MCP DTO、JPA实体分别留在实现模块。依据[规格](../spec.md)。

## 能力与责任

| 端口／组件 | 输入 → 输出 | 负责人／不负责什么 |
| --- | --- | --- |
| WorkspaceResolver | root选择 → WorkspaceHandle | core；所有入口同一定位，不随数据库实现改变 |
| AgentCatalog / AgentDefinitionReader | name／目录 → AgentProfile或配置错误 | core；配置启动快照，正文按次读 |
| AgentService | ProcessRequest → ExecutionResult | core；身份、锁、截止、上下文与结果编排 |
| ProviderPort | ProviderRequest → ProviderReply | provider实现；一次HTTP，不执行工具、不隐式重试 |
| ReActLoop | ExecutionContext → FinalReply或终止原因 | core；最多10轮、顺序工具、不操作具体数据库 |
| ToolRegistry | name → ToolDescriptor/OryxTool | tool装配；未知工具不能进入IO |
| ToolExecutor | ToolCall + ExecutionContext → ToolResult | core流程；调用tool提供的校验与适配器；唯一工具执行入口 |
| ToolPolicy / ArgumentValidator | agent、tool、args、target → Permit或拒绝 | tool；参数schema、权限、路径／命令／网络边界 |
| OryxTool | ValidatedArguments + ToolContext → ToolResult | tool/memory原生与MCP适配实现；受统一执行入口约束 |
| MemoryPort / MemoryService | session/turn及预算 → ContextMemory；scope/content或query → MemoryView/WriteReceipt | memory统一门面；委托会话历史端口与长期记忆后端，不自行管理执行锁 |
| SessionManager / SessionHistoryPort | 创建、身份校验、完整turn读取、追加、归档 → Session/History | core；会话生命周期与历史选择的唯一负责人，委托SessionStore持久化 |
| LongTermMemoryStore | load/save/recall → 长期记忆视图或收据 | memory后端；md/sqlite/mem0差异，不读取整段会话或决定会话归属 |
| SessionStore / ExecutionStore / AuditSink | 项目实体／查询 → 已提交结果 | storage；短事务、迁移、审计持久化 |
| RuntimeGate / Deadline | 动作发起请求 → Permit或停止原因 | core；每次新动作及重试前检查，不以线程中断替代 |
| ScheduleCoordinator | ScheduleDefinition + trigger → ExecutionResult/Skip | core；同task互斥；Spring触发适配在boot |
| RuntimeLauncher | LaunchMode + WorkspaceHandle → runtime handle | boot实现；cli只引用端口，不能依赖boot |
| QueryPort / RecoveryPort | 查询／人工恢复请求 → DTO | core契约；storage/boot实现；失败不返回假空数据 |

ExecutionContext显式携带workspaceId、agent快照、sessionId、executionId、identity、
deadline、RuntimeGate和脱敏策略。工具不靠静态变量读取“当前Agent”；
优先显式传递，若某适配必须使用ThreadLocal，其设置与finally清理只能位于统一边界。

MemoryPort及SessionHistoryPort在core定义；memory实现MemoryService，通过注入的
SessionHistoryPort访问core的SessionManager，再由后者调用storage实现的SessionStore。
AgentService负责执行编排并调用SessionManager办理会话生命周期；每轮仅向MemoryService
请求一次ContextMemory，其中包含近期已完成turn、本次turn、完整CORE及有界ARCHIVAL。
SessionManager返回的近期turn必须排除本次turn；PromptBuilder直接消费该视图，
不得再次查询或追加同一份历史。长期记忆后端只接收显式save内容，不接收ContextMemory。
US1–2可注入空长期记忆后端和临时SessionStore，仍沿用同一接口，不把临时路径留作生产降级。

## 消息与工具结果

ProviderRequest包含：providerName、model、systemPrompt、orderedMessages、tools schema、工具名映射快照、
remainingDuration、executionId、iteration。providerOptions由ProviderRegistry从已校验的runtime.yaml快照解析；
它不作为可被任务输入覆盖的端口参数。systemPrompt单列，持久消息role仍只取USER/ASSISTANT/TOOL。ProviderReply包含：
assistantMessage、toolCalls、finishReason、usage(nullable)、actualProvider、actualModel。

Provider实现保留tool_calls以及providerOpaque；只在同provider/model组合中回传。
配置变更重启后若旧会话Provider/model不同，续聊返回SESSION_MODEL_CHANGED，
要求新建会话，不把旧供应商协议字段发给新供应商。
Session在创建时保存配置中的providerName/model；即使从未发过消息，重启后也从该快照校验。
实际模型响应报告的版本名另存llm_calls，不用当前Agent配置或最后一次审计反推创建值。

内部工具全名用于授权和审计，发送模型前必须映射为合法wireName：
符合[A-Za-z_][A-Za-z0-9_-]{0,63}的内置名可原样使用；其余名字使用t_加内部全名UTF-8的
SHA-256前48位十六进制。启动检查映射冲突并拒绝冲突定义，不静默覆盖。
每次执行保存工具名映射快照；历史同时保留internalName与原始wireName。
Provider解析返回调用时还原内部名，回放给模型时使用原始wireName和tool_call_id。
未知wireName成为未知工具错误，不能猜测映射。实现保留原call id/wireName/参数，
内部name为空且nameErrorCode=UNKNOWN_TOOL，供引擎回填配对错误；它不构成执行授权。MCP的点号名称不得直接发送为模型函数名。
MCP描述符分别保存serverId、remoteName、internalName和wireName；remoteName必须保留
服务器发现时的原始工具名。调用MCP callTool时传remoteName，不能传内部全名或模型用的哈希名。

ToolResult字段：
- outcome：SUCCESS / ERROR / DENIED / UNKNOWN / NOT_EXECUTED；
- content：对模型安全的真实结果；errorCode、safeMessage；
- retryClass：NONE / SAFE_TRANSIENT；sideEffectState：NONE / CONFIRMED / UNKNOWN；
- callId、attemptCount、durationMs；内部审计与公开DTO均通过脱敏投影。
- resolutionStatus：NOT_APPLICABLE / UNRESOLVED / RESOLVED；
  correctionOfCallId、resolvedByCallId为引擎核实后的关联，不接受模型直接宣告错误已解决。

无错误的尝试使用NOT_APPLICABLE；失败尝试初始为UNRESOLVED。同请求安全重试成功时，
其失败尝试附加RESOLVED并以本callId关联最终成功；独立参数修正则关联后续成功callId。
原outcome、错误原因和尝试次数不改写，解决状态只是新增的核查信息。

通知200但业务ack失败属于ERROR；连接中断且目标是否接收无法确定属于UNKNOWN。
模型自由文本不能覆盖ToolResult。未知工具、参数不合法、权限拒绝均产生配对结果，
可由下一轮模型修正；不把拒绝当作执行成功。

有副作用的动作返回sideEffectState=UNKNOWN时，立即关闭本次执行的新动作许可，
设置stopReason=SIDE_EFFECT_UNCERTAIN；不再调用模型、其他工具或重试。
同轮剩余请求补记NOT_EXECUTED，返回确定性说明“结果不确定，请先核查”和执行ID。
记录正常时终态为PARTIAL；若同时截止则保留TIMED_OUT，记录故障则按记录故障处理。
该执行不会因模型换call id、换说法或要求“再试一次”而重新开放；不自动重放未知动作。
其他执行不因此全局暂停，只有记录故障按工作区规则关闸。

错误被纠正后的成功采用保守且可核查的规则：安全重试仍归集为同一工具请求；
独立请求仅在原错误为INVALID_ARGUMENTS、参数是可解析对象且sideEffectState=NONE时，
才可开启参数修正窗口。校验器记录出错字段路径及其余有效参数；下一轮须只有一个
同名工具调用，且只修正这些字段、其余有效参数不变，再通过完整参数及权限校验。
该链最终实际成功后，由引擎记录纠正关联，把链内错误标为RESOLVED；其历史结果仍为ERROR。
若继续参数修正，仍受原字段范围和迭代上限约束。多项待纠正、插入其他调用、改变原有效目标、
坏JSON、未知工具、权限拒绝、外部执行错误或无法建立上述关联，均不自动认定已解决。
这些情况可以继续正常处理，但未解决错误保留为UNRESOLVED，最终状态为PARTIAL。
只有最终回复产生且所有错误已核实解决、无未知或未执行请求时才可SUCCEEDED；
hasToolErrors仍为true，页面显示“成功，过程中有已纠正的错误”。这不证明自然语言目标自动验收通过。
校验失败结果以脱敏字段路径和原因说明问题；不得把原始秘密值拼入纠正提示或公开历史。

## 一次任务的准确顺序

1. 入口解析用户标识、Agent、内容、Session。HTTP固定channel=http，CLI=cli，自动定时=scheduler。
   空内容、UTF-8正文超过32768字节、未知资源在发模型前拒绝。
2. RuntimeGate检查READY；SessionManager校验身份及模型快照，锁定指定Session或创建新Session；同会话忙碌不排队。
   手动立即执行定时任务还需取得相同schedule锁。
3. 创建executionId/deadline。持久阶段先写execution和USER消息；预写失败则无外部动作。
4. 每轮重读当前正文／Bootstrap；通过MemoryService一次取得完整最近20轮、本轮消息和共享记忆。
   附当前日期时间及工作区时区，历史仅注入一次，提供Agent可用工具schema。
5. 检查截止，写模型STARTED，再由Provider发送一次请求；记录真实provider/model、用量和耗时。
6. 保存assistant的结构化tool_calls与opaque；没有工具请求时产生最终回复。
7. 对同轮工具按顺序：解析JSON → 离线schema校验 → 工具集合／目标授权 →
   截止与工作区检查 → 写STARTED → 执行 → 提交结果与配对消息。
   被拒绝的请求仅写拒绝记录，不发实际IO。
8. 安全可重复的暂时性工具失败最多追加3次尝试，退避250/500/1000ms；
   每次前重查截止。无效参数、401/403、禁止目标和未知副作用不自动重试。
   Provider模型调用本阶段不自动重试，逐次失败直接交给引擎结束并反馈。
9. 先检查未知副作用终止规则；仅在执行仍开放时根据工具结果开始下一轮。
   迭代上限10时停止新动作，补齐未执行工具结果；参数修正也占用迭代预算。
10. 保存终态／最终消息后回复执行ID、内容、动作状态及recordingStatus。finally释放锁；
    本地在途调用尚未退出时，会话仍视为忙碌，不能用HTTP已超时代替执行资源已释放。

US1–4演示可注入日志AuditSink和临时SessionStore；这些替身必须显式标记。
US5生产装配只接受持久实现，不能在数据库不可用时悄悄降级到内存继续执行。

## 截止与故障闸门

HTTP采用MVC DeferredResult返回单个结果，业务运行在受控虚拟线程中，仍是同步请求语义。
Deadline使用单调时钟，每个动作的“发起”以统一dispatcher授权并交给适配器为边界。
截止回调先封闭许可，再返回504；与发起动作的检查共享原子状态，防止检查后仍越过截止调度。
在途外部请求可能无法撤回；只允许收尾和记录，不允许重试、额外通知或继续模型调用。

工具内部多个外部请求（例如Mem0写后回读）也须检查同一许可，不能藏在一次工具调用里绕过截止。
无法及时确认的写入为UNKNOWN；后续维护查询可核对，不能自动重复该写入。
外部HTTP connect上限5秒、read上限剩余deadline；Shell/MCP上限见配置。
HTTP服务端不得只配置Tomcat超时而缺少业务闸门。

记录提交失败时：工作区关闸 → 标记部分完成或提交前失败 → 告警 →
暂停人工、定时及其他活跃执行的新动作。所有在途动作结果均按已知／未知记录。
恢复由RecoveryPort执行[数据模型](../data-model.md)中的顺序；诊断查询不调用模型或工具。

## 工具操作边界

| 工具 | 输入与成功结果 | 主要失败／限制 |
| --- | --- | --- |
| read_file | 相对workspace的path → UTF-8内容 | 规范路径、父链／symlink检查；最大输出64KiB，超限明确标记截断 |
| write_file | path,content → 实际写入确认 | 仅允许目录；原子替换；不允许改控制文件或秘密文件 |
| list_dir | path → 直接子项列表 | 同文件边界；有界返回1000项，标明是否截断 |
| shell | command,args[],workingDirectory → exitCode/stdout/stderr | ProcessBuilder不经shell解释；命令与脚本路径校验；超时终止并核对进程退出 |
| http_get | url → status/body | scheme/host/port规则；重定向逐跳检查，最多3跳 |
| http_post | url,jsonBody → status/body | 同HTTP规则；默认非幂等，未知结果不自动重试 |
| save_memory | content,scope默认ARCHIVAL → WriteReceipt | 写后可见才成功；增强空结果、缺ID或回读不全不能报告保存成功 |
| recall_memory | query → 已保存归档事实集合 | 基础字面匹配；增强语义；不查CORE，不生成新事实 |
| notify | content,targetName → 接收确认 | 必须当前Agent已声明目标且网络获准；检查传输与业务ack |

受控文件工具禁止访问runtime.yaml中的秘密、secrets.local.yaml、数据库/WAL、
runtime控制文件和Agent配置区；普通读写允许范围以references/output等显式路径声明。
正文／Bootstrap由ContextLoader读取，子指令通过允许路径读取。禁止write_file修改授权配置，
防止模型用普通文件工具给自己加权限。受信任脚本仍可能绕过应用级文件／网络规则，
该限制如实展示，不能宣传成操作系统强隔离。

原生扩展以Spring AI注解生成schema，实际方法调用包装为OryxTool并经过统一Executor。
扩展不是在Controller直接调用任意Bean。MCP发现的schema先作离线校验；
拒绝远程$ref，限定JSON深度64、schema/参数各64KiB；不接受让校验器自行联网的schema。

## 调度、错误与观察

- cron采用六字段格式，timezone为有效IANA名称；文件定义在启动时注册。
- 首版统一使用工作区timezone；定时条目省略时继承，显式设置必须与工作区相同。
  手动与定时提示词均用该时区解释“今天”，不读取服务器系统默认时区作为业务时区。
- 相同schedule id无论自动或手动触发都共用锁；重叠记录SKIPPED_OVERLAP，不排队。
- 文件中的初始enabled仅用于首次创建状态；后续文件cron变化不覆盖已保存的运行启停值。
- 文件删除／无效使任务不调度；保留历史。新定义重启生效，不监听目录。
- 普通任务失败不关闭调度器；记录故障才关工作区闸门；不补跑停机／故障期间遗漏。
- MDC/trace包含workspaceId、executionId、sessionId、attemptId；不记录secret值、完整Webhook URL或opaque。
- tool统计区分请求与尝试，模型用量未知为null；耗时区分内部耗时与外部等待。
- REST状态与错误码详见[HTTP契约](http-api.json)；CLI退出码详见[CLI契约](cli-admin.md)。

