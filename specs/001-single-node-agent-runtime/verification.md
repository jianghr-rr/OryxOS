# 验证策略与需求追踪

本文件定义实施阶段必须取得的证据，不是已通过的测试报告。
已完成设计工件、任务生成及只读分析，并依据分析修订实施顺序与测试执行契约；
此前为文档工作；F0记录见evidence/F0-foundation.md；US1本地记录见evidence/US1-local.md，US2见evidence/US2-local.md与evidence/RV01-RV06-loop.md；US3本地记录见evidence/US3-local.md；真实前提分别见evidence/US1-real-models.md、evidence/US2-real-tools.md、evidence/US3-real-mem0.md。

## 分层验证与报告

| 层次 | 输入／环境 | 能证明什么 | 不能代替什么 |
| --- | --- | --- | --- |
| L0 文档与依赖 | 实际POM、固定tag、隔离Maven依赖树 | 版本可解析、公开API存在、设计契约可追踪 | 编译运行、性能和真实服务可用性 |
| L1 可控组件 | 可控时钟、固定Provider/Tool、临时文件 | 状态机、次数、消息配对、失败分支 | 外部API兼容与真实接收 |
| L2 本地整合 | 真SQLite、回环HTTP、stdio MCP、完整CLI/HTTP入口 | 序列化、迁移、并发、资源关闭、故障与重启 | 两家公网模型、真实Webhook及Mem0语义行为 |
| L3 真实联调 | DeepSeek/Kimi、测试Webhook、自托管Mem0及配置工具 | 实际模型工具循环、接收证据、增强记忆契约 | 可重复边界故障、持续性能目标 |
| L4 性能与长跑 | 10 Agents、100活跃Sessions、4小时、可控外部延迟 | 内核开销、真实SQLite审计压力、长期稳定性 | 任意供应商的延迟SLA |

测试报告逐项列：FR/SC/场景ID、工作区、版本、命令、输入、预期、实际、执行ID、
外部副作用计数、结论和未验证原因。禁止使用真实秘密截图或完整敏感请求。
本地fixture要捕获实际调用和副作用，不只mock内部方法返回值。

## 测试执行契约

以下测试执行契约已由T003落实；当前登记F0、us1-contract、us2-loop、deadline-boundary、us3-memory、us4-tools、storage-faults、restart-recovery、us5-schedules、us5-http-api、us5-cli及五个real组；所有real入口仍未完成真实验收；第六批已登记并通过us5-process、us5-published-contract、综合us5-http及us5-cli-process，另完成真实Chrome浏览器核验。第七批已登记kernel-local的KernelLocalIT 9项并通过，纳入默认local回归。

- 统一使用Surefire在Maven的test阶段执行JUnit；显式保留 `**/Test*.java`、`**/*Test.java`、`**/*Tests.java`、`**/*TestCase.java`，并加入 `**/*IT.java`。`verify`经过同一test阶段，不绑定第二个Failsafe执行，避免真实通知等用例被跑两次。
- `tools/dev/test-groups.json`按组保存模块、完整类名、必跑用例标识和local/real/soak类型。下面的表是最终必需映射；T003只登记已经加入的f0-discovery哨兵，新增测试时按表设置标签，表中指定的登记/出口任务再串行登记并补齐本组映射。尚未实施的组不能被记录为已验收。
- `tools/dev/check-test-reports.py`由个人`tools/dev/mvnw`在成功的test/verify后调用，只核对本次调用产生的Surefire XML。运行前只清理参与模块的旧`target/surefire-reports`，不能复用旧报告证明本次执行；构建本身失败时保留非零退出。
- 显式groups须存在且已登记，所选组的必跑类和用例必须出现在报告中并实际执行成功；缺失、失败、全跳过、已要求用例被跳过均不得通过。不得只检查全仓库测试总数大于零。不承担该分组的模块允许零用例，不在父POM全局强制每个模块都有测试。
- 未指定groups的普通test/verify核对当前已登记的全部local组，并排除real-integration、kernel-soak及显式soak诊断组kernel-short-gate/kernel-writer-probe/kernel-redactor-probe/kernel-reader-probe/kernel-provider-probe。测试编写中的预期失败不能作为阶段验收成功；完成阶段出口前，本组清单与实际用例必须齐全。
- 默认`-Doryxos.tests.mode=local`：使用临时工作区、回环替身及真实本地资源，禁止公网调用。所有真实类同时标记`real-integration`和表中专用标签；只有显式`mode=real`且选择真实分组时解除该组排除，继续排除kernel-soak。未指定真实分组不能仅靠切换mode启动全部外部联调。
- 完整四小时类标记`kernel-soak`，只有`mode=soak -Dgroups=kernel-soak`启用；短程门禁及最小探针使用各自显式soak组，始终排除real-integration。模式与分组不匹配、未知组或未登记组在执行前报错；不以用户未配置凭证为由把真实用例skip后输出成功。
- 真实模型凭证、Mem0服务、可信MCP、测试通知目标等前提缺失时，命令以未验证/前提未满足结束并非零退出，相应任务保持未勾选。通知目标仍须由维护者明确选择；模式开关本身不代替目标配置。

| 分组标签 | 必跑测试类（类名简写；清单使用所属模块与完整类名） | 登记／出口任务 |
| --- | --- | --- |
| f0-discovery | EntryPointContractTest、TestDiscoveryIT、DiscoverySuffixIT | T003、T006 |
| f0-foundation | OryxOsApplicationTest、RuntimeConfigContractTest、WorkspaceCommandsTest、StorageLifecycleIntegrationTest、DegradedStartupTest | T018 |
| us1-contract | ProviderContractTest、ProviderSmokeTest、AgentCatalogContractTest、AgentStatusTest、StructuredLogAuditTest | T019、T026 |
| us2-loop | ReActContractTest、FailurePolicyContractTest、SessionRuntimeContractTest、ToolSafetyContractTest、EarlyCliLoopIntegrationTest、DeadlineRaceTest | T041、T043 |
| deadline-boundary | DeadlineRaceTest、FailurePolicyContractTest | T043 |
| us3-memory | MemoryContractTest、SessionContractTest、Mem0ProtocolContractTest、MemoryFacadeIntegrationTest | T045、T056 |
| us4-tools | ToolsContractTest、NotifyContractTest、McpContractTest、ToolIntegrationTest、AgentCatalogContractTest（US4方法） | T059、T069 |
| us5-cli | CliOperationsTest、CliRuntimeContractTest | T073本批子集、T090 |
| us5-admin | AdminPageContractTest（本地已登记）；另有实际浏览器测试页 | T091、T092本地子集，非T095整体 |
| us5-cli-process | CliCommandsIT（独立main/JAR，3项） | T095发行CLI；浏览器另取证 |
| us5-process | CrossProcessAcceptanceIT（支持独立main/JAR） | T093 |
| us5-published-contract | PublishedOpenApiTest（实际文档/操作ID/状态/约束对照与DTO派生，2项） | T094 |
| us5-http | 当前12类/71项综合清单见test-groups.json；含HTTP、调度、存储、已有CLI、页面静态服务与上述两组 | T073/T093/T094；T095另见发行CLI与浏览器证据 |
| storage-faults | RecordingFailureIT、RestartRecoveryIT | T073、T093 |
| restart-recovery | RestartRecoveryIT | T073、T093 |
| kernel-local | KernelLocalIT（9项，实际CLI/HTTP/SQLite/MCP子进程/脚本/回环服务） | T096先准备测试；T097本地出口已通过 |
| real-provider | RealProviderIT | T027 |
| real-provider-tools | RealProviderToolLoopIT | T042 |
| real-mem0 | RealMem0IT | T057 |
| real-notify | RealNotificationIT | T070 |
| real-mcp | ConfiguredMcpIT | T071 |
| kernel-soak-harness | SoakHarnessTest（5项，仅本地夹具/计时校验） | 第九批已登记并通过；不替代长跑 |
| kernel-timing | RuntimeTimingTest两项、存储分段一项 | 显式传播、观察器隔离、队列/读写/提交耗时 |
| kernel-history | 持久化历史窗口两项 | 回合/序号/opaque/重启，以及20倍历史增长下的SQLite实际查询工作量 |
| kernel-batching | 合并写事务四项 | 提交确认、整组回滚、启动前容量、启动拒绝 |
| kernel-session-concurrency | 会话接收并发一项 | 独立会话不被其他会话的存储阻塞串行化 |
| kernel-hot-writes | 终态与完成标记一项 | 防止复活终态或丢失turn_complete |
| kernel-transport | 连接与并发截止一项 | 连接复用、独立超时、无隐式重试、关闭 |
| kernel-diagnostic | SoakDiagnosticIT一项，约三分钟 | 同10/100负载及25秒外部等待，只有120秒稳态；永不算T101 |
| kernel-active-batch | 活跃事务与截止/中断两项 | 接纳期间到达的工作共用确认/回滚边界、SQL复用及已接纳事务收尾 |
| kernel-redactor | 并发秘密注册与投影一项 | 注册完成后新调用可见，长短秘密、URL和凭证保持遮蔽 |
| kernel-redactor-probe | RedactorConcurrencyTest性能方法（显式soak） | 100并发审计投影的最小诊断，不能替代T101 |
| kernel-provider-resources | 请求包装器资源一项 | 100个包装器不得创建额外HttpClient选择线程 |
| kernel-short-gate | ShortPerformanceGateIT一项（显式soak，约三分钟） | 原10/100负载、完整审计与50ms最大值；未通过则不进入四小时 |
| kernel-writer-probe | WriteCoordinatorPerformanceIT一项（显式soak） | 最小真实SQLite写入探针，永不作为T101证明 |
| kernel-lease-read | SessionReadContractTest两项 | 同租约序号、重启、投影不分配托管上下文、关闭后不伪装空记录 |
| kernel-reader-probe | SessionReadPerformanceIT一项（显式soak） | 100并发真实SQLite读、包含池等待/物化；最小诊断，不替代T101 |
| kernel-writer-isolation | WriterIsolationTest一项 | 实际子JVM证明写线程不依赖虚拟承载线程可用性 |
| kernel-http-read | HttpReadPathTest一项 | 真实HTTP查池、身份错误、404及暂停无模型调用 |
| kernel-tool-transport | HttpTransportReuseTest两项 | 连接复用、100并发、单请求取消隔离、不自动重试及关闭 |
| kernel-memory-read | MarkdownReadConcurrencyTest一项 | 慢快读取互不阻塞、完整快照与原子发布；多实例写入仍跑原回归 |
| kernel-evidence-log | EvidenceLogTest两项 | 同步逐条写入/刷新、失败可见、锁等待释放虚拟承载线程 |
| kernel-provider-timing | ProviderContractTest一项 | 真实HTTP请求体编码/响应头/响应体分段，返回与完整审计不变 |
| kernel-group-timing | PersistentStorageContractTest一项 | 真实SQLite组内前后等待仍计入，其他成员未完成时不提前确认 |
| kernel-external-isolation | ExternalFixtureIsolationTest两项 | 实际单载体子JVM证明外部服务慢读与负载证据写入不饿死运行端 |
| kernel-provider-probe | ProviderConcurrencyProbeIT一项（显式soak） | 100调用方真实HTTP传输；无SQLite，仅诊断，不替代短程或四小时 |
| kernel-soak | KernelSoakIT（1项，显式soak、完整4小时） | 前轮完整四小时失败；本轮短程未过，未启动新长跑 |

一个类可具有多个本地标签；同一次Surefire执行只运行一次，不按表格行重复启动测试。
各任务对共享test-groups.json的登记须串行合并，不据此新增US3与US4的业务前置关系；清单之外的独立实现仍按tasks中的并行边界推进。
US2在T041先验收当时已实现的循环用例，T043加入截止用例并补全us2-loop；US5同理在T093–T095逐步补齐。
阶段出口必须核对截至该任务的登记项；最终T103按本表核对全部必跑类和重点场景用例，不能用先前较小的清单提前关闭最终验收。
T006必须用隔离报告样本证明：普通Test存在而IT缺失、要求用例被跳过、仅存在旧报告时均会失败；同时实际运行两类发现哨兵。人工浏览器、接收方确认和三个真实场景的报告仍须单独核验，JUnit清单不能替代它们。

## 功能要求映射

| 要求 | 主要实现位置／阶段 | 关键验证 |
| --- | --- | --- |
| FR-001 | core/cli，F0 | 初始化两次；已有文件哈希不变；缺配置可理解 |
| FR-002–FR-003 | core/provider，US1 | 目录名与配置、Provider显式映射；无效Agent不拖垮有效Agent |
| FR-004–FR-005 | provider，US1 | 两家真实请求和工具循环；401/429/坏JSON/缺凭证；无串用和fallback |
| FR-006–FR-008 | core，US2 | 顺序工具、多轮回填、10轮终止、同call不双调度、完整消息配对 |
| FR-009 | core，US2 | 修改正文／Bootstrap下一轮可见；配置变更不热加载；脚本只消费输出 |
| FR-010–FR-012 | core/storage，US3/US5 | 入口×用户×Agent矩阵；同组合多Session；身份不匹配拒绝；归档/分页/忙碌 |
| FR-013–FR-017 | memory/storage，US3 | 三backend；CORE原文；4000字符；关键词与语义分别验收；并发、空结果、写后读 |
| FR-018 | storage，US5 | Session/记忆/审计/调度状态与历史重启恢复，统一工作区 |
| FR-019–FR-025 | tool/memory，US4 | 九工具、原生扩展、MCP发现/分页/调用；schema离线；拒绝前无IO |
| FR-026–FR-029 | tool/core，US4 | Webhook接收与业务ack；未知不重放；退避有界；秘密不外泄 |
| FR-030 | cli/channel/boot，F0–US5 | 十二操作、轻命令不启动Spring、薄CLI与完整发行边界 |
| FR-031–FR-033 | web/core，US5 | 十基础操作+管理契约；32KiB/100条分页；60秒后零新调用 |
| FR-034–FR-037 | core/boot/web，US5 | 文件cron和时区；手动/自动同链路、防重叠；真实管理页；无配置CRUD |
| FR-038–FR-040 | storage/core/web，US5 | 每次模型/工具尝试审计；fault后暂停；重启不能绕过人工恢复 |
| FR-041 | 文档与发行，US5 | 新使用者按quickstart完成启动/请求/查询，真实与模拟证据分开 |

以上连续覆盖FR-001至FR-041，后续tasks必须引用这些要求。
各故事场景仍以spec.md的US1-A1至US5-A8为验收权威，表格不能替代33个场景。

## 必须优先做的集成风险验证

### Provider与协议
- HTTP替身验证actualProvider/model和endpoint/path；请求不能重复/v1。
- 两轮以上工具调用，捕获完整assistant/tool消息与reasoning_content不丢失；
  Kimi不发送不支持的thinking/temperature。
- Provider收到工具请求时本身不执行工具；仅ToolExecutor执行一次。
- 内部mcp.server.tool名称映射为合法wireName；两个server的同名工具不冲突，
  回放仍使用原始wireName与call id；实际MCP调用使用服务器原始remoteName，
  未知wireName不产生副作用。
- 低层客户端无隐式重试；401/429/5xx与连接失败分别映射。
- 一个Provider缺密钥／地址错误，仅其引用Agent不可用；另一Provider与Agent仍可调用。
- 创建空会话后改Provider/model并重启，旧会话仍返回创建快照，续聊409，不从当前配置补值。
- 真实Kimi和DeepSeek都完成一次工具回填；只有普通聊天通过不足以验收FR-004。

### 记忆与存储
- CORE超过归档预算仍原文完整，UTF-8多字节/代理对不被破坏；归档预算不是删数据。
- 两个Agent、多Session并发追加后，所有确认成功的内容仍存在。
- md原子写、损坏文件不重建为空；sqlite实库迁移/外键/事务，不能只用H2。
- Mem0无关检索为空、同义命中、scope和workspace ID正确；source映射不明的结果拒绝。
- Mem0返回200但空results、缺ID、返回ID回读不全或归属不符不能报告归档保存成功；
  未知写入不自动重放。CORE本地保存不依赖远端可用。
- 上游未报告的提炼遗漏不能靠回读ID检测；验证本地完整原文账本仍保留输入，
  并用固定语料核对增强检索，报告不声称所有事实的语义索引完整性已自动证明。
- JPA资源创建失败时诊断服务仍可启动；恢复能在关闸下重新创建资源，不能留下旧EntityManager。

### 权限与进程
- ../、绝对路径、symlink及写入父目录逃逸；已拒绝路径无读泄漏或写副作用。
- 禁止普通工具读取秘密、改AGENT配置／runtime规则／DB／故障标记。
- Shell明确argv，不通过sh -c；超时有界清理进程，记录无法确认清理的情况。
- 用伪秘密CANARY测试：MCP/Shell环境、stderr、API和审计都不能出现无关secret；
  原生工具参数若含秘密须脱敏。
- 恶意MCP schema包含远程$ref时，独立HTTP接收器计数必须为0；
  断连、call isError、超时和close失败各有错误。
- 允许的脚本自发联网不算内置HTTP白名单保护，本轮不声称有强沙箱。

### 截止与恢复
- 截止前一刻、截止时、截止后竞争；所有新模型/工具/通知/重试调度必须为0。
- 在途通知已被接收但回包迟到：显示已知／未知，不补发；迟到模型结果不启动工具。
- 在动作前写STARTED失败：外部调用计数0。
- 在通知后写结果失败：接收计数1；响应部分完成；新人工和定时任务计数0。
- 故障标志写入也失败：先前运行标记／STARTED仍导致重启暂停。
- 重启先检查旧运行标记，再创建新runId文件；确认之前旧标记内容和哈希不能被覆盖。
- 仅恢复数据库可写或重启，状态仍暂停；未核对／错误incident恢复请求拒绝。
- 人工确认并验证可写后，仅新任务可执行；原动作不重放，遗漏cron不补跑，审计缺口保留。
- query本身失败不能返回空列表；恢复检查也不能偷偷执行模型／业务工具。

## 本次评审补齐的验证场景

以下是待实施的验收安排，不是本轮已运行结果。

| 编号 | 对应要求 | 输入与可检查的结果 |
| --- | --- | --- |
| RV-01 未知动作终止 | FR-006、026、027；US4-A7 | 通知接收后丢失回包，剩余预算仍充足；假模型下一响应预设再次notify但不得被请求。发送计数1，新增模型/工具/重试均0，同轮其余调用NOT_EXECUTED，PARTIAL且SIDE_EFFECT_UNCERTAIN；其他会话仍可用 |
| RV-02 门面与历史 | 宪章III；FR-008、010、011、014 | MemoryService只向SessionHistoryPort获取一次历史；返回近期完整turn排除本次turn，PromptBuilder中每个消息ID恰好一次；换用户/Agent不串历史，但同工作区长期记忆可共享；后端不接收整段对话 |
| RV-03 空会话模型快照 | FR-003、008、010、018 | 创建尚无消息的Session，保存Provider/model，改变配置并重启；GET仍显示创建值，续聊SESSION_MODEL_CHANGED，调用模型次数0；原配置未变的空会话可继续 |
| RV-04 配置错误隔离 | FR-002、003、005；US1-A3 | 两家Provider中一家缺秘密、地址错误或选项无效，仅相关Agent不可用；另一家正常。YAML语法/重复名等整体错误才进入CONFIGURATION_ERROR；实际401/429不得变成全局配置错误 |
| RV-05 故障详情 | FR-038–040；US5-A7 | 注入通知后记录失败：runtime/state返回currentIncident及同一incidentId、已知/未知动作、缺口和来源；缺详情时detailsComplete=false，页面不得把空数组当无动作；修复并确认缺口后恢复，缺口仍保留 |
| RV-06 时间与纠正展示 | FR-006、009、032、034、037；US2-A4、US5-A4 | 主机设UTC，工作区Asia/Shanghai，固定到跨午夜时刻，手动和定时理解同一天；不一致调度时区被拒。无副作用参数错误经受控修正成功，SUCCEEDED且有已纠正警告；无法关联、多失败或权限拒绝仍PARTIAL |

RV-01另覆盖通知之外的写入/MCP未知副作用；不得只禁止SDK自动重试而允许模型换ID重发。
RV-06修正用例检查原失败outcome保留、resolutionStatus/纠正call ID可追溯；
修正关联仅由引擎核实，改变原有效目标、多工具回复或模型单方面声称成功不能清除错误。
安全重试成功仍可归集成功，不能因为保留失败attempt就误判未解决。
同工作区serve运行时chat须明确忙碌，管理台/HTTP仍可运行；轻查询不启动第二套内核。

## 成功标准对应证据

| 成功标准 | 必需报告 |
| --- | --- |
| SC-001 | 五故事各有成功与失败演示，33场景逐项结果 |
| SC-002 | DeepSeek、Kimi各自真实普通请求+工具回填，Provider配置错误矩阵 |
| SC-003 | 九工具允许／拒绝表、外部副作用计数、通知正常场景无重复 |
| SC-004 | 身份矩阵、三backend跨Agent共享/重启、核心原文与语义正反例 |
| SC-005 | 天气／科技日报／GitHub日报各手动+定时，共≥6次成功，以及各一种失败 |
| SC-006 | 正常可写时模型/工具成功失败拒绝记录覆盖100%；故障例单列，不伪造记录 |
| SC-007 | 10 Agents、100活跃Sessions、4小时；无串话、确认写入丢失或同task重叠 |
| SC-008 | 创建P99≤200ms；单任务内部开销≤50ms，报告最大值和分布；不排除DB排队 |
| SC-009 | 管理页操作与真实后端一致；错误/空/加载状态；没有Agent配置编辑入口 |
| SC-010 | 具备环境与凭证的新使用者30分钟内启动、请求、查询，操作录像／记录 |
| SC-011 | 输入、权限、模型、迭代、截止、存储暂停、人工恢复全错误矩阵 |

## 性能与语义测试口径

性能使用真实SQLite和完整审计，不使用内存AuditSink。100客户端每个持有独立活跃Session，
均匀分布10Agents；每个客户端最多一个在途任务，循环发起，记录实际吞吐和请求间隔。
fixture提供固定外部等待与确定性两轮工具任务；单任务内部耗时 =
端到端服务处理耗时减串行模型／工具外部等待，不能减去锁等待、数据库提交或框架开销。
采用单调时钟分段计时，单独报告JVM预热与稳态；必须保留完整4小时的数据。
硬件／负载口径固定写入报告后比较，不通过换统计口径把失败改成通过。

增强记忆测试固定服务版本、提炼模型、embedding、阈值和语料；
正例“关注AI芯片”与“人工智能硬件偏好”，负例“饮食忌口”外，还补入多个相关／无关样本。
模型输出事实和已保存原文由测试fixture人工标注对照；不把相似度分数本身当事实正确证明。

## 完成与暂停门禁

- 方案经人工评审后才生成tasks；tasks生成后再运行speckit-analyze检查三类工件一致性。
- 历史F0批次只实施T001–T018；此后US1–US3本地分批完成。历史US4授权为T059–T072；当前US5第五批授权为T091/T092，本批完成后停止，真实缺口不勾选。
- 三个早期风险按依赖先后验证：T006检查工程装配并作为F0-B前置；T017检查真实SQLite/JPA生命周期，必须通过T018才进入US1；T026检查Provider协议并作为US2前置。三项不要求挤入第一批六项任务。
- US2的T034先提供SessionManager/HistorySelector基础实现，T037只委托，T041即可验证；US3的T047完善既有规则，不能成为US2的反向前置。
- 任一风险验证失败，应修正设计实现并阻止依赖它的下一阶段，不修改已确认需求来迁就骨架。
- 缺真实凭证或服务时继续可控验证，最终真实项仍记未验证，不宣称核心验收完成。
- 性能不达标如实报告；保持已确认持久化与权限约束，方案调整需说明影响。
- 历史F0批次仅生成基础代码、测试和元数据迁移；US1–US4本地增量已分别授权和验证，该历史批次只推进US4；当前授权为US5第五批T091/T092，不更改全局环境。


## 本次一致性分析修订记录

| 分析项 | 文档修订 | 复查标准 |
| --- | --- | --- |
| I1 会话能力前置倒置 | tasks T034先交付SessionManager/HistorySelector基础实现，T037委托；T047改为完善，plan同步阶段归属 | T034→T035/T037→T041不依赖T047；US2沿用真实会话管理实现，仅存储/长期记忆使用显式测试替身 |
| U1 集成测试发现及必跑清单缺失 | tasks T003/T006及各测试任务补充Surefire发现、分组清单和本次报告核查；quickstart同步模式/命令 | 普通Test与IT均纳入；分组有明确必跑类，漏跑/跳过不通过；真实与性能仍显式启用，未配置不冒充成功 |
| I2 第一批定义不一致 | 本文件与quickstart明确首批T001–T006，三个风险点依次由T006/T017/T026验证 | 首批不要求提前实现Provider或正式存储；每项风险仍阻挡依赖它的后续阶段 |

本次仅复查上述修订的文档依赖、覆盖和边界；不将它记录为测试执行或业务验收通过。
以上为此前文档修订记录。当前授权与实施状态以tasks及US2证据为准；F0/US1记录保留为历史证据。宪章原则、产品决定和真实联调要求保持不变。


## US4 当前证据（本地与真实分开）

us4-tools登记21项，最终全量102测试/27类均通过；114个必跑登记含12个deadline跨组重叠。
详见[本地矩阵](evidence/US4-local.md)、[只读分析与代码核对](evidence/US4-review.md)。
real-notify与real-mcp已登记并有严格入口，未提供所选真实目标/可信服务和私有配置；前检失败而非skip，
没有外发消息或启动真实MCP；T070/T071/T072未完成。原有T027/T028/T042/T044/T051/T057/T058未改勾选。

## US5 第一批当前证据

T073存储/故障/重启子集登记16项：RestartRecoveryIT 5项、RecordingFailureIT 7项、PersistentStorageContractTest 4项。
`restart-recovery`复用RestartRecoveryIT；`us5-http`尚未登记，不能将本批视作HTTP整体通过。
全量118测试/30类，0失败/错误/跳过；135个必跑登记包括17个跨组重叠项。
T074–T081本地完成，T073整体、T082以后及此前10项真实前提/出口缺口保持未完成。
详见[本地证据](evidence/US5-first-batch-local.md)、[只读分析与代码核对](evidence/US5-first-batch-review.md)。

## US5 第二批本地出口（2026-11-13）

新增`us5-schedules` local分组：ScheduleIntegrationTest 16项、ScheduleStorageContractTest 2项，共18项。
实际Spring cron、生产CLI serve/gateway独立进程、跨JVM持久状态、真实SQLite故障与迁移均已运行，方法清单见tools/dev/test-groups.json。
最终clean verify为136个唯一测试/32类、0失败/错误/跳过；所有local组登记合计153项包含17项跨组重复，不冒充153个唯一测试。
T073整体、T093/T094及`us5-http`仍未完成；本批可控时间测试不替代HTTP真正等待60秒验收。
运行控制服务不等于HTTP/管理台交付；既有真实模型/Mem0/通知/MCP缺口保持未完成。
见[本地证据](evidence/US5-second-batch-local.md)及[一致性审查](evidence/US5-second-batch-review.md)。

## US5 第三批本地出口（2026-11-13）

新增us5-http-api分组15项：boot HttpApiContractTest 14项（12项实际端口整合，44schema字段/序列化和收尾504映射各1项），core DeadlineRaceTest新增1项最终记录阻塞截止；后者也纳入既有us2-loop/deadline-boundary。
19个操作逐一请求，真实60秒等待、SQLite审计/故障/恢复、分页、上下文重启与模型快照重载、调度共锁、客户端断开均通过。测试方法清单以tools/dev/test-groups.json为准。
最终clean verify：151个唯一测试/33类，0失败、错误、跳过。T084–T089完成；T073及T093–T095整体出口、完整CLI/管理台与真实联调仍未完成，不以44schema单元测试替代业务验收。[本地证据](evidence/US5-third-batch-local.md)、[审查](evidence/US5-third-batch-review.md)。


## US5 第四批本地出口（2026-11-13）

us5-cli登记15项，跨cli/boot两类；先取得6项行为失败基线，再补正式SQLite/独立JVM/实际stdio MCP/真实60秒截止验证。轻命令无Spring/外部连接、文件权威与停机归档、历史分页保留、启动快照差异、JSON公开投影及全部退出码已核对。接收/最终记录的阻塞边界另有受控时钟适配测试，不替代实际60秒测试。
全量166测试/35类，0失败/错误/跳过；185个local登记项包含19个跨组重叠。本批只完成T090，不勾选T073或T091以后；真实模型/Mem0/通知/可信MCP及浏览器/长跑/三场景整体出口保持开放。
[本地证据](evidence/US5-fourth-batch-local.md)、[一致性审查](evidence/US5-fourth-batch-review.md)。

## US5 第五批本地出口（2026-11-13）

T091/T092完成；us5-admin登记AdminPageContractTest两项，先有实际404失败基线，再验证正常/暂停工作区静态入口及同源API。最终全量168测试/36类，0失败/错误/跳过，187个登记项含19个跨组重叠。
全量后跳转焦点及帮助说明的小型修正经定向2项、最终真实Chrome12组流程及打包admin黑盒复验；另有键盘、Python6项和foundation5组。浏览器实际写入8次、回环副作用3次；API与SQLite的31会话/19执行/23模型审计一致。正式发行没有测试页/注入端点。
本地浏览器服务明确标记回环模型与接收器，未替代真实外部服务验收；T073、T093–T095和既有十项缺口均保持未勾选。[本地证据](evidence/US5-fifth-batch-local.md)、[一致性审查](evidence/US5-fifth-batch-review.md)。

## US5 第六批初次验收出口（历史失败快照，2026-11-13）

T093通过；CrossProcessAcceptanceIT三项使用实际发行JAR进程、HTTP/CLI和SQLite，补齐正式notify接收1次后记录/fault双故障、旧run证据及人工恢复不重放。us5-process定向3项通过。
T094综合us5-http已登记70项，实跑69通过/1失败/0错误/0跳过；PublishedOpenApiTest读取实际/v3/api-docs并逐操作/参数/44schema对照，185个比较项不符。全量172项/38类同样唯一此项失败，171通过、0错误/跳过。失败保留默认local清单，不能跳过后宣称整体通过。
T095因前置T094未通过而未进入，T073不关闭。第五批浏览器记录保持原批次；旧CLI回归通过不替代T095完整发行验收。此前十项真实联调缺口保留。[第六批记录](evidence/US5-sixth-batch-local.md)、[HTTP差异](evidence/US5-http.md)。


## US5 第六批续作出口（2026-11-13）

运行端MVC/DTO元数据生成实际OpenAPI 3.0.3，19操作/44schema零差异。原比较断言保留，新增精确计数及DTO派生测试。us5-http为71项通过；us5-cli-process新增CliCommandsIT三项，实际发行JAR十二操作/全部退出码/60秒截止/历史保留通过。
Chrome13组真实后端流程及人工键盘/恢复、API/SQLite核对通过，新增调度写失败与恢复落库失败，无乐观成功或自动重试。
最终verify为176个唯一测试/39类，266个登记引用包含跨组重叠，零失败/错误/跳过；Python6项、发行宿主5项通过。T093–T095出口齐全，T073关闭；T096–T104及十项真实缺口不变。详见[续作记录](evidence/US5-sixth-batch-continuation.md)及[一致性审查](evidence/US5-sixth-batch-review.md)。


## US5 第七批出口（2026-11-13）

T096三个Agent示例与T097可控全链路完成。先留缺资源红灯，再实现示例；三场景各手动+自动cron、共享偏好、按需参考、脚本输出、全过程SQLite审计与副作用计数通过。
失败矩阵覆盖三个数据源、脚本非零/拒绝、不完整源、缺参考、空新闻、三个通知拒绝/UNKNOWN、实际记忆写入失败及脚本凭证边界。
最终使用发行JAR的verify为185个唯一测试/40类，275个登记引用含跨组重叠，零失败/错误/跳过；Python门禁6项通过。实际OpenAPI零差异。
此前十项真实联调缺口和T098–T104不变，T073保持第六批已完成状态；本批未重做浏览器人工验收，也未修改页面/后端生产代码。
详见[可控全链路证据](evidence/US5-kernel-local.md)、[摘要](evidence/US5-seventh-batch-summary.json)与[一致性审查](evidence/US5-seventh-batch-review.md)。


## US5 第八批前检（2026-11-13，未完成真实验收）

第七批源码/示例/JAR哈希复核一致；未重复运行185项本地测试。维护者确认尚未准备真实配置，T098–T100未执行且不勾选。
本轮real-provider 2项、real-provider-tools 2项、real-notify 1项、real-mcp 1项在缺工作区/目标/服务参数处失败，0错误、0跳过；两次命令均退出1。
失败发生于客户端创建/服务启动之前，没有真实模型、业务源、可信MCP或通知动作；不计作三个真实场景的失败演示。
既有验收要求、真实分组与门禁不变；第八批当时未进入T101及后续，当前进度见下方第九批续轮。见[第八批审查](evidence/US5-eighth-batch-review.md)与[实际前检摘要](evidence/US5-eighth-batch-preflight.json)。


## US5 第九批性能验收（2026-11-13～14，T101 未通过）

本地 kernel-soak-harness 4项及全量189项/41类回归通过，零失败/错误/跳过；实际OpenAPI 19操作/44schema仍零差异。
显式 kernel-soak 使用10 Agent/100独立活跃会话、连续14400秒及22.635秒排空，56599次任务成功；
真实SQLite逐条核对身份、审计、已确认写入、配对和重叠通过。创建P99为78.538541ms，内部最大为2184.36475ms，
56350次超过50ms。因此正式用例1失败、0错误、0跳过，T101保持未完成；不以P99/平均数替代最大值。
运行源码、固定负载与计时边界保持不变，完整客户端往返与外部等待也保留；从原始样本独立重算一致。
原始记录与条件见[性能报告](evidence/US5-performance.md)、[安全摘要](evidence/US5-performance-summary.json)、[审查](evidence/US5-ninth-batch-review.md)。
此前真实缺口与T098–T100不变，T102–T104未进入。本批止于T101，不提交或推送。


## T101续轮计时补充

原服务端内部耗时保留：serverNanos减外部扣除值。新增入口保守上界为max(serverNanos,clientNanos)减同一外部扣除值；它包含客户端/回环传输，不能当成精确纯服务端耗时。两个最大值都须≤50ms才可通过，不将入口外排队隐藏掉。
RuntimeTiming分段为嵌套区间，不可直接相加。原始start/end支持独立区间分解，外部sleep超过配置的部分仍计入内部开销。固定负载文件、创建P99≤200ms、全部四小时、完整审计及无串话/丢确认数据/重叠要求不变。
续轮测试与中断记录见[续轮审查](evidence/US5-ninth-batch-continuation-review.md)；完整四小时仍超出50ms，T101保持未勾选。

## 第九批续轮最终复测（2026-11-16，T101 未通过）

- 本地全量203项/43类、293项必跑引用，零失败/错误/跳过；runId `805b0176-a707-4095-b1ed-ad35719fb260`，运行端OpenAPI仍为19操作/44schema零差异。进程回归走main/classpath，不额外宣称本轮发行JAR验收。
- 正式四小时runId `1f61d38e-5854-4701-b46f-cb6b3d1ba4ad`：14400秒稳态+9.542393秒排空，57400次成功，零失败/违例；完整SQLite审计、工具配对、无丢确认写入/串话/重叠通过，退出后integrity/FK检查通过。
- 创建P99=16.056833ms；内部P50=83.391208ms、P99=308.029333ms、最大=1011.133125ms，49000次超过50ms；入口上界最大1012.571541ms。正式用例1失败、0错误、0跳过，Maven退出1，T101未勾选。
- 相邻心跳墙钟与单调时钟间隔最大差0.087556秒；没有把旧中断时长累计进来。原始数据独立重算分布并对最慢样本分解互斥区间，队列、DB和审计耗时未扣除。

见[续轮性能报告](evidence/US5-performance-continuation.md)和[机器摘要](evidence/US5-performance-continuation-summary.json)。初次四小时失败、两次中断和全部真实联调缺口保留；不进入T102及以后，不提交或推送。

## T101 提交与写队列本轮出口（2026-11-16）

全量207项/44类、297项必跑引用通过，0失败/错误/跳过；实际OpenAPI19操作/44schema零差异。独立短程最大322.241791ms、500/500超标，原50ms门禁真实失败，未启动新的四小时复测。旧四小时结果仅为旧实现的失败证据；T101未勾选，真实联调缺口不变。

显式短程/最小写入组与普通local、固定kernel-soak分组隔离，不混入四小时类，也不通过跳过用例变绿；本轮已实际运行短程并保存失败XML。所有样本及SQLite完整审计保留，见[本轮证据与命令](evidence/US5-ninth-batch-commit-queue-review.md)。


## T101 读取与提交后恢复最新出口（2026-11-16）

全量216项/50类、306项必跑引用通过，0失败/错误/跳过；实际OpenAPI仍19操作/44schema零差异，tools/dev隔离与报告门禁6项通过。最终独立短程500次成功、全部审计通过，内部最大271.953375ms、P99=265.394667ms，500/500超标；入口上界290.153250ms。短程1失败/0错误/0跳过，未启动新四小时，T101保持未勾选。

每个短程的全部样本/外部扣除和退出后SQLite审计已独立重算；本轮保留7次短程、313份最终源码/配置快照及红绿记录。同步测试证据日志仅更换竞争锁，未延迟写入/刷新到请求结束之后；原负载、50ms断言、队列/DB/审计计时保持不变。真实联调、T098–T100及T102以后缺口不变。[本轮证据](evidence/US5-ninth-batch-read-wakeup-review.md)、[机器摘要](evidence/US5-performance-read-wakeup-summary.json)。


## T101 模型传输与组内等待最新出口（2026-11-16）

本节更新上文历史续轮状态。全量221项/51类、311项必跑引用通过，0失败/错误/跳过；实际OpenAPI 19操作/44schema零差异，tools/dev隔离与报告门禁6项通过。最终独立短程500次成功，内部最大201.795500ms、P99=199.531125ms，500/500超标；入口上界207.072667ms。短程1失败/0错误/0跳过，未启动新四小时，T101未勾选。

新增5个local测试方法及一个显式soak模型传输探针；探针未含SQLite，不充当整体验收。四次短程的全部样本、外部扣除与退出后SQLite独立核对；317份最终源码/配置快照。测试服务/驱动改为独立有界平台池，其全部IO仍计时，负载、50ms断言、审计、队列/DB口径不变。真实联调与T098–T100缺口保留，T102以后未进入。[本轮证据](evidence/US5-ninth-batch-transport-batch-review.md)、[机器摘要](evidence/US5-performance-transport-batch-summary.json)。
