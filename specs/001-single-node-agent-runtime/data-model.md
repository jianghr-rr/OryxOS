# 数据模型与生命周期

设计依据：[spec](spec.md) FR-002、008–018、034–040。以下为目标模型，不是已创建的数据库表。
默认每个工作区一个 SQLite 文件；表内 workspace_id 保留归属校验，但不构成多租户授权。

## 归属与唯一性

| 对象 | 权威来源 | 归属／唯一性 |
| --- | --- | --- |
| Workspace | 工作区根目录及 workspace.json；runtime.yaml中的timezone | UUID 初始化后稳定；根路径转绝对规范路径；统一业务时区默认Asia/Shanghai |
| AgentDefinition | agents/<name>/AGENT.md | name 在工作区唯一且与目录名相等；Profile 是其配置区派生快照 |
| ProviderDefinition | runtime.yaml | provider name 唯一；Agent引用名称；凭证只保留secret引用 |
| ToolDefinition | 内置注册／原生扩展／MCP发现 | 工具全名唯一；MCP名称带server前缀；Agent只选择子集 |
| NotificationTarget | Agent frontmatter 的 notify_channels | (agent_name,target_name)唯一；不建可编辑DB注册表 |
| Session | sessions | UUID唯一；绑定channel、user_id、agent_name及创建时provider_name/model；三元组不是唯一键，允许多个会话 |
| LongTermMemory | 所选backend | 同工作区共享；记录source Agent用于追踪，不用于隔离或过滤 |
| ScheduleDefinition | Agent frontmatter | (agent_name,local_schedule_id)稳定；对外id为 agent:localId |
| ScheduleState | scheduled_tasks | 同schedule id的运行启停与历史；不能取代文件中的cron/message/timezone |
| Execution | executions | 每次人工／定时触发独立UUID；关联一个Session及可选schedule id |

HTTP管理页调用同一个HTTP入口，channel=http；CLI为cli；自动定时为scheduler。
页面是HTTP客户端，不新建另一套执行引擎。user_id由受信任调用者提供，不能当作认证证明。
HTTP以明确Session ID续聊；CLI新chat默认新会话，可用 --session 指定；一次性invoke与每次定时触发
创建独立Session。人工立即执行某定时任务仍是http/cli身份，不借用scheduler的系统身份。

T090的`runtime/cli-view.json`仅是当前进程实际注册表与Agent版本摘要的脱敏诊断投影，绑定工作区UUID、PID及进程启动时刻。CLI同时校验进程锁和存活身份；启动前清理、退出时删除，不作为数据库表、配置来源或实时健康证据。配置仍由上述文件权威决定。

删除Agent文件不删除旧会话、执行、审计或记忆；旧记录保留agent_name快照。
Agent重新使用同名定义也不会自动续接旧会话；必须明确指定可用Session。
本阶段不增加Agent数据级联删除或记忆自动迁移。

## 核心逻辑实体

### Agent 配置与运行快照

AgentProfile：name、description、providerName、model、allowedToolNames、mcpServers、
bootstrapPaths、notifyTargets、schedules、maxIterations、maxHistoryTurns、configRevision。
configRevision 为规范化配置区哈希；正文不在该哈希内，另记 bodyRevision。

启动解析后配置快照不随正文热读改变；每轮仅重新读取正文和Bootstrap。
同名Provider冲突使全局配置无效；单个Agent解析／引用失败仅将其标为UNAVAILABLE。
单个Provider的地址、选项或凭证错误只使该Provider及引用它的Agent不可用；
不能因为同一个runtime.yaml中另一Provider缺凭证就关闭所有有效Agent。
配置只读查询输出脱敏DTO，不输出secret值、Webhook敏感URL、子进程env秘密。

### Message 与协议配对

Session的providerName/model在创建事务中写入，不允许续聊时改绑；
公开Session DTO直接读取该快照，不能用当前Agent配置补齐。空会话同样必须保留这两个字段。
SessionManager拥有会话生命周期和完整turn选择；MemoryService通过SessionHistoryPort读取历史，
与LongTermMemoryStore的CORE/ARCHIVAL组成一次ContextMemory，不重复注入历史。

Message：id、sessionId、executionId、turnId、seq、role、content、toolCalls、toolCallId、
toolName、toolOutcome、providerName、providerOpaque、createdAt。
role取USER、ASSISTANT、TOOL；toolCalls保留每个id/name/argumentsJson。
工具调用另保存原始wireName；executions保存tool_name_map_json快照，
授权/公开结果用内部全名，模型历史回放用原始wireName，避免MCP点号名称违反模型协议。
providerOpaque是供应商限定的协议续传资料（如reasoning_content），只供相同Provider重放，
不可进入公开历史DTO、普通日志、审计摘要或长期记忆。

每个用户任务构成一个turn。最近20轮选择完整turn，不按任意20条消息裁剪；
assistant提出的每个调用必须有对应TOOL结果，拒绝、未执行、超时也有明确结果状态。
尚未返回的调用不能被混入下一次模型请求。超时后的迟到真实结果更新该步骤的独立审计，
历史可保留当时“结果不确定”的记录并追加修订信息，不伪装当时已经知道成功。

### Execution 与结果表达

Execution：id、sessionId、agentName、channel、userId、triggerType、scheduleId、
configRevision、startedAt、deadlineAt、finishedAt、status、stopReason、
recordingStatus、inFlightCount、finalContent、errorCode、hasToolErrors、hasUnresolvedToolErrors、timezone。
deadline计算使用单调时钟；deadlineAt用于展示。manual=60秒，自动定时默认也采用60秒
执行预算并允许仅在文件中显式设置更长budget，不能改变人工调用的60秒规则。

| 状态 | 含义 |
| --- | --- |
| RUNNING | 正在处理，可在许可内发起下一动作 |
| SUCCEEDED | 产生最终回复、请求成功或错误已由引擎核实纠正，无未知／未执行请求；不以文本替代外部接收证据 |
| PARTIAL | 存在未解决的工具错误、未知副作用或未执行步骤；可由引擎直接返回核查说明，无需再请求模型 |
| FAILED | 致命Provider／上下文错误、迭代上限等导致无法正常完成 |
| TIMED_OUT | 截止已到，不再发起新动作；在途结果可能稍后补记 |
| PARTIAL_RECORDING_FAILURE | 动作已发生而会话／审计保存失败；显示部分完成及记录异常 |
| INTERRUPTED | 重启发现未完成执行；不自动重放，进入人工核对流程 |

同一个工具请求内部的安全重试最终成功时，该请求归集为成功；每次失败尝试仍留审计。
独立工具请求的参数纠正按[runtime契约](contracts/runtime.md)建立可验证关联；
tool invocation保留原失败结果，并增加resolutionStatus、correctionOfCallId、resolvedByCallId。
所有错误已解决时可SUCCEEDED，但hasToolErrors=true，显示已纠正错误的提示；
仍有未解决错误则hasUnresolvedToolErrors=true并为PARTIAL，不能仅凭模型声称完成改为成功。
执行状态与自然语言目标是否满足分开，不承诺自动判定业务目标。
有副作用动作结果UNKNOWN立即终止本次执行，stopReason=SIDE_EFFECT_UNCERTAIN，
补记同轮剩余请求NOT_EXECUTED；未同时超时／记录故障时为PARTIAL。

recordingStatus取COMPLETE、INCOMPLETE、UNKNOWN；只有所有必须记录提交成功才为COMPLETE。
已返回的HTTP 504不被迟到响应改成200；查询可显示后续已知动作结果。
若超时后再发现保存故障，保留stopReason=DEADLINE并显示记录异常，不能丢失任一事实。

### 记忆

MemoryEntry：id、workspaceId、scope(CORE/ARCHIVAL)、rawContent、createdAt、
sourceExecutionId、sourceAgentName、backend、writeStatus、remoteIds。
默认scope=ARCHIVAL。CORE始终原文完整；不以字节预算静默删CORE。
归档注入最多4000 Unicode code points，不切断代理对；超限仅影响注入，不删已保存内容。
关键词检索为Unicode NFC规范化、大小写折叠后的字面子串，空关键词拒绝；
sqlite使用转义LIKE参数或等价匹配，不能让用户%/_变成隐式通配符。

md模式：memory/MEMORY.md，保留CORE/ARCHIVAL区；条目id/日期可用Markdown元数据。
工作区级锁、同目录临时文件、fsync后原子替换；发现损坏格式返回错误，不当作空记忆重建。
sqlite模式：memory_entries按scope读取，每次新查询、不缓存；CORE无LIMIT。
mem0模式：本地来源账本保留CORE与归档原始输入，远端只承担ARCHIVAL投影。
writeStatus=PREPARED/CONFIRMED/UNKNOWN/FAILED；两种scope按各自的提交条件确认成功。
CORE只需本地原文事务提交，不依赖Mem0在线或remote ID；远端回读只针对ARCHIVAL。
ARCHIVAL确认要求有返回ID、逐一回读成功、归属正确且本地源原文/映射提交；
写入收据分别报告sourceStored与projectionState，语义覆盖度标为NOT_PROVEN。
Mem0没有预期事实总数，无法从ID数量证明全部事实都已索引；本地原文保全事实，
固定语料与人工标注验证增强投影，不伪装为任意文本的完整性证明。
失败或未知的源记录不作为已确认事实注入；人工核对用的读取不自动重发原写请求。

## 目标持久化结构

全部主键由应用产生UUID字符串；时间统一UTC ISO-8601，持续时间另用整数毫秒。
可查询字段单列，结构化消息／必要摘要用JSON TEXT；不把Java序列化对象存入库。
以下仅定义字段与约束，SQL迁移在实施阶段生成。

| 表 | 主要字段 | 约束／索引 |
| --- | --- | --- |
| schema_migrations | version,checksum,applied_at | version PK；校验和不符停止执行 |
| workspace_state | workspace_id,gate_state,incident_id,updated_at | workspace_id PK；不保存Provider等权威配置 |
| sessions | id,workspace_id,channel,user_id,agent_name,provider_name,model,kind,status,created_at,archived_at,version | provider_name/model创建时非空且不可变；身份组合查询索引；kind=CONVERSATION/ONE_SHOT；status=ACTIVE/ARCHIVED |
| messages | id,session_id,execution_id,turn_id,seq,role,content_json,provider_name,provider_opaque_json,created_at | UNIQUE(session_id,seq)；外键到session/execution；完整工具配对校验 |
| executions | 上述Execution字段 | session_id和schedule_id索引；id PK；终态查询与迟到动作分离 |
| llm_calls | id,execution_id,iteration,attempt,provider,model,status,started_at,finished_at,duration_ms,input_tokens,output_tokens,error_code | UNIQUE(execution_id,iteration,attempt)；未知token为NULL；不存秘密或opaque正文 |
| tool_invocations | id,execution_id,tool_call_id,tool_name,attempt,status,args_redacted,result_redacted,side_effect_state,resolution_status,correction_of_call_id,resolved_by_call_id,started_at,finished_at,duration_ms,error_code | UNIQUE(execution_id,tool_call_id,attempt)；纠正仅同execution关联；拒绝也有记录，旧失败不改写为成功 |
| scheduled_tasks | id,agent_name,definition_hash,enabled,configured_present,last_execution_id,updated_at | id PK；文件定义缺失则不调度，状态保留；不从本表恢复旧cron |
| task_executions | id,schedule_id,execution_id,trigger_kind,scheduled_for,status,reason,created_at | 包括SKIPPED_OVERLAP、SKIPPED_PAUSED；execution_id可空 |
| memory_entries | MemoryEntry的本地字段，raw_content,write_status | (workspace_id,backend,scope,created_at,id)索引；Agent不是隔离键 |
| memory_remote_links | source_entry_id,remote_id,workspace_id,verified_at | 复合PK；只接受当前工作区已确认映射 |
| incidents | id,workspace_id,run_id,detected_at,reason,affected_execution_ids,missing_record_summary,state,acknowledged_by,acknowledged_at,recovery_notes | OPEN/ACKNOWLEDGED；说明缺口而不伪造原始审计 |

RuntimeState嵌入currentIncident，提供受影响executionIds、已知动作、未知动作、缺口说明、
evidenceSources和detailsComplete。knownActions仅表示有证据的动作结果，不等于已成功；
没有可恢复的ID／详情时允许集合为空，但detailsComplete=false且必须说明缺失范围。
诊断信息可来自当前内存、既有fault标记、运行标记和仍可读的数据库，不能为凑字段编造信息。
故障ID在发现时由应用生成，即使数据库不可写也可供本次诊断及人工恢复引用；
重启优先使用已有标记中的ID，仅有旧run标记时建立重建故障并注明来源，不伪造旧故障详情。

Agent、Provider、Webhook、工具白名单没有可编辑数据库配置表。
数据库中的配置哈希和历史快照仅用于定位当次执行，不能覆盖文件定义。

## 状态转换

~~~mermaid
stateDiagram-v2
  [*] --> RUNNING
  RUNNING --> SUCCEEDED: 最终回复且请求成功或错误已核实纠正
  RUNNING --> PARTIAL: 未解决工具错误或未知副作用直接终止
  RUNNING --> FAILED: 致命错误或迭代上限
  RUNNING --> TIMED_OUT: 到达截止
  RUNNING --> PARTIAL_RECORDING_FAILURE: 已有动作且必需记录保存失败
  RUNNING --> INTERRUPTED: 未完成即进程退出
  TIMED_OUT --> PARTIAL_RECORDING_FAILURE: 迟到结果的保存失败
~~~

终态不回RUNNING；迟到动作更新其attempt信息，不启动新步骤。
Session归档只允许无活跃本地执行时发生；忙碌返回409。归档后继续发消息返回409，不能隐式新建。
ONE_SHOT会话在本地执行及迟到收尾结束后归档，记录仍可查询；不用于下一次invoke。

工作区闸门：STARTING → READY 或 PAUSED_RECORDING；READY在记录故障时立即转PAUSED_RECORDING。
只有检查可写并完成人工确认的恢复操作能转回READY。存储恢复、重启、模型响应返回均不能解除暂停。

## 事务与故障恢复顺序

1. 运行进程先取得runtime/process.lock，读取并保留旧运行标记、fault文件和未闭合尝试；
   先判定上次是否完整关闭，不得先覆盖旧证据。
2. 初次启动无历史标记可正常运行；旧标记未干净关闭、STARTED尝试未闭合、数据库无法检查时暂停。
   在任何新动作前为本次runId独占创建runtime/runs/<runId>.json并落盘；创建失败仍暂停。
   每次运行使用独立文件，未核对的旧标记保持原样；人工恢复确认后才按恢复记录清理旧证据。
3. 接收任务用短事务写execution及USER消息。失败即拒绝，无模型／工具副作用。
4. 每次模型／工具调用先短事务写STARTED；提交失败不发起动作。允许后执行IO，随后短事务写结果及配对消息。
5. 任一必需记录提交失败：先在内存关闸门，尝试写独立fault.json与可用日志，返回明确异常。
   即使新故障文件写不出，先前运行标记／STARTED仍在；不能仅依赖失败后写一个数据库标志。
6. 同一时间只有一个写协调器提交；队列有界256，等待受剩余deadline约束。
   不把事务跨线程持有，不在事务里等待网络；ORM实体不可跨虚拟线程共享。
   T101续轮实现：同一写线程可把已排队的最多128条记录放入一个短事务，不等待凑批。
   ArrayBlockingQueue容量256、总未完成记录容量257；提交和资源关闭后才确认，组内失败整组回滚并先关闸。
   高频记录通过JPA所属连接的参数化SQL写入，仍执行外键与身份/终态校验；每条操作刷新并清空持久化上下文，实体不跨线程。
   后续T101定位允许事务执行期间接纳已排队记录，仍受128上限与预算约束；不添加收集等待。
   SQL语句仅在该事务/连接/写线程内复用（最多32条），每次清除旧参数后重新绑定，事务结束全部关闭。
   不把成功确认放到提交之前，已接纳事务在调用方截止或中断后仍须完成收尾。
7. 恢复接口检查无未收尾的本地调用、存储可读写，展示已知动作与缺口；
   维护者确认后写incident确认与恢复记录，再清理故障标志并开放新动作。
8. 正常停止需等待有界收尾、记录全部完成才清理本次运行标记；异常退出保持标记。
   重启扫描遗留尝试标UNKNOWN/INTERRUPTED供核对，不自动执行原动作。

StorageLifecycle在boot装配时以受控方式创建storage资源；失败提供UnavailableStore端口，
使诊断与恢复HTTP仍可启动。JPA工厂／repositories不以自动扫描方式让整个管理服务因数据库错误退出；
由storage工厂在迁移成功后创建，恢复时在关闭闸门下重建并原子替换端口。
只读命令使用SQLite只读连接，不自动迁移、不初始化模型、不改变运行状态。
这是F0/US5必须验证的集成点，不能等到上线才测试。

## 迁移、并发与保留规则

- migrations目录包含按版本命名的SQL；启动持有工作区锁后核对checksum、按事务执行并记录版本。
- 迁移失败保持暂停；不执行隐式自动ddl，不靠删库恢复，不自动迁移三种记忆后端。
- 同Session使用非排队执行锁；同schedule使用独立防重叠锁，手动与自动触发共享该锁。
- 成功写入才回复保存成功；未知结果不重复提交。并发共享记忆写入不得相互覆盖。
- 不自动过期、清理或删除会话、审计、记忆；查询分页而不是丢弃旧记录。
- SQLite WAL仅用于本地磁盘。增强服务独立持久卷与备份另列操作前提；断连不伪装空记忆。



T101续轮新增V007会话历史索引：messages(session_id,turn_id,seq)及USER消息的序号索引。
序号在每个既有会话租约的首次写入前从持久化MAX读取；新建会话的原子begin从1开始。
同一排他租约只在写入确认后推进序号，后续租约和重启重新查库；不缓存历史或执行记录。
运行上下文只查询最近N个完整回合及当前回合，未完成的旧回合不混入提示。
完整历史保留供查询和审计；V001–V006 SQL及其checksum不变。


T101读取与恢复续轮：find/lastSequence/turn/history使用同一有界池的参数化SQL投影，连接归还后不保留记录缓存；MAX走会话/序号索引。HTTP发送消息前的元数据查询也复用该池，租约仍独立检查身份/模型/归档状态。单个物理写drain由独立平台线程执行，容量、128组上限、FULL/WAL、提交后确认与故障回滚规则不变。Markdown共享记忆的读操作打开旧或新完整文件快照，无需持有工作区监视器做IO；多实例写入仍共用原锁、原子替换并同步落盘。相应行为测试通过不构成50ms验收；[本轮结果](evidence/US5-ninth-batch-read-wakeup-review.md)仍失败。


T101模型传输与组内等待续轮（2026-11-16）：新增可选内容无关计时，记录请求体编码、响应头前等待、响应体读取/消费与关闭，以及写事务组中本记录动作前后等待。计时不改变任何持久化字段、提交顺序、FULL/WAL、整组回滚或提交后确认；组内其他记录的动作耗时仍计入调用方。模型HTTP请求体使用Provider所属虚拟线程执行器，关闭Provider时释放；底层JDK连接、截止与无自动重试语义不变。[本轮证据](evidence/US5-ninth-batch-transport-batch-review.md)仍未通过50ms验收。
