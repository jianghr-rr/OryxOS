# Implementation Plan: OryxOS 单机 Agent 运行内核

**Branch**: main（实际 Git 分支） | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

**Feature Directory**: specs/001-single-node-agent-runtime。
setup-plan 返回的 BRANCH=001-single-node-agent-runtime 是功能定位值，本轮未创建或切换 Git 分支。

**Input**: 已确认宪章 v1.0.0、完成三项澄清的规格、个人仓库 POM 与技术方案。
**Status**: tasks已生成并完成G0检查；F0实现及验证见evidence/F0-foundation.md。四项产品确认与六项设计补齐保持有效；US1已通过T026，US2已通过T041/T043，US3已通过T056，US4本地已通过T069；T027/T028、T042/T044、T051/T057/T058、T070/T071/T072保留真实前提/出口缺口，US5已完成T073–T097；实际OpenAPI为19操作/44schema零差异，跨进程/HTTP/发行CLI/浏览器出口通过，T073已关闭。第七批三个Agent示例与可控全链路9项、全量185项通过；第八批已做T098–T100依赖与真实配置前检，维护者确认未准备配置，三项验收仍未完成；第九批继续定位模型传输、组内等待与提交后恢复，补齐分段计时，修复模型请求体平台线程增长，并隔离测试服务及负载驱动的载体阻塞；最终独立短程内部最大201.795500ms、500/500超出50ms，入口上界207.072667ms，未启动新的四小时复测，T101未完成；本地221项/51类回归通过。T102–T104未进入。见[第八批审查](evidence/US5-eighth-batch-review.md)。见[evidence/US5-kernel-local.md](evidence/US5-kernel-local.md)；第六批记录见[evidence/US5-sixth-batch-review.md](evidence/US5-sixth-batch-review.md)。 第九批见[性能证据](evidence/US5-performance.md)。 前轮四小时失败记录见[续轮性能证据](evidence/US5-performance-continuation.md)。 本轮见[本轮传输与组内等待证据](evidence/US5-ninth-batch-transport-batch-review.md)。

## Summary

交付 Provider → ReAct → Memory / Tool → Web Service 五项核心能力。
CLI、HTTP、定时任务共用引擎；三个整合场景由 Agent 目录、指令和工具组合表达。
首版管理台目标为真实查询和运行控制。F0提供工程、配置、工作区与存储资源基础；US1已有Agent解析、单次Provider调用和结构化日志；US2已有顺序循环、会话基础、统一工具和CLI接入；US3已补齐MemoryService与md/sqlite/Mem0协议适配；US4已补齐九工具、原生扩展和stdio MCP。本地验证通过，相关真实验收待完成。正式发行已装配SQLite Session/Execution/Audit，普通chat可配置运行；故障人工恢复内核已验证；文件调度已在serve/gateway装配，运行控制及恢复HTTP已交付；同源原生管理台查询与控制已本地验证。

采用 JDK 21 / Boot 3.5.16，保留九模块。Spring AI 只处理单次模型协议通信和工具描述；
循环、权限、重试、截止、审计由本项目控制。SQLite 保存会话与运行记录；
长期记忆提供 md、sqlite、mem0 三档，归属均为工作区共享。

## Technical Context

**Language/Version**: Java 21，编译 release=21；管理页面原生浏览器 JavaScript。

**Primary Dependencies**: Boot 3.5.16；AI 1.1.8；MCP 0.18.3；Picocli 4.7.7；
SQLite JDBC 3.51.3.0；Hibernate core/community 6.6.53.Final；Schema Validator 1.5.9；
Springdoc 2.9.1；其余 Spring/Jackson/SnakeYAML 跟随 Boot。[完整版本与证据](research.md)。

**Storage**: 工作区/oryxos.db；默认 .oryxos/oryxos.db；WAL、本地文件系统、显式迁移。
md 默认原文文件；mem0 的 CORE／来源账本本地保存，ARCHIVAL 增强使用固定 OSS 2.2.1。

**Testing**: Boot 管理的 JUnit Jupiter、Mockito、MockMvc；JDK 本地 HTTP 服务作为模型／通知替身；
真实 SQLite、可控 stdio MCP、独立工作区；人工浏览器验收；真实供应商联调单独报告。
测试统一由Surefire在test阶段发现普通测试及*IT.java，verify不重复执行；
分组必跑清单、报告核查、real/soak显式启用规则见[测试执行契约](verification.md#测试执行契约)。

**Target Platform**: 本机 macOS arm64 开发，Linux JDK 21 运行；默认回环地址。
个人构建通过ORYXOS_JAVA_HOME选择JDK21；验证只读复用既有JDK，未硬编码课程工具路径。

**Project Type**: 单进程 Java 运行内核、CLI、同步 HTTP API 和管理页。

**Performance Goals**: ≥10 Agents、≥100 并发 Sessions、连续 4 小时；
Session 创建 P99≤200ms，单任务内部额外耗时≤50ms，外部等待单独记录。
数据库排队、校验、序列化时间计入内部耗时，不靠排除这些开销满足目标。

**Constraints**: 同一工作区最多一个运行进程；同 Session 不并行；
同轮工具顺序执行；手动请求 60 秒截止；不自动重放未知副作用；
记录故障关闭执行闸门，人工恢复；配置文件权威，正文按次读取。
未知副作用立即结束本次任务；首版运行模式互斥；业务时区由工作区统一配置，默认Asia/Shanghai。

**Scale/Scope**: 5 个核心故事、41 项功能要求、33 个故事验收场景、3 个整合场景；
9 项工具、12 项 CLI 基础操作、10 个基础 HTTP 操作及管理所需查询和控制。

## Constitution Check

“通过”只表示设计符合约束，不表示实现和测试通过。宪章文件遗留的批准元数据按 spec AS-01
处理，使用用户已确认内容，不在本轮修改宪章。

| 原则 | 研究前检查 | 设计后检查与依据 |
| --- | --- | --- |
| I 单机与核心范围 | 通过：五项均必交付，管理台已在范围内 | 通过：阶段顺序、三 Demo、无分布式／Agent CRUD |
| II 自有循环 | 通过：框架只作单次调用 | 通过：[runtime](contracts/runtime.md) 单一 ToolExecutor，默认无自动工具能力 |
| III 职责边界 | 通过：core 端口，适配实现向内依赖 | 通过：MemoryService委托SessionManager与长期记忆后端；下方DAG无环，Spring DTO/JPA实体不出实现模块 |
| IV 权限与秘密 | 通过：动作前校验，信任边界清楚 | 通过：[configuration](contracts/configuration.md)，子进程最小环境、schema 离线、秘密不进入 UI |
| V 审计与恢复 | 通过：US1–4 日志，US5 持久化 | 通过：[data-model](data-model.md) 预写意图、故障闸门、人工确认与重启检查 |
| VI 可演示与检查 | 通过：规格已有逐故事验收 | 通过：[quickstart](quickstart.md) 与 [verification](verification.md) 分开模拟／真实 |
| VII 工件一致性 | 通过：不据骨架缩减需求 | 通过：FR 全覆盖映射；保留原文件；后续 tasks/analyze 再检查一致性 |

无需要豁免的宪章冲突。依赖可解析已验证；SQLite 社区方言、Kimi 协议适配与真实服务
仍有实施验证门禁，不能绕过这些门禁直接宣布功能完成。

## Project Structure

### Documentation (this feature)

~~~text
specs/001-single-node-agent-runtime/
├── spec.md                         # 已确认，不由本轮修改
├── plan.md                         # 本计划
├── research.md                     # 版本证据和技术取舍
├── data-model.md                   # 实体、存储、状态转换
├── verification.md                 # 要求映射、模拟/真实/性能验证
├── quickstart.md                   # 实施后的操作与验证指南
├── contracts/
│   ├── runtime.md                  # 内部端口和完整请求链
│   ├── configuration.md            # Agent/Provider/工具/记忆配置权威
│   ├── http-api.json               # OpenAPI 3.0.3 契约
│   └── cli-admin.md                # CLI、页面和人工恢复流程
└── checklists/requirements.md       # 原规格清单，本次仅同步过时状态说明
~~~

tasks.md 已由 speckit-tasks生成并复查；本轮授权止于US5第六批T095，已完成该范围及T073本地出口，状态和证据见任务清单及evidence/。

### Source Code (repository root)

以下为评审时的目标路径安排；F0及US1/US2/US3/US4本地实现路径现已落地，相关真实验收及T096以后仍待完成，具体以tasks勾选与证据为准：

~~~text
pom.xml                            # 保留父版本，后续增加依赖管理
oryxos-core/src/                    # 新增中立类型、端口、编排、配置读取、闸门
oryxos-provider/src/                # 新增单次模型协议适配
oryxos-memory/src/                  # 新增三档记忆与 MemoryTools
oryxos-tool/src/                    # 新增执行器适配、内置工具、MCP、权限
oryxos-storage/src/                 # 新增 JPA/SQLite 适配、轻量查询、故障生命周期
oryxos-storage/src/main/resources/db/migration/  # 后续显式 SQL
oryxos-channel-cli/src/             # 新增 stdin/stdout 会话入口
oryxos-cli/src/                     # 保留版本入口，增加命令解析
oryxos-web/src/                     # 新增 REST、错误映射、管理页面
oryxos-web/src/main/resources/static/admin/     # 后续管理页
oryxos-boot/src/                    # 保留入口，补齐装配与调度适配
tools/dev/                         # 后续个人项目构建与运行入口
website/                           # 现有文档站，保持独立
~~~

**Structure Decision**：保持九模块，使用端口隔离，不用模块数量掩盖职责混杂。

~~~mermaid
flowchart TD
  boot --> cli
  boot --> provider
  boot --> memory
  boot --> tool
  boot --> storage
  boot --> web
  boot --> channel["channel-cli"]
  cli --> channel
  cli --> core
  channel --> core
  provider --> core
  memory --> core
  tool --> core
  storage --> core
  web --> core
~~~

memory 的工具实现只依赖 core 的 OryxTool；由 boot 注册进 tool 的 Registry。
storage 实现 core 存储端口；memory 的生产代码不直接引用 storage。
US3真实Mem0验收在memory模块增加test scope的storage依赖，用真实SQLite账本复核重启；不进入生产依赖DAG。
core 使用 JDK 与安全 YAML 解析，不引用 Spring AI／JPA／业务模块。
MemoryService统一访问会话历史和长期记忆，SessionManager负责生命周期及完整turn选择；
PromptBuilder只消费一次ContextMemory，存储适配器不决定上下文策略。

## 现有骨架审查：复用、调整、新增

| 现状 | 处理 | 理由与后续检查 |
| --- | --- | --- |
| 父 POM、九模块、依赖管理与 Enforcer | 复用；增加表中 BOM/版本 | 保留 Java 21，禁止凭框架新文档升级到 Boot 4 |
| 各能力模块目前只有 package-info | 保留命名空间，新增业务实现 | 不能标记 Provider/Memory 等已完成 |
| Boot 的 scanBasePackages 与启动入口 | 保留，调整启动模式分发 | 无参数仍 serve；命令解析先于 Spring 初始化 |
| Boot 聚合所有实现并依赖 cli | 复用依赖方向 | 新增 RuntimeLauncher 注入，拒绝 cli → boot |
| CLI Maven 版本资源与版本输出 | 复用，补充依赖打包说明 | 薄 JAR 版本路径仍可用；完整命令由 boot fat JAR 提供 |
| web 已依赖 starter-web | 复用 MVC/Tomcat | 不因为 AI 传递带 webflux 就改应用服务器 |
| application.yml：8080、虚拟线程 | 复用基础设置，补齐回环监听与工作区加载 | 业务配置单独由统一 loader 读取 |
| 唯一 SpringBootTest | 保留并调整为临时工作区、无真实凭证的装配测试 | 当前只验证上下文启动；不能替代故事验收 |
| 无 JPA、迁移、调度、MCP、API、管理台 | 全部新增 | 依照已确认需求，不从旧课程记录复制“已实现”结论 |
| 课程 run.sh 绑定官方仓库 | 不用于个人项目 | tools/dev 入口只列为待实施，固定个人根目录并检查 |
| 当前默认 Java 17 | 不修改全局 | 新入口从 ORYXOS_JAVA_HOME 取得 JDK 21，只影响子进程 |

## 完整请求过程

详见[内部契约](contracts/runtime.md)；以“生成科技日报并通知”为例：

~~~mermaid
sequenceDiagram
  participant U as CLI/HTTP/定时入口
  participant A as AgentService
  participant S as Session与存储
  participant M as Memory/上下文
  participant P as Provider单次调用
  participant T as ToolExecutor
  U->>A: 任务、入口身份、Agent、可选Session
  A->>A: 校验配置/工作区闸门/会话并发，建立执行ID和截止
  A->>S: 保存执行与用户消息
  loop 最多10轮且仍允许新动作
    A->>M: 重读正文/公共设定，MemoryService取得一次历史和共享记忆视图
    A->>S: 写模型尝试STARTED
    A->>P: 一次模型请求与工具schema
    P-->>A: 正文、工具调用、协议续传资料、用量
    A->>S: 保存模型结果及结构化assistant消息
    opt 模型提出工具请求
      A->>T: 按顺序执行
      T->>T: 参数/Agent工具集合/资源权限/截止校验
      T->>S: 写工具尝试STARTED或拒绝记录
      T-->>A: 实际结果或失败/未知结果
      A->>S: 写工具结果与配对消息
      opt 副作用结果不确定
        A->>A: 关闭本次执行，标记余下请求未执行，不再调用模型/工具
      end
    end
  end
  A->>S: 保存终态与完成消息
  A-->>U: 执行ID、响应、动作结果与记录状态
~~~

任一步记录失败都关闭工作区闸门；不会先向用户返回成功再异步补写。
60 秒后不再启动下一动作；已经发出的外部请求可能稍后返回，结果仅补记，不触发新循环。
模型的自由文本不能把失败的通知改成已送达。
UNKNOWN副作用无需等到60秒：立即停止本次执行并返回核查说明；记录正常时不暂停其他任务。

## 实施顺序与阶段出口

以下阶段已由tasks拆分；本轮用户授权为US5第六批T093–T095整体验收；先补验证，不进入T096及后续整合，外部前提缺失保留未完成。

| 阶段 | 复用／调整／新增工作 | 出口证据 |
| --- | --- | --- |
| F0 工程与配置地基 | BOM/依赖范围、个人 tools/dev 入口、CLI/Boot 装配契约、安全配置读取、临时工作区测试能力 | JDK21构建、无依赖环、--version/--help不启动Spring；全部变更经方案评审 |
| US-1 Provider | 中立消息和单次调用端口、两家显式映射、协议字段往返、分作用域配置错误、结构化日志 | 一家缺密钥不拖垮另一家；假模型来源隔离；两家真实普通请求；无工具自动执行 |
| US-2 ReAct | Agent/context加载、SessionManager/HistorySelector基础实现、顺序循环、ToolExecutor、截止/迭代控制、未知副作用终止与参数纠正关联、最小HTTP工具、CLI入口 | 多轮天气任务、工具恰执行一次；UNKNOWN后零新增调用；可证明纠正才成功；拒绝与上限 |
| US-3 Memory | MemoryService门面、完善既有SessionManager的归档等生命周期规则、创建时模型快照验证、CORE/ARCHIVAL、三backend、MemoryTools；存储迁移基础可在此建立 | 历史仅一次、跨Agent共享、身份历史不串、空会话重启快照、三backend写后可读 |
| US-4 Tool | 补齐9工具、白名单、Webhook、MCP、本地扩展示例、有限重试和最小子进程环境 | 各工具成功/拒绝；可控MCP及真实通知；副作用未知不重放 |
| US-5 Web与收尾 | REST、CLI完整12项、管理台、统一时区调度、会话/审计/调度持久化、故障详情与恢复、迁移与文档 | 不完整故障可核查、人工恢复、统一时区跨午夜、三Demo各手动+定时、100会话4小时与性能 |
| 验收整合 | 逐项检查33场景、41 FR、11 SC，分别标记模拟/真实证据 | [verification](verification.md)全部对应；未通过项不能算完成 |

US-3 与 US-4 在 US-2 契约稳定后可独立推进，MemoryTools 的完整整合需两者就绪。
US2先交付真实SessionManager/HistorySelector的基础行为，AgentService只负责委托和编排；
测试装配仅替换SessionStore/ExecutionStore与长期记忆后端，不复制会话策略。
US3在既有会话实现上完善规则；不得把US2验收依赖的能力留到US3才首次实现。
前期可用临时内存 Session 和结构化日志演示；不得让这套替身成为 US-5 默认生产模式。
最终只读查询、管理台和 CLI 都从同一工作区读取数据。

## 2026-11-12 技术评审修订与已确认决定

本节保留逐项问答与设计补齐记录；后续任务生成和F0实现另获本轮用户授权，产品决定不变。

| 决定 | 维护者确认 | 落实位置 |
| --- | --- | --- |
| 运行入口 | A：chat与HTTP服务首版互斥，服务运行时用管理台或HTTP提交任务 | contracts/cli-admin.md，进程锁与忙碌提示 |
| 未知副作用 | A：立即结束本次任务，提示核查，不再调用模型或工具 | contracts/runtime.md，SIDE_EFFECT_UNCERTAIN与同轮未执行记录 |
| 业务时区 | A：工作区统一，默认Asia/Shanghai；定时配置保持一致 | contracts/configuration.md、HTTP Info/Execution与跨午夜验证 |
| 纠正后状态 | A：确已纠正且其余完成可成功，保留错误记录；无法确认仍为部分完成 | contracts/runtime.md参数修正窗口、ToolAttempt纠正关联与页面提示 |

六项评审缺口分别落实：未知结果不能借下一轮模型重发；Memory统一门面与会话管理分工；
sessions创建时模型快照；Provider局部失败隔离；RuntimeState内嵌故障详情；统一业务时区。
新验证均登记在verification.md的RV-01至RV-06，不把本轮文档检查当作行为验证通过。

## 主要风险与人工评审重点

1. **双入口与装配**：复核 boot fat JAR 完整发行与 CLI 薄 JAR 版本入口的实现；
   防止新增循环依赖或轻命令偷偷启动模型连接。
2. **Provider 协议兼容**：Kimi 必须保留 reasoning_content；审查它的存储访问和公开输出脱敏，
   确认两家真实工具调用均有证据。
3. **存储故障生命周期**：审查预写 STARTED、持久运行标记、降级查询与人工恢复；不要只设计正常路径。
4. **增强记忆成本与边界**：Mem0 增加独立服务、模型和embedding依赖；不允许变相发送整段对话或截断CORE。
5. **默认安全边界**：回环监听、同源管理页、受信任脚本、进程环境收敛；
   普通文件工具不允许改运行配置、数据库及秘密文件。
6. **SQLite性能**：100会话与完整审计目标可能暴露写队列瓶颈；先测真实SQLite，不擅自降低持久化保证或修改指标。
7. **文档效力**：CLAUDE.md及规格清单状态在本次同步；宪章原批准元数据仍按spec AS-01解释，
   不补造批准日期、不改原则，不能据旧状态文字推翻已确认决定。

## Complexity Tracking

无宪章豁免项。新增复杂度仅用于兑现已确认要求：
模型协议续传、记录故障恢复和增强记忆来源账本；替代方案及不采用原因见 research.md。

## US2 实施补记（不改变产品契约）

- `core`增加中立的Execution/Session/Tool端口与自有循环；SessionManager持有身份、忙碌及完整turn规则，MemoryPort只经SessionHistoryPort取一次历史。
- 最小HTTP GET在tool模块使用Boot BOM管理的HttpClient 5.5.2 / HttpCore 5.3.6，显式关闭底层自动重试、重定向和Cookie；ToolExecutor统一重试，每跳重新授权。依据与验证见research补记。
- 临时SessionStore、ExecutionStore、空长期记忆与早期装配只存在于测试源码，不打入发行JAR。CLI生产入口复用AgentService，正式端口未齐时明确NOT_READY。
- 默认手动60秒预算在入口建立。调用方截止先关闭动作许可；已交接的在途动作可能晚于截止返回，会话保持占用直到同步调用及收尾退出。迟到事实仅补记录；真正HTTP 504/60秒与数据库恢复仍由US5验证。

## US3 实施补记（不改变产品契约）

T045–T050、T052–T056完成；V002仅新增记忆账本/远端映射，V001不变。
MemoryService已替换早期EmptyMemoryFacade测试装配；正式SessionManager负责身份、历史和归档，测试Store仅替代持久化。
MD/SQLite采用Unicode NFC与固定Unicode17.0.0完整case folding表做字面匹配；原文不变，归档注入不超过4000 code points。
Mem0逐请求许可/截止控制、原文来源先记录后远端确认；回读401或断连保留UNKNOWN，不重发。
隔离服务准备已锁源码/Python依赖/基础镜像，应用镜像digest与真实语义、容器重启仍待T051/T057。
本地81项全量回归与边界证据见[US3-local](evidence/US3-local.md)；阶段审查见[US3-review](evidence/US3-review.md)。


## US4 实施补记（不改变产品契约）

T059–T069已完成；九基线工具、原生Echo、stdio MCP由boot注册，全部经统一ToolExecutor。
MCP复用0.18.3同步客户端协议，受控transport限制协议行/错误流并清空环境；显式复用NetworkNT1.5.9离线校验器，
避免SDK默认validator依赖另一主版本API。无自动重连/重放/sampling/elicitation；依赖版本和生产模块DAG不变。
本地102项全量回归通过（US4新增21项）；真实通知/可信配置MCP及US4总出口仍待T070/T071/T072。
默认chat仍缺US5正式Session/Execution存储，结构化日志不等于落库审计。
详见[US4-local](evidence/US4-local.md)、[US4-review](evidence/US4-review.md)。

## US5 第一批实施补记（不改变产品契约）

T074–T081完成，T073仅存储/故障/重启子集完成，完整HTTP验收保持未勾选。
新增V003会话/消息/执行/工作区状态、V004逐次审计、V005incident；V001/V002保持原样。
正式装配使用JpaSessionStore/JpaExecutionStore/JpaAuditSink，所有SQLite必需写入经单写协调器；
新会话+execution+USER原子提交，后续审计/消息分别短事务确认，任一失败立即暂停且不对外报告完整成功。
SessionManager保留唯一身份/完整turn策略；协议opaque只存私有列，只读历史不读取该列。
运行进程先核查旧证据再独占创建新run；故障信息来自数据库、内存和独立标记，不把缺记录当成无动作。
RecoveryPort在关闭闸门且无本地执行/在途时重建资源，提交确认及缺口说明、清理已确认旧证据后开闸。
第一批交付时HTTP恢复操作和管理台留待T088/T092；第三批已完成T088，第五批已完成T092本地页面。不新增CLI恢复命令或绕过确认的开关。
普通chat可运行，serve/gateway共享正式引擎/工作区锁；后续第二批补齐调度、第三批补齐HTTP，第五批补齐管理台。
新增16项本地测试、全量118项通过；[证据](evidence/US5-first-batch-local.md)、[审查](evidence/US5-first-batch-review.md)。
前文各US实施补记保留当时状态，不作为当前生产可用性的判断依据。

## US5 第二批实施补记（不改变产品契约）

T082–T083完成，新增V006调度状态/触发历史；前五个迁移保持原文与checksum。
ScheduleDefinition保留启动时文件快照，JpaScheduleStore只存运行状态；初始enabled只使用一次，删除/无效定义不删历史。
ScheduleCoordinator通过AgentService进入同一引擎，自动与手动共享占用直到迟到收尾结束；停用写入与自动接收串行校验。
调度事件在外部IO前持久提交，写失败沿用全局记录暂停；恢复资源后在关闸期间重新协调状态，旧事件不补跑。
ThreadPoolTaskScheduler/CronTrigger使用工作区时区和六字段表达式，回调只交接执行，不等待模型；句柄取消和下一时刻计算不做补偿触发。
自动身份scheduler/oryxos-scheduler；手动保留cli/http调用者；均新建一次性Session。自动预算默认60秒且可文件放大，手动60秒。
serve/gateway启用调度，chat仅对话。启停/立即执行的内部服务已验证，第二批交付时外部入口留待T087/T092；第三批已完成HTTP，第五批已完成页面。
本批新增18项，最终全量136项通过；[证据](evidence/US5-second-batch-local.md)、[审查](evidence/US5-second-batch-review.md)。
T073整体与既有真实联调缺口保持未完成；第一批补记仍表示当时状态。第二批当时止于T083；第三批与第四批状态见下。

## US5 第三批装配与验证（2026-11-13）

T084–T089完成。web通过WebRuntimePort访问boot组合的正式SessionManager、AgentService、ScheduleCoordinator、RecoveryPort与只读SQLite查询；web只依赖core，不序列化JPA/opaque。29个数据schema使用明确DTO，15个信封共用ApiResponse<T>。
HTTP从入口建立60秒单调预算，DeferredResult配合最多64个等待/收尾任务的虚拟线程；取得Handle即向等待器发布，记录阻塞不能隐藏已建立的执行ID。超时先停止新动作许可再响应；客户端断开不取消已交接动作、不释放执行锁。
模型已返回但最终记录仍待完成时也返回504，保留已知模型终态和recordingStatus=UNKNOWN；实际收尾后的查询可显示COMPLETE，旧HTTP响应不被修改。
ToolAttempt的HTTP序号从1开始，内部0表示首次调用前校验/拒绝；公开投影为1并保留原outcome、NONE副作用和纠正关联，不宣称发生了外部IO。模型审计SUCCEEDED/FAILED投影为契约SUCCESS/ERROR。
15项新增验证，全量151测试/33类通过；T073和T093–T095整体出口未完成，真实联调缺口不变。[证据](evidence/US5-third-batch-local.md)。


## US5 第四批 CLI 装配与验证（2026-11-13）

T090完成，止于十二项CLI。OryxCommandLine在Spring之前解析轻命令，boot注入core QueryPort的BootCliQueries：session list用SqliteReadOnlyQueries只读连接及UUID游标，不创建、迁移或修复数据库。channel-cli使用Boot管理的Jackson做明确公开字段投影，core不引入Jackson/Spring，依赖方向不变。
Profile create拒绝覆盖；delete在持有独占工作区锁期间将目录移到agent-archive，历史数据库不变。show展示脱敏当前frontmatter/body，对比实际启动摘要，区分配置重启与正文下一任务热读。
启动宿主在完成真实工具注册后发布脱敏cli-view.json，绑定workspace UUID/PID/进程启动时刻；轻查询校验仍有运行锁，读实际注册工具与Agent摘要。启动前移除旧投影，关闭时删除；不能读取有效投影时明确不可用，不能当作离线静态结果。该文件仅诊断，不取代AGENT.md/runtime.yaml权威。
离线工具来自各工具的staticDescriptor和正式ToolRetryPolicy分类，native schema由同一Registrar生成；不实例化HTTP、Mem0、MCP客户端。MCP标记NOT_DISCOVERED，离线不声称运行可用。
CLI等待使用独立虚拟线程交接引擎，入口60秒预算覆盖接收任务与最终记录；观察Handle后到期封闭动作许可，保留已知终态/UNKNOWN记录，后台收尾不被一次响应替代。执行投影与HTTP mapper字段一致；输出0/2/3/4/5/6/7，stdout为结果、stderr为日志。chat不自动调度；serve/gateway仍提供HTTP及配置调度，不宣称入站IM或管理台已交付。
15项本批测试、全量166测试/35类通过；T073整体及真实验收继续开放。[证据](evidence/US5-fourth-batch-local.md)、[审查](evidence/US5-fourth-batch-review.md)。

## US5 第五批管理台装配与验证（2026-11-13）

T091/T092完成。web的static/admin/直接交付原生HTML、CSS和ES modules，AdminController只处理/admin规范地址与静态首页转发；api.js固定同源/api/v1，复用现有正式后端，不新增业务API或Node依赖，不改website。
read-views提供Agent、会话历史、CORE/ARCHIVAL共享记忆、调度/触发历史和执行审计。统一UUID游标分页保留失败页游标、去重且不覆盖其他视图；所有外部正文走文本节点。语义表单、原生dialog及键盘焦点支持，用户标识显式保存、非登录。
control-views每次写入前GET核对READY/当前incident；全局在途闸门防重复提交，POST/PUT不自动重试，刷新和后台轮询只GET。UNKNOWN与INCOMPLETE优先显示核查，纠正成功保留旧失败及关联。恢复要求当前故障、零在途、勾选和说明；存储修复后的可写性由后端恢复接口重新检查，不能用暂停时的storageWritable旧快照把恢复入口永久禁用。
本地测试服务采用正式Boot/SQLite与明确标记的回环模型/接收器，测试辅助端点仅存在于测试classpath/profile。浏览器查询/操作结果与API、实际SQLite记录一致，故障恢复不重放。全量168测试/36类通过；完整T073/T093–T095及真实联调仍开放。[证据](evidence/US5-fifth-batch-local.md)、[审查](evidence/US5-fifth-batch-review.md)。

## US5 第六批初次验收（历史失败快照，2026-11-13）

T093通过：新增独立发行JAR JVM，经正式HTTP/CLI/SQLite验证状态恢复和notify故障不重放。运行时与依赖不改；现有测试只追加综合验收标签，所有原断言保留。
T094未通过：从/v3/api-docs实测得到19操作、4输入schema，响应和约束元数据未满足44schema契约，185个比较项差异。失败测试保持默认local必跑；不能用静态契约替代实际生成结果。T095前置不满足，未进入完整CLI/浏览器重新验收；T073继续开放。
全量172项中171通过、1失败，0错误/跳过；并非构建成功。[证据](evidence/US5-sixth-batch-local.md)、[初次审查](evidence/US5-sixth-batch-initial-review.md)。


## US5 第六批续作补记

T094实际发布文档由MVC绑定、HttpContract响应元数据、实际ContractDtos record与Swagger约束生成，固定OpenAPI 3.0.3。泛型ApiResponse信封复用实际组件；不读取静态设计契约。生产行为未新增配置CRUD或其他入口。
T094综合71项、T095发行CLI三项/Chrome13组及人工恢复通过；全量176项/39类零失败/错误/跳过，T073阶段出口关闭。此前第六批失败证据仍保留；本批止于T095，T096–T104及十项真实缺口不变。见[evidence/US5-sixth-batch-continuation.md](evidence/US5-sixth-batch-continuation.md)。


## US5 第九批续轮实现（T101，2026-11-14）

运行计时显式跨HTTP/Agent工作线程传递，覆盖会话锁、历史、写队列、连接、事务、审计和模型/工具阶段。
会话生命周期按ID互斥；SQLite历史查询有界，原始消息完整保留。单写协调器合并已排队的短写，事务始终在单个写线程内，提交前不确认；严格256等待槽及总257容量，不延长原等待预算，不降低WAL/FULL持久化。
高频Session/Execution/Audit记录使用JPA事务连接上的绑定SQL，CPU准备移到入队前但仍在请求计时内；校验与失败暂停不变。Provider复用所属连接与序列化器，请求超时工厂独立，关闭注册表释放资源。
HTTP在途上限沿用第九批初次已调整的128。此前分批记录中的64是第三批历史值。
50ms仍是最大值上限。保留原服务端指标，并增加包含入口/客户端往返的保守上界门槛；只扣配置范围内的外部等待。短程不等于T101通过，完整结果见[续轮审查](evidence/US5-ninth-batch-continuation-review.md)。

## T101 提交与写队列继续定位（2026-11-16）

事务执行期间可接纳已排队工作，单组仍最多128、不等待凑批；确认在完整提交与资源释放之后。只等待持久结果，取消/中断不能提前释放已开始事务。参数化语句只在当前事务和写线程内复用，最多32条，结束关闭；不缓存记录、不降低WAL/FULL。新增SQL准备/绑定/执行和提交后唤醒分段，均计入原总耗时。

Provider的同步和未使用流式包装器共用同一个所属传输，避免每请求创建额外HttpClient；仍无流式入口，逐请求截止独立。审计脱敏使用原子发布的不可变秘密快照和共享Pattern，每次独立Matcher；保持相同遮蔽规则，避免只读投影争用全局锁。独立短程50ms门禁失败（最大322.241791ms），按授权未进入四小时；全量207项/44类本地回归通过。[本轮证据](evidence/US5-ninth-batch-commit-queue-review.md)。


## T101 读取、组内等待与提交后恢复（2026-11-16）

热读取通过既有有界SQLite池直接执行参数化投影，保留JPA写事务和全部审计；排他租约只缓存下一序号，提交确认后推进，跨租约/重启重新读取。物理写drain使用独立平台线程，避免被无关虚拟线程承载线程占满时饿死，仍只允许单写者。HTTP消息接收复用该读取路径；工具HTTP使用所属有界连接池，逐请求截止、取消和无隐式重试保持不变。共享记忆读取完整原子文件快照，写入仍跨实例串行化。

剖析发现测试服务同步证据写入的监视器等待也阻塞虚拟线程；改用可卸载等待的锁，JSON序列化、逐条写入/刷新仍在返回响应前完成，全部耗时仍计入。负载、外部等待扣除、50ms断言、SQLite FULL/WAL未改。最终短程271.953375ms失败，216项/50类本地回归通过；不启动新四小时、不关闭T101。[证据及限制](evidence/US5-ninth-batch-read-wakeup-review.md)。


## T101 模型传输、组内等待与线程恢复续轮（2026-11-16）

补齐传输/组内分段计时后，以真实HTTP测试复现Spring请求体序列化每请求分配平台线程；改用Provider所属虚拟线程执行器并随生命周期关闭。真实SQLite提交规则不变，新增组内前后等待只用于定位，不用于扣除。JFR显示JDK21测试HTTP服务读取请求体会占住运行端共用虚拟载体，负载驱动同步证据锁也造成竞争；两者各改用128线程/256队列的预启动平台池。100闭环客户端及创建探针、全部外部等待与同步证据均保留，未改变正式运行线程配置或审计语义。

最终无剖析器短程201.795500ms失败；221项/51类本地回归通过不能替代性能验收。T101未关闭，不启动新四小时。[本轮详细结果](evidence/US5-ninth-batch-transport-batch-review.md)优先于上文历史续轮状态；历史失败保留。
