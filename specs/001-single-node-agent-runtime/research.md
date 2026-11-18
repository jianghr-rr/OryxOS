# 研究与技术决策记录

日期：2026-11-12。输入为[已澄清规格](spec.md)、[宪章](../../.specify/memory/constitution.md)、
[技术方案](../../docs/TechnicalSolution.md)及个人仓库实际 POM。
本文记录设计选择；不把依赖解析成功写成业务已实现。官方实验仓库不作为版本或代码权威源。

## R01 — 保留个人项目基线，分别锁定新增库

**Decision**：保留 JDK 21、Spring Boot 3.5.16、Maven Wrapper 3.9.16；不升级 Boot 4。
父 POM 管理版本，各能力模块按需引用；不用所有模块都引入全部 starter。

| 坐标／组件 | 采用版本 | 归属与依据 |
| --- | --- | --- |
| org.springframework.boot:spring-boot-starter-parent | 3.5.16 | 现有父 POM；保留 |
| org.springframework.ai:spring-ai-bom | 1.1.8 | 父 POM import；provider 使用 spring-ai-openai |
| io.modelcontextprotocol.sdk:mcp-bom / mcp | 0.18.3 | tool；直接 SDK 坐标需独立 BOM，AI BOM 不直接管理它 |
| org.hibernate.orm:hibernate-core / hibernate-community-dialects | 6.6.53.Final | storage；两者跟随 Boot，不单独升级 |
| org.xerial:sqlite-jdbc | 3.51.3.0 | storage |
| info.picocli:picocli | 4.7.7 | cli |
| com.networknt:json-schema-validator | 1.5.9 | tool；离线 JSON Schema 校验 |
| org.apache.httpcomponents.client5:httpclient5 / httpcore5 | 5.5.2 / 5.3.6 | US2 tool最小HTTP GET，均取Boot BOM管理值 |
| org.springdoc:springdoc-openapi-starter-webmvc-ui | 2.9.1 | web；对应 Boot 3.5.16 |
| org.yaml:snakeyaml | 2.4 | core 配置读取，版本由 Boot 管理 |
| Spring Framework / Jackson / HikariCP / SLF4J | 6.2.19 / 2.21.4 / 6.3.3 / 2.0.18 | 全部使用 Boot 管理值 |
| Boot web、data-jpa、validation、test starters | 3.5.16 | 分别放 web、storage、需要校验处及测试 scope |
| Spring AI model 工具描述能力 | 1.1.8 | tool 的原生扩展 schema；不承担实际执行 |
| 管理台 | 浏览器原生 ES modules、HTML、CSS | 放 web 静态资源；首版不新增 Node 构建链 |

**Rationale**：AI 1.1.8 发布依赖的 Spring 6.2.19 与本项目一致；MCP 是 Java 17 字节码，
JDK 21 可加载；Springdoc 2.9.1 自身父 POM 就是 Boot 3.5.16。
SQLite 3.51.3 修复官方记录的 WAL-reset 数据损坏问题。
**Alternatives**：不选 AI 2.x／Boot 4；不为尚未使用的 Graph、Agent Framework 引入 Alibaba；
不使用旧 SQLite 3.51.2，也不让 community dialect 与 Hibernate core 版本分叉。

证据：[Boot BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/3.5.16/spring-boot-dependencies-3.5.16.pom)、
[AI tag POM](https://github.com/spring-projects/spring-ai/blob/v1.1.8/pom.xml)、
[AI 发布 POM](https://repo.maven.apache.org/maven2/org/springframework/ai/spring-ai-openai/1.1.8/spring-ai-openai-1.1.8.pom)、
[MCP BOM](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/sdk/mcp-bom/0.18.3/mcp-bom-0.18.3.pom)、
[Springdoc POM](https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi/2.9.1/springdoc-openapi-2.9.1.pom)、
[SQLite 修复说明](https://www.sqlite.org/releaselog/3_51_3.html)。

## R02 — 单次模型请求与完整协议消息

**Decision**：Provider 使用 Spring AI 1.1.8 的 OpenAiApi.chatCompletionEntity。
它只执行一次同步 HTTP 请求，不具备自动工具循环。各 Provider 有独立实例，
按配置名称映射；core 使用自己的消息类型，Spring AI DTO 留在 provider。

请求工厂封装 ChatCompletionRequest 的 record 构造器，不能使用不存在的 builder。
reasoningEffort 是显式字段；extraBody 通过 JsonAnyGetter 展开到请求顶层。
接收并持久化 assistant 的正文、tool_calls、ID、参数以及供应商要求的 reasoning_content。
最后一项仅作同供应商会话的协议续传资料，不进入长期记忆、普通日志、公开 API 或页面。

**Rationale**：1.1.8 OpenAiChatModel 在历史转换中把 reasoningContent 设为 null，
而当前 Kimi 要求完整回传。因此仅加“关闭自动工具执行”不足以完成真实 Kimi 工具任务。
低层 API 避免该丢字段路径，仍复用 Spring AI 的协议传输与工具 schema 能力。
若后续确实引入 OpenAiChatModel，默认与逐次请求均须设置 internalToolExecutionEnabled(false)；
不使用 ToolCallingManager.executeToolCalls、ChatClient 工具 advisor 或 MCP starter 自动 callbacks。

**Alternatives**：不使用无条件转纯文本的适配器；不靠换已下线模型绕过问题；
也不引入第二个 Agent 框架接管循环。

| 真实联调服务 | 核实时的模型示例 | 配置限制 |
| --- | --- | --- |
| DeepSeek | deepseek-flash | origin https://api.deepseek.com；path /chat/completions；最小工具链显式 thinking.type=disabled |
| Kimi | kimi-k3.1-pro | origin https://api.moonshot.cn；path /v1/chat/completions；不发送 thinking 或 temperature；可配置 reasoning_effort=low |

模型名是本次核实的配置示例，联调时仍须确认账号可用性；不把它们写成永久原则。
Kimi 当前始终 thinking，不能承诺每次真实任务必定在 60 秒内完成；超时规则仍按规格执行。

内部MCP全名含点号，不能直接作为模型函数名。Provider采用保守的64字符
字母/数字/下划线/连字符wireName与内部名称双向映射，并持久化原始名称用于历史回放。
依据：[DeepSeek函数名称约束](https://api-docs.deepseek.com/api/create-chat-completion/)。

证据：[低层调用和消息 DTO](https://github.com/spring-projects/spring-ai/blob/v1.1.8/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/api/OpenAiApi.java)、
[高层消息转换缺口](https://github.com/spring-projects/spring-ai/blob/v1.1.8/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java)、
[关闭自动执行选项](https://github.com/spring-projects/spring-ai/blob/v1.1.8/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatOptions.java)、
[DeepSeek 工具调用](https://api-docs.deepseek.com/guides/tool_calls/)、
[Kimi 模型列表](https://platform.kimi.com/docs/models)、
[Kimi 完整消息要求](https://platform.kimi.com/docs/guide/use-thinking-models)。

## R03 — 保留九模块，修正装配而不倒置依赖

**Decision**：保留 boot → cli → core/channel-cli。core 定义 RuntimeLauncher 和只读查询端口，
boot 实现并注入 CLI；CLI 解析、帮助及轻命令先于 Spring 上下文创建。
完整 12 项命令使用 boot 可执行 JAR，boot 无参数仍进入 serve，保留现有 HTTP 启动意图。
现有 OryxOsCli.main 的无参数／版本输出保留为独立薄 JAR 的入口；
它不因此成为完整依赖打包后的 CLI 发行物。完整命令从 boot 调用 CLI 的 execute 方法。

**Rationale**：当前 cli JAR 只写 Main-Class，没有打包第三方依赖；直接增加 cli → boot 会成环。
只读查询由 boot 注入 storage 的轻量 JDBC 查询适配器，不需为了 session list 启动 Spring。
core 不依赖 Spring AI、JPA 或任何其他业务模块；SnakeYAML 仅负责受控配置解析。
**Alternatives**：不删除已有入口，不用反射猜测 Boot main；不为了数量限制强行拆第十个模块，
本次也没有新增模块的必要。

证据：[实际父 POM](../../pom.xml)、[CLI POM](../../oryxos-cli/pom.xml)、
[Boot POM](../../oryxos-boot/pom.xml)、[Picocli](https://picocli.info/)、
[Boot 打包说明](https://docs.spring.io/spring-boot/3.5/maven-plugin/packaging.html)。

## R04 — SQLite 短事务和可恢复的故障暂停

**Decision**：SQLite 本地文件、WAL、synchronous=FULL、每连接 foreign_keys=ON、
busy_timeout=5000ms；单工作区运行进程锁；单写协调器、最多 4 个连接，读写使用短事务。
等待模型、工具、Mem0 期间不持有数据库事务。ddl-auto=none、关闭 OSIV 与二级缓存。
迁移使用递增版本 SQL 文件、校验和与 schema_migrations 表，不生成或运行迁移代码于本轮。

在动作之前提交 STARTED 审计意图；持久运行标记在开放执行前落盘，正常关闭且记录完整才清除。
保存失败立即关闭执行闸门；即使故障文件也写不出，之前的未闭合记录／运行标记仍能在重启时
阻止自动恢复。启动发现数据库不可读、未闭合尝试或故障标记，进入只提供诊断与可用查询的状态。
人工恢复先检查可写、登记核对说明、确认缺口，再开放后续新执行；不重放动作。

**Rationale**：SQLite 同时只有一个 writer，单纯加连接数不能解决写竞争；
向已经写失败的同一个库补写暂停标志并不可靠。
**Alternatives**：不以 H2 测试替代 SQLite，不用 ddl-auto=update，不把自动恢复可写当人工恢复确认。
JPA 社区方言没有 Hibernate 官方 CI 保证，须用真实 SQLite 验证其生命周期和事务。

证据：[community dialect POM](https://repo.maven.apache.org/maven2/org/hibernate/orm/hibernate-community-dialects/6.6.53.Final/hibernate-community-dialects-6.6.53.Final.pom)、
[方言维护说明](https://docs.hibernate.org/orm/6.6/dialect/)、
[WAL](https://www.sqlite.org/wal.html)、[事务](https://www.sqlite.org/lang_transaction.html)、
[PRAGMA](https://www.sqlite.org/pragma.html)。

## R05 — 三档记忆遵循已确认的差异契约

**Decision**：md 默认；sqlite 原文条目；mem0 模式在本地保留 CORE 原文和显式保存的来源账本，
仅把 ARCHIVAL 交给自托管 Mem0 提炼与检索。增强服务锁 Mem0 OSS v2.2.1，
commit 94c3fe9f238f3dbf29c9ce98643bd71eb13077cd；实施时生成固定依赖锁和容器 digest，
禁用 MEM0_TELEMETRY，显式配置其模型、embedding 和持久卷，不运行上游开发 Compose 的滚动依赖。

所有 Agent 共用 workspace UUID 作为 Mem0 user_id，不以实际用户、Agent 或 Session 分区。
POST /memories 仅传本次明确保存内容；返回 IDs 后在剩余时限内回读确认，
本地来源与 remote IDs 关联提交完成后才能报告归档保存成功。空 results、缺 ID 或回读不全不算成功；
超时结果不确定，不能自动重放。load 每次读取本地已确认来源与 CORE；recall 实时调用 /search，
只接受匹配工作区及已确认来源的远端记录。

上述远端确认只适用于ARCHIVAL；CORE本地原文提交即可确认。可检测的失败具体为：
空results、缺ID、返回ID回读不全或归属不符。Mem0未提供预期事实总数，无法检测所有提炼遗漏；
回读成功不等于全部原始事实已被索引。收据区分源原文保存与投影状态，不宣称语义覆盖已证明；
本地账本保全原始事实，增强检索的准确性与覆盖通过固定语料核对。

**Rationale**：官方 GET /memories 默认限量，不能保证 CORE 全量；同步 add 的 HTTP 200
也不保证提炼出了内容。本地来源账本保证原始事实、共享归属与失败证据不被增强层掩盖。
语义结果是否准确用固定正反例验收，不声称元数据校验可以证明任意自然语言事实正确。
**Alternatives**：不使用托管 Platform 的异步 event_id 协议，不把全部历史发给增强服务，
不把远端前 20／1000 条当作完整记忆。增强模式外部服务是显式选择的前提，不扩散到默认模式。

证据：[固定版本](https://github.com/mem0ai/mem0/releases/tag/v2.2.1)、
[REST 源码](https://github.com/mem0ai/mem0/blob/v2.2.1/server/main.py)、
[记忆实现](https://github.com/mem0ai/mem0/blob/v2.2.1/mem0/memory/main.py)、
[遥测开关](https://github.com/mem0ai/mem0/blob/v2.2.1/mem0/memory/telemetry.py)、
[REST 文档](https://docs.mem0.ai/open-source/features/rest-api)。

## R06 — MCP 与 Schema 校验不能绕过资源边界

**Decision**：使用 MCP 0.18.3 同步 client、stdio transport；初始化、分页发现、调用与退出都显式管理。
ServerParameters 和 JacksonMcpJsonMapper 传入 StdioClientTransport。
工具仅由 ToolExecutor 调用；不启用 sampling／elicitation，也不自动重连并重放未知结果。
MCP 工具名称采用 mcp.<server>.<tool>，与内置工具区分。

SDK 的 ProcessBuilder 默认继承父环境，仅 putAll 参数不能删除已有秘密值。
包装受保护的 getProcessBuilder，先清空环境，再放最小运行变量和显式允许的 secret 引用；
stderr 走脱敏与限量日志。Shell 子进程执行同样的环境收敛。
NetworkNT 1.5.9 使用 DisallowSchemaLoader／本地映射，禁止远程 $ref，
限制输入大小、嵌套深度及 schema 复杂度，不引入 optional Joni／GraalJS。

**Rationale**：工具执行前的 schema 校验和 MCP 启动本身也可能产生外连或泄漏环境变量。
**Alternatives**：不使用 SDK 2.x／Jackson 3，不假设“仅传 env 白名单”已经清空父环境。

证据：[stdio transport](https://github.com/modelcontextprotocol/java-sdk/blob/v0.18.3/mcp-core/src/main/java/io/modelcontextprotocol/client/transport/StdioClientTransport.java)、
[同步 client](https://github.com/modelcontextprotocol/java-sdk/blob/v0.18.3/mcp-core/src/main/java/io/modelcontextprotocol/client/McpSyncClient.java)、
[Schema POM](https://repo.maven.apache.org/maven2/com/networknt/json-schema-validator/1.5.9/json-schema-validator-1.5.9.pom)、
[离线加载控制](https://github.com/networknt/json-schema-validator/blob/1.5.9/src/main/java/com/networknt/schema/resource/DisallowSchemaLoader.java)。

## R07 — 60 秒是执行截止，不只是网页等待截止

**Decision**：入口建立 executionId 和单调时钟 deadline；HTTP 用 MVC DeferredResult 配合虚拟线程执行
同步业务链。超时先关闭该执行的新动作许可，再返回 504；每次模型、工具、重试及工具内的外部子请求
在发起前重新检查 deadline 和工作区闸门。迟到结果只能补充已发出步骤的记录。
不将 Servlet 超时／Future.cancel 当作外部动作已撤销的证据。

**Rationale**：MVC 允许应用自行决定在哪个线程生成单次返回结果；这不是改成 WebFlux，
也不把业务改成超时后继续跑的异步任务。
**Alternatives**：不只在前端设置 fetch 超时，不用线程中断作为唯一边界，不给已超时任务重新通知。

证据：[Spring MVC 6.2 异步返回机制](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html)；
执行许可、取消与迟到记录是本项目的设计决定，不是该框架自动提供的保证。

## R08 — 管理台与配置源保持简单

**Decision**：web 模块静态管理页使用同源 HTTP API；查询 Agent、会话、记忆、调度和执行记录，
提供手动运行、启停、故障确认恢复。默认监听 127.0.0.1，跨源默认关闭。
Provider／白名单来自 runtime.yaml；通知来自 AGENT.md；数据库只保存状态和审计。
页面不编辑这些定义。website/ 继续作为独立文档站，不充当业务管理台。

**Rationale**：已有 VitePress 网站不证明管理能力存在；原生页面可先交付有限管理操作，
不用触碰 ACSP 的 Node 环境。旧技术文档开放所有 CORS 的示例不适合作为无认证控制接口默认值。
**Alternatives**：不引入另一套 Provider／通知数据库注册表；不增加 Agent CRUD 或配置热更新。

## 本轮兼容性证据与实际验证边界

以下兼容性证据来自初次plan阶段；本次评审修订没有变更依赖版本或重复运行应用构建。

已在临时目录、独立 Maven settings/global-settings 和独立本地仓库完成：
1. 项目 Wrapper 3.9.16 + 只读借用已有隔离 JDK 21.0.13 的版本检查；
2. 对个人仓库父 POM 执行 help:effective-pom；
3. 用隔离 probe POM 组合表中依赖，dependency:tree 成功，无仓库业务 POM 修改；
4. 官方 Spring AI JAR 的 javap 检查，确认 reasoningContent、同步调用、关闭自动执行选项存在；
5. 官方 POM、tag 源码与 MCP／SQLite JAR 最低字节码要求核对；
6. 初稿OpenAPI 3.0.3经openapi-spec-validator 0.7.2校验通过，含17个路径、19个操作、42个schema；
   九份设计工件的相对链接、本地 schema 引用、文档结构及 FR-001–041／SC-001–011 映射检查通过。

解析结果包括 AI 1.1.8、MCP 0.18.3、Spring 6.2.19、Hibernate 两件 6.6.53.Final、
Jackson 2.21.4、SQLite 3.51.3.0、Picocli 4.7.7、SnakeYAML 2.4、
Springdoc 2.9.1、Schema Validator 1.5.9。没有转成 Boot 4／Jackson 3。

未执行应用构建、JPA 生命周期测试、真实模型、通知、MCP 或 Mem0 联调。
依赖可解析和 API 存在并不等于完整运行兼容；这些验证列在[验证方案](verification.md)。
所有研究问题已形成上述选择；剩余事项是实施验证和人工方案审阅，不是未回答的产品问题。

## R09 — 技术评审补齐与维护者决定（2026-11-12）

**Decision**：维护者逐项确认：首版chat与HTTP服务互斥；未知副作用立即结束本次任务；
工作区统一时区默认Asia/Shanghai；确有证据的参数纠正允许成功但保留原错误。
引擎按受控参数修正窗口建立关联，不能仅凭模型自述判定业务已完成。
MemoryService经SessionHistoryPort委托SessionManager，并与长期记忆后端共同提供一次上下文视图；
sessions创建即保存provider_name/model；配置错误按工作区、Provider、Agent、MCP定义隔离；
RuntimeState内嵌currentIncident，包含动作证据、受影响执行及缺口，故障时不假装信息完整。

**Rationale**：防止模型重发绕过工具重试禁令；避免上下文职责分散、空会话重启丢失模型绑定、
一个Provider拖垮其他Agent、恢复页面无资料可核对，以及手动和定时任务对“今天”理解不同。

**Alternatives**：未采用首版远程CLI模式、未知副作用后继续只读任务、各Agent独立时区、
以及“任何历史错误都永久使执行为PARTIAL”的显示规则；这些均有本次明确问答依据。
不引入语义相似度或模型自评来清除旧失败，无法建立可靠纠正关联时仍保留PARTIAL。
不因此修改宪章原则、记忆共享归属、60秒手动截止或记录故障后的人工恢复要求。

验证安排见[RV-01至RV-06](verification.md#本次评审补齐的验证场景)。

本次修订的OpenAPI经同一0.7.2校验器离线校验通过，仍为17个路径、19个操作，
增加故障详情类型后共44个schema。文档链接、契约引用、必填字段和41项FR／11项SC映射检查通过。
这些检查仅验证设计材料；RV行为、编译及真实联调尚未执行。


## R10 — US2 最小HTTP工具的重试边界

**Decision**：tool模块使用Boot已管理的Apache HttpClient 5.5.2（HttpCore 5.3.6）；不升级父BOM。显式调用`disableAutomaticRetries`、`disableRedirectHandling`、`disableCookieManagement`、`disableAuthCaching`与`disableContentCompression`，不读取系统代理。每次工具尝试和每次重定向都经过ExecutionContext许可；统一重试仅由ToolExecutor决定。

**依据**：Apache的[HttpClientBuilder官方API](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.html)说明关闭自动重试与自动重定向的方法。官网5.5文档地址目前重定向5.6，因此不以在线页面推断项目版本；本次实际5.5.2编译、断连服务器请求计数及发行JAR中的依赖版本共同验证使用的API与零隐式重试。没有更改US1的Provider传输策略。

**实测**：`ToolSafetyContractTest`验证断连只收到一次GET；503由统一执行器显式请求4次，401只请求1次；每次STARTED与结果成对记录；302到未授权目标时该目标计数0；重定向最多3跳、响应>64KiB失败、读取截止取消。见[US2-local](evidence/US2-local.md)。

Schema校验使用NetworkNT 1.5.9的自定义拒绝加载器，阻断外部$ref/$dynamicRef/$recursiveRef/$id，元Schema仅允许已打包支持的方言；测试确认校验过程中外部接收器计数0。JSON深度64、Schema与参数各64KiB的既有约束保持不变。

## R11 — US3 实施核验补记

沿用R05的Mem0 OSS2.2.1/commit，未改产品决定。源码压缩包SHA、Python锁及两个基础镜像manifest digest见
[container-lock.json](../../tools/mem0/container-lock.json)。Python锁由隔离uv cache、Python3.12/linux amd64目标解析，
安装使用--require-hashes。应用镜像尚未build/inspect，applicationDigest明确为null；Docker不可用不虚构成功值。
服务采用固定commit中的/memories、/memories/{id}、/search与X-API-Key，没有/v1前缀；启动前运行上游Alembic迁移。
显式提炼/embedding模型、API/JWT鉴权、PostgreSQL/history卷和关闭telemetry的配置见[服务说明](../../tools/mem0/README.md)。

MD/SQLite字面检索使用NFC加固定Unicode17.0.0默认完整case folding（C/F），不依赖默认Locale或简单toLowerCase。
数据取自[Unicode官方CaseFolding](https://www.unicode.org/Public/17.0.0/ucd/CaseFolding.txt)，
原文件SHA256为`ff8d8fefbf123574205085d6714c36149eb946d717a0c585c27f0f4ef58c4183`；随资源保留官方header与
[许可证](https://www.unicode.org/license.txt)。该折叠仅用于匹配，保存/CORE注入原文保持不变。


## R12 — US4 实施核验补记

- SDK仍锁定0.18.3，Spring AI1.1.8、NetworkNT1.5.9、HttpClient5.5.2/HttpCore5.3.6及POM均未改版。
- [SDK StdioClientTransport源码](https://raw.githubusercontent.com/modelcontextprotocol/java-sdk/v0.18.3/mcp-core/src/main/java/io/modelcontextprotocol/client/transport/StdioClientTransport.java)
  的默认ProcessBuilder会继承环境，且默认按行读取/异常日志不足以满足本项目流限制。SanitizedStdioTransport覆盖进程创建与管道IO，
  保留SDK JSON-RPC/session与同步客户端，协议行1MiB、stderr64KiB、禁止原始报文错误日志；只传最小环境与显式引用。
- 实测发现SDK默认JSON Schema实现需要当前NetworkNT1.5.9不存在的Dialect API。通过SDK的jsonSchemaValidator扩展点
  复用现有ToolArgumentValidator，完全离线并在SDK DTO映射前预检原始Schema；禁用callTool Schema缓存的额外发现请求。
  该修正不更换依赖版本，也不回避Schema校验。实际两页发现/调用/远程ref拒绝/断连/截止/强制关闭均通过。
- [Spring AI注解工具元数据源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/tool/method/MethodToolCallbackProvider.java)
  仅用于确认注解/Schema生成方式；实现使用ToolDefinitions.from(Method)，自行包装OryxTool并由ToolExecutor调用，不接入框架自动执行。
- MCP0.18.3内部使用Reactor，但对OryxOS仍暴露同步客户端；没有将业务执行模型改为响应式。
- 实测102个本地测试通过；真实通知和可信已配置MCP仍缺前提，不以fixture结果冒充。详见evidence/US4-*.md。
