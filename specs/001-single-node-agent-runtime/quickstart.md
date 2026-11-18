# Quickstart 与验收运行指南

**当前已完成F0、US1–US4本地实现和US5 T073–T097及T102新使用者上手验证（7.22s，使用回环模型桩，证据见[US5-onboarding](evidence/US5-onboarding.md)）；第九批 Slice 5–6 已将短程内部最大从201.80ms降至112.14ms（`500/500`成功、`0`违例、完整SQLite审计通过、`322`项本地`verify`通过），并按用户确认将压测时长改为2分钟（`120s` / `PT2M`），但因仍超50ms按路线C止损保持T101未勾选、未启动`kernel-soak`（证据见[Slice 5–6 排查与止损证据](evidence/US5-ninth-batch-slice6-review.md)）。T027/T028、T042/T044、T051/T057/T058、T070/T071/T072及T098–T100的真实前提/出口缺口保持开放，T103–T104待全部前置关闭后进入。**

命令只针对个人仓库。禁止用tools/course/lesson-02/run.sh构建本项目。

## 1. 前提与个人项目入口

需要JDK21、本项目Maven Wrapper、可用本地磁盘。真实联调另需DeepSeek和Kimi配置、
一个可核对接收的Webhook，以及被允许的数据源／脚本。
增强记忆另需research.md指定的自托管Mem0和其持久化、模型、embedding前提；
默认md/sqlite不要求启动这些服务。

F0已交付：
- tools/dev/mvnw：从ORYXOS_JAVA_HOME选择JDK，核验21；固定到脚本对应的个人仓库根，
  调用本仓库mvnw，使用个人项目独立Maven settings与缓存，不继承ACSP镜像／认证配置；
- tools/dev/oryxos：使用同一JDK运行本仓库boot发行JAR，透传--root与命令；
- 构建入口缺JDK时报错并给设置方法，不自行安装或改全局JAVA_HOME/PATH；
- 构建/启动时显示实际仓库根、JDK版本和工作区，避免误入官方实验仓库。

下文假定ORYXOS_JAVA_HOME已经在当前终端指向用户选择的JDK21。已验证的本机路径是 `/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home`，仅只读复用JDK；个人缓存和构建仍独立。
本轮兼容性审计只读借用了现有隔离JDK，入口没有硬编码课程安装路径。

~~~bash
ORYXOS_REPO=/Users/jianghaoran/github/OryxOS
"$ORYXOS_REPO/tools/dev/mvnw" --version
"$ORYXOS_REPO/tools/dev/mvnw" clean verify
"$ORYXOS_REPO/tools/dev/oryxos" --version
"$ORYXOS_REPO/tools/dev/oryxos" --help
~~~

预期：JDK21、Maven3.9.16；全部模块构建；帮助和版本不启动Spring、不监听8080、
不要求真实模型凭证。F0为已完成的T001–T018；可运行以下发现检查，并核对报告同时包含
EntryPointContractTest、TestDiscoveryIT及仅匹配*IT的DiscoverySuffixIT，缺IT/跳过/旧报告的负例检查也须通过：

~~~bash
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=f0-discovery
~~~

SQLite生命周期风险已在T017验证，Provider本地协议风险已在T026验证。F0入口与US1独立JUnit演示可用；
chat的真实主入口可通过us2-loop测试演示；默认发行chat已可运行；serve/gateway提供共用运行宿主，业务API仍不可用。命令和真实前提见[个人工具说明](../../tools/dev/README.md#us2-react-验证)。

F0完整发行验收（临时工作区、随机端口，自动结束进程）：

~~~bash
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=f0-foundation
python3 "$ORYXOS_REPO/tools/dev/test_report_gate.py"
python3 "$ORYXOS_REPO/tools/dev/verify-foundation.py"
~~~

F0当时的serve只验证装配；第三批已提供业务HTTP。DB/配置错误可从health/runtime/state及启动日志查看，
status查询不创建数据库，明确标注数据库健康未检查。正式故障详情和人工恢复HTTP及页面已完成本地验证。

## 2. 先完成不需要真实凭证的验证

当前可运行 `f0-discovery`、`f0-foundation`、`us1-contract`、`us2-loop`、`deadline-boundary`、`us3-memory`、`us4-tools`、`storage-faults`、`restart-recovery`；`us5-http`、`us5-cli-process`及`kernel-local`也已登记可运行。命令使用Surefire的groups选择，并遵守
[测试执行契约](verification.md#测试执行契约)：test阶段同时发现普通Test和*IT.java，verify不重复执行。
个人入口在本次报告中核对所选分组的必跑类/用例；漏跑或跳过不能算通过，未知/尚未登记组明确失败。
默认local模式排除真实联调与性能分组。测试必须使用临时工作区、回环HTTP替身、
真实临时SQLite及可控stdio进程，禁止读写开发者真实工作区或调用公网服务。

~~~bash
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=us1-contract
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=us2-loop
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=us3-memory
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=us4-tools
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=us5-http
"$ORYXOS_REPO/tools/dev/mvnw" verify -Dgroups=kernel-local
~~~

| 标签 | 应看到的证明 |
| --- | --- |
| us1-contract | 两个假服务不会串用；工具调用结构与reasoning_content往返；不支持参数不发送；Provider不执行工具 |
| us2-loop | 指定工具结果进入下一次请求、只执行一次、多个工具按序、10轮终止、消息配对 |
| us3-memory | CORE原文、ARCHIVAL预算、跨Agent共享、身份历史隔离、并发保存不丢；假Mem0空结果／读回失败不报成功 |
| us4-tools | 九工具成功与适用拒绝；路径/symlink、命令、域名、通知ack；MCP分页/超时/关闭；子进程不继承无关秘密 |
| us5-http | 十项基础API与管理接口、输入边界、超时停止新动作、故障暂停和人工恢复、分页与重启 |
| kernel-local | 从真实HTTP/CLI入口串起来；各服务仍是假服务，报告不得写成公网联调通过 |

业务fixture以后续故事任务为准；F0已包含临时工作区、回环HTTP与真实SQLite的基础测试。
至少包含：固定天气、科技新闻及来源、GitHub脚本数据、捕获Webhook、延迟响应、故障写入、
MCP工具发现与调用、Mem0正反例。每项提供输入和预期副作用计数。

## 3. 创建独立联调工作区

~~~bash
ORYXOS_WS="$(mktemp -d "${TMPDIR:-/tmp}/oryxos-demo.XXXXXX")"
"$ORYXOS_REPO/tools/dev/oryxos" --root "$ORYXOS_WS" init
"$ORYXOS_REPO/tools/dev/oryxos" --root "$ORYXOS_WS" init
"$ORYXOS_REPO/tools/dev/oryxos" --root "$ORYXOS_WS" status
~~~

预期：第二次init不覆盖第一次模板或用户编辑；显示数据库路径为该根/oryxos.db。
按[配置契约](contracts/configuration.md)编辑工作区文件并配置秘密引用。
不要把真实key写进本规格、测试fixture、命令行参数或提交文件。

准备两个模型Agent及三个整合Agent；同一Agent可同时承担模型连通验证。
先确认Provider名称、模型可用性、工具白名单、通知目标ack、城市与数据来源。
确认runtime.yaml的timezone，默认Asia/Shanghai；首版所有Agent和定时条目使用同一业务时区。

| Agent | 核心资源 | 必须允许的能力 |
| --- | --- | --- |
| daily-weather | AGENT.md正文、已配置城市和天气来源 | http_get、notify |
| daily-tech-digest | AGENT.md + skills/digest-format.md，偏好“关注AI和芯片” | read_file、http_get或显式MCP工具、save_memory、recall_memory、notify |
| github-daily | AGENT.md + scripts/github_trending.py，三个栏目 | shell、recall_memory、notify；脚本及解释器均获准 |
| kimi-check | 指定Kimi模型的简短任务 | 最小可核对工具，例如read_file |

三个场景的Agent定义和测试数据将在US5加入示例材料；不把业务写进ReAct引擎。
配置变更需重启；正文变更在下一次上下文组装生效。

## 4. HTTP、会话与管理台

在一个终端启动，保持运行：

~~~bash
"$ORYXOS_REPO/tools/dev/oryxos" --root "$ORYXOS_WS" serve
~~~

另一个终端设置地址与该工作区对应的用户标识：
当前服务运行期间通过HTTP或同源`/admin/`提交任务；同工作区再启动chat会明确返回忙碌。
若需本地终端交互，先正常停止serve，再运行chat；轻量查询不受此运行模式互斥限制。

~~~bash
ORYXOS_API=http://127.0.0.1:8080/api/v1
curl -sS "$ORYXOS_API/health"
curl -sS "$ORYXOS_API/profiles"
curl -sS -X POST "$ORYXOS_API/sessions"   -H 'Content-Type: application/json' -H 'X-Oryx-User-Id: reviewer'   -d '{"agentName":"daily-tech-digest"}'
~~~

从响应data.id复制会话ID，再发任务：

~~~bash
ORYXOS_SESSION_ID='<上一步返回的ID>'
curl -sS -X POST "$ORYXOS_API/sessions/$ORYXOS_SESSION_ID/messages"   -H 'Content-Type: application/json' -H 'X-Oryx-User-Id: reviewer'   -d '{"content":"请记住：我关注AI和芯片。然后说明你实际保存的内容。"}'
curl -sS "$ORYXOS_API/sessions/$ORYXOS_SESSION_ID"
curl -sS "$ORYXOS_API/memory"
~~~

检查实际save_memory成功记录，不能只相信最终回复“记住了”。
换新会话及另一个Agent使用同一偏好；换user/入口不能自动带入旧对话。
用不同X-Oryx-User-Id续聊原会话应409；只读管理查询可以显示工作区历史，这不代表已有认证。

浏览器打开http://127.0.0.1:8080/admin/，检查真实列表、加载/空/错误状态、执行详情与共享记忆标识。
查看/swagger-ui/index.html及/v3/api-docs，并与[OpenAPI契约](contracts/http-api.json)比对。
不要把静态页面截图当作后端操作通过。

## 5. 真实模型和通知联调

真实测试类同时带real-integration及专用标签。准备好相应工作区、凭证和维护者选定的测试目标后，
分别显式运行需要的组；下面是不同联调任务的独立命令，不要求一次执行全部：

~~~bash
"$ORYXOS_REPO/tools/dev/mvnw" test -Doryxos.tests.mode=real -Dgroups=real-provider
"$ORYXOS_REPO/tools/dev/mvnw" test -Doryxos.tests.mode=real -Dgroups=real-provider-tools
"$ORYXOS_REPO/tools/dev/mvnw" test -Doryxos.tests.mode=real -Dgroups=real-notify
"$ORYXOS_REPO/tools/dev/mvnw" test -Doryxos.tests.mode=real -Dgroups=real-mcp
~~~

模式与组不匹配会在运行前拒绝；缺前提须非零退出并报告未验证，不能靠skip获得成功。
普通test/verify不会启用这些组；真实命令仍排除kernel-soak。


分别让DeepSeek和Kimi完成：
1. 一次普通文本响应，核对审计中的实际provider/model；
2. 一次必须使用已知工具数据的任务，核对工具call id、真实结果和后续响应；
3. 失效凭证、不可用模型的失败路径，确认不切换另一Provider；
4. Kimi多轮工具消息必须完整回传协议续传字段，公开日志和API不能泄漏这些字段。

向受控Webhook发一条可辨认的通知，在接收端和执行记录核对同一内容；
模拟业务ack拒收、回包丢失以及发送后审计失败，分别显示失败／未知／记录异常，
不能自动再发一次。真实通知测试只在维护者明确选择的测试目标进行。
回包丢失但尚未到60秒时也必须立即结束本次任务，提示“结果不确定，请先核查”；
核对后续模型、工具及重试计数为0，不能让模型下一轮换个调用ID重新发送。

真实联调记录模型名、脱敏配置、时间、执行ID、接收证据及未验证项；
没有额度／凭证／网络时标“未验证”，不能把上一节替身通过替代它。

## 6. 三个整合场景

每个Agent各完成一次人工invoke和一次真实定时触发，总计至少六次成功执行；
另分别制造天气查询失败、新闻来源缺失、脚本失败等失败样本。

~~~bash
curl -sS -X POST "$ORYXOS_API/agents/daily-weather/invoke"   -H 'Content-Type: application/json' -H 'X-Oryx-User-Id: reviewer'   -d '{"content":"查询配置城市的天气，给出穿衣建议并发送到daily。"}'
curl -sS "$ORYXOS_API/schedules"
~~~

测试工作区可将cron设为下一分钟触发，重启加载；看到一次结果后通过enabled接口停用，
避免反复发送。停用后自动不跑，但手动run仍可执行且不改变enabled。
重叠触发必须跳过；重启不补跑错过的时间。
日报必须列出可核对的数据来源；脚本输出来自真实脚本执行，不把脚本全文当作结果。

## 7. 故障与恢复验证

~~~bash
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=deadline-boundary
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=storage-faults
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=restart-recovery
~~~

使用fixture注入故障，不对真实工作区删库或改权限。
deadline测试以可控时钟验证边界，另保留真实HTTP等待到60秒的用例；
迟到响应不能触发新模型、工具、通知或重试。

故障后GET /runtime/state显示incident。确认已核对记录并修复存储后，按
[人工恢复流程](contracts/cli-admin.md)提交恢复，未确认前即使重启也不能执行新任务。
核对currentIncident中的受影响执行、已知/未知动作、缺口和证据来源；detailsComplete=false
表示资料不完整，空数组不能解读为没有动作。允许如实确认无法补齐的记录，不伪造完整性。
复查通知次数仍为1，旧动作不重放、漏掉的cron不补跑，无法补齐记录保留缺口说明。

## 8. 增强记忆与性能单独验证

Mem0服务按[research R05](research.md)固定版本和依赖，关闭遥测，使用独立测试卷。
真实增强测试：显式保存“关注AI芯片”，同义检索“人工智能硬件偏好”命中，
“饮食忌口”无匹配；CORE原文对照；不同Agent共享、不同workspace UUID不串；重启后可读。
不能用假Mem0的固定响应替代真实语义检索证据。独立运行：

~~~bash
"$ORYXOS_REPO/tools/dev/mvnw" test -Doryxos.tests.mode=real -Dgroups=real-mem0
~~~

性能验证显式选择soak模式，继续禁止真实供应商调用：

~~~bash
"$ORYXOS_REPO/tools/dev/mvnw" verify -Doryxos.tests.mode=soak -Dgroups=kernel-soak   -Doryxos.test.agents=10 -Doryxos.test.sessions=100   -Doryxos.test.duration=PT4H
~~~

性能标签默认不随普通test运行；此处使用可控模型/工具延迟，不消耗真实模型额度。
报告硬件、JVM、磁盘、请求节奏、数据规模、完整审计写入量、P99创建会话时间、
单任务内部耗时和最大值。内部排队与DB提交不能从耗时中扣掉。
4小时中没有串会话、丢失已确认写入或同任务重叠才通过；数值不达标就记录失败，
不能降低审计保证或把目标改成估算。

第九批首次完整运行四小时：56599次任务成功，SQLite完整审计核对通过；创建P99=78.538541ms，内部最大=2184.36475ms，T101因后者超过50ms保持未完成。短程夹具可用`test -Dgroups=kernel-soak-harness`单独检查，不能替代上述完整命令。条件、结果和原始数据见[性能证据](evidence/US5-performance.md)。

## 9. 本次评审修订的补充检查

按[verification的RV-01至RV-06](verification.md#本次评审补齐的验证场景)分别验证：
未知副作用立即终止、上下文历史仅一次、空会话模型快照、Provider局部错误隔离、
故障详情可核查、统一时区和错误纠正显示。
参数错误纠正后应能看到原失败与成功调用的关联；没有可靠关联仍显示部分完成。
这些均为待实施场景，本轮没有生成或执行其业务测试。


## US2 可运行验证

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp test -Dgroups=us2-loop
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp test -Dgroups=deadline-boundary
```

us2-loop的28项用例包括真实CLI解析入口→测试专用Spring装配→实际Provider适配→回环模型/天气服务；使用真实SessionManager，临时Store只存取。该历史US2用例使用受控参考/脚本输出夹具；US4已新增真实文件/脚本和MCP的CLI整合。默认发行没有临时存储降级。
证据：[US2-local](evidence/US2-local.md)、[截止与纠正](evidence/RV01-RV06-loop.md)、[真实工具前提](evidence/US2-real-tools.md)。

## US3 可运行验证

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp test -Dgroups=us3-memory
```

20项本地契约/整合测试：实际MD、SQLite/JPA与工作区文件，可控Mem0 HTTP；没有真实模型或真实语义结论。
默认chat仍等待US5正式Session/Execution存储。真实Mem0需要个人Docker服务、已核实镜像、模型与embedding配置/凭证，
并显式允许测试容器重启；完整命令见[隔离服务说明](../../tools/mem0/README.md)。
证据：[本地](evidence/US3-local.md)、[真实前提](evidence/US3-real-mem0.md)、[审查](evidence/US3-review.md)。


## US4 本地可运行验证

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp test -Dgroups=us4-tools
./tools/dev/mvnw -B -ntp clean verify
```

us4-tools登记21项，使用真实临时文件、进程、回环HTTP/Webhook和SDK连接的可控stdio服务。
模型脚本与临时Session/Execution Store只用于本地CLI整合；默认chat仍NOT_READY。
完整九工具成功/拒绝矩阵见[US4-local](evidence/US4-local.md)。
真实入口需另提供明确目标和配置，上方real组示意命令不具备全部前提：
[通知前提与命令](evidence/US4-real-notify.md)、[可信MCP前提与命令](evidence/US4-configured-mcp.md)。
T070/T071/T072及此前七项缺口继续未完成；本批不进入US5。

## US5 第一批：现在运行普通 chat

前文各US小节的NOT_READY描述为当时批次状态；当前生产端口已齐备。先完成构建，创建独立工作区：

```bash
cd /Users/jianghaoran/github/OryxOS
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp clean verify
./tools/dev/oryxos --root /absolute/path/to/private-workspace init
```

在该工作区编辑`runtime.yaml`的Provider地址、协议路径和密钥引用，并在`agents/<name>/AGENT.md`选择同名Provider与账号可用model。
可参考[普通模型模板](../../tools/dev/examples/real-provider/runtime.yaml)及[Agent模板](../../tools/dev/examples/real-provider/agents/real-deepseek/AGENT.md)；其中model占位值必须替换。
密钥使用环境变量显式引用，或权限600的`secrets.local.yaml`；不要放命令行、正文或仓库。默认md记忆无需Mem0/MCP。

```bash
./tools/dev/oryxos --root /absolute/path/to/private-workspace status
./tools/dev/oryxos --root /absolute/path/to/private-workspace chat --profile <name> --user alice
# /quit 正常退出，记下输出的session UUID；相同channel/user/Agent及模型快照方可续聊
./tools/dev/oryxos --root /absolute/path/to/private-workspace chat --profile <name> --user alice --session <uuid>
./tools/dev/mvnw -B -ntp test -Dgroups=storage-faults
./tools/dev/mvnw -B -ntp test -Dgroups=restart-recovery
```

本地测试分别16项与其中5项，包含实际JVM重启和SQLite故障。它们使用回环模型/接收方，不代表两家公网模型已验收。
运行根必须可写、只允许一个运行进程。记录故障会暂停；仅修复存储/重启不会解锁。
RecoveryPort内部服务和人工确认行为已验证，第三批已补齐HTTP操作入口，第五批已补齐页面；本批没有新的恢复CLI命令。
[本批证据与限制](evidence/US5-first-batch-local.md)、[任务审查](evidence/US5-first-batch-review.md)。

## US5 第二批：运行文件调度

先按上一节配置个人工作区、Provider与Agent。将以下`schedules`加入已有AGENT.md的frontmatter，保持原有配置和正文：

```yaml
schedules:
  - id: morning
    cron: "0 0 8 * * *"
    timezone: Asia/Shanghai
    message: "简短说明今天的任务。"
    enabled: true
    max_duration_seconds: 60
```

工作区timezone也须为Asia/Shanghai；省略任务timezone时继承工作区。cron是六字段，按该时区每天08:00运行。
自动预算默认60秒，可仅在文件中放大；手动始终60秒。Agent/Provider/调度配置重启加载，正文仍按次读取。

```bash
cd /Users/jianghaoran/github/OryxOS
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp clean verify
./tools/dev/oryxos --root /absolute/path/to/private-workspace serve
# Ctrl-C正常退出后，也可用gateway启动同一工作区；两者不能同时运行
./tools/dev/oryxos --root /absolute/path/to/private-workspace gateway
# 无需公网凭证的本地验证
./tools/dev/mvnw -B -ntp test -Dgroups=us5-schedules
```

serve/gateway会自动执行已启用的有效任务；普通chat不启动定时器。调用模型仍需要账号可用模型/密钥，本批回环验证不等于真实供应商验收。
`enabled`只在首次创建该schedule状态时取文件初值；以后保存的运行启停状态优先，改文件不会复位。
内部启停/立即执行服务已验证，但本批没有新调度CLI命令，第三批已补齐HTTP控制入口，第五批已补齐页面；需要停止当前运行进程时使用Ctrl-C。
记录故障后仍需人工核查和明确恢复，重启不会绕过暂停，也不补跑停机或故障期间错过的任务。
T073/T093/T094完整HTTP验收及此前真实联调缺口保持未完成。[本批证据](evidence/US5-second-batch-local.md)。

## US5 第三批 HTTP 验证

第三批当时止于T089，未实现完整CLI或/admin/；第四批CLI说明见下。以下本地命令不需要真实模型账号：

~~~bash
cd /Users/jianghaoran/github/OryxOS
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp clean test -Dgroups=us5-http-api
./tools/dev/mvnw -B -ntp clean verify
python3 tools/dev/verify-foundation.py
~~~

us5-http-api包含15项，实际截止用例会等待约60秒；使用临时工作区、SQLite和回环模型/接收方，不代表真实联调通过。
普通chat和HTTP运行需要有效runtime.yaml、Agent定义及该Provider所需秘密引用；提供相应工具白名单、通知/MCP配置才可调用这些能力。chat与serve/gateway互斥同一工作区。已暂停的工作区须先核查并用HTTP明确恢复，重启本身不恢复。

配置好个人工作区后，在终端运行`./tools/dev/oryxos --root "$ORYXOS_WS" serve`。另一个终端可执行：

~~~bash
ORYXOS_API=http://127.0.0.1:8080/api/v1
ORYXOS_AGENT=default  # 替换为 GET /profiles 返回的可用Agent名称
curl -sS "$ORYXOS_API/health"
curl -sS "$ORYXOS_API/profiles"
curl -sS -X POST "$ORYXOS_API/agents/$ORYXOS_AGENT/invoke"   -H 'Content-Type: application/json' -H 'X-Oryx-User-Id: reviewer'   -d '{"content":"请回复一句简短问候。"}'
curl -sS "$ORYXOS_API/executions?limit=10"
curl -sS "$ORYXOS_API/schedules"
curl -sS "$ORYXOS_API/runtime/state"
~~~

写请求的X-Oryx-User-Id是分组标识，不是登录认证；只读接口可查看本工作区其他入口的历史。不要自动重试POST。
调度控制使用`PUT /schedules/{agent:localId}/enabled`、正文`{"enabled":false}`；停用后仍可`POST /schedules/{id}/run`，不会改变enabled。
有故障时，先核对`GET /runtime/state`的currentIncident与外部实际动作；修复后使用该incidentId请求`POST /runtime/recover`，正文包含`incidentId`、`acknowledgeMissingRecords:true`、1–2000字符的真实核对说明`notes`，并提供用户头。恢复不会重放动作。

HTTP 504表示等待截止。通常执行为TIMED_OUT；若模型已完成而最终记录尚在收尾，则保留已知模型状态并标UNKNOWN，随后查询可显示最终记录结果。无论哪种情况，都先查执行ID再决定重试。
完整任务/行为矩阵见[第三批证据](evidence/US5-third-batch-local.md)；跨批出口与真实缺口见[审查](evidence/US5-third-batch-review.md)。


## US5 第四批 CLI 验证

本批仅T090；不进入T091管理台。以下命令使用个人隔离入口；构建与本地测试无真实账号前提：

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp clean test -Dgroups=us5-cli
./tools/dev/mvnw -B -ntp clean verify
python3 tools/dev/verify-foundation.py

ORYXOS_CLI_WS=$(mktemp -d "$PWD/.oryxos-dev/cli-review.XXXXXX")
./tools/dev/oryxos --root "$ORYXOS_CLI_WS" init --json
./tools/dev/oryxos --root "$ORYXOS_CLI_WS" status --json
./tools/dev/oryxos --root "$ORYXOS_CLI_WS" profile create researcher
./tools/dev/oryxos --root "$ORYXOS_CLI_WS" profile list --json
./tools/dev/oryxos --root "$ORYXOS_CLI_WS" profile show researcher --json
./tools/dev/oryxos --root "$ORYXOS_CLI_WS" provider list --json
./tools/dev/oryxos --root "$ORYXOS_CLI_WS" tool list --json
./tools/dev/oryxos --root "$ORYXOS_CLI_WS" session list --json
# 新工作区尚无数据库：上一条应明确返回3/STORAGE_UNAVAILABLE，不创建空库。
./tools/dev/oryxos --root "$ORYXOS_CLI_WS" profile delete researcher
```

普通chat已使用正式SQLite，可在配置后运行；没有内存替身绕过RUNTIME_NOT_READY。按前文配置runtime.yaml中的Provider协议、endpoint、completions_path和密钥引用；在环境或0600的secrets.local.yaml提供凭证，在agents/<name>/AGENT.md选择实际provider/model。普通无工具chat不要求Mem0、通知或MCP配置；用到这些能力再按对应契约配置。模板中的configure-provider/configure-model不是可用账号。

```bash
./tools/dev/oryxos --root /absolute/path/to/configured-workspace chat --profile default --user owner --json
# 输入消息；/quit退出。每轮JSON有sessionId，可用--session <UUID>续聊。
./tools/dev/oryxos --root /absolute/path/to/configured-workspace session list --profile default --limit 20 --json
./tools/dev/oryxos --root /absolute/path/to/configured-workspace serve
# 或gateway：HTTP和文件调度相同；chat不自动启用调度。Ctrl-C停止。
```

运行期间可在另一终端执行profile show/tool list/session list；另开chat、serve、gateway或profile delete返回4。配置变更重启加载；删除只把定义移入agent-archive，历史保留。超时返回6，记录异常/暂停返回7，先核查执行/审计与runtime/state；恢复仍用已有HTTP人工确认入口，不自动重放。
真实模型、Mem0、通知、可信MCP缺口及T073/T093–T095跨批出口保持开放；本地夹具通过不替代真实验收。见[第四批证据](evidence/US5-fourth-batch-local.md)。

## US5 第五批管理台验证

本批完成T091/T092，止于管理台查询与运行控制；T073和T093–T095整体出口继续保留。页面为原生HTML/CSS/ES modules，位于web模块`static/admin/`，与已有API同源，没有Node构建步骤。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp clean verify
# 使用你已经配置好的工作区（替换下面路径）：
./tools/dev/oryxos --root /absolute/path/to/your-workspace serve
```

打开`http://127.0.0.1:8080/admin/`（修改监听端口时相应调整）。gateway也提供同一页面；chat持有同一工作区锁，不能与serve同时运行。普通chat/页面运行真实任务仍需有效Provider endpoint/path、凭证和Agent provider/model配置；纯查询不调用模型。用户标识用于记录操作者，不是登录认证；页面当前接受1–128位可见ASCII标识，不改写历史身份。

无需真实账号的可操作验证入口：

```bash
# 已完成上面的构建，或先执行：
./tools/dev/mvnw -B -ntp clean test -Dgroups=us5-admin
./tools/dev/admin-preview --port 18080
```

打开`http://127.0.0.1:18080/admin/`。这是**本地测试服务**：正式Boot/HTTP/SQLite，模型及HTTP接收器是回环夹具，不是真实模型或通知验收。所有数据写入新建的`.oryxos-dev/tmp/admin-browser-*`，Ctrl-C停止。夹具只在测试classpath，正式发行包不提供测试入口。不要在同一夹具上混跑手动步骤和自动校验；每轮重新启动以生成新工作区。

1. 保存用户标识`browser-review`，检查local Agent说明中的“本地验证服务”。未保存身份、不可用Agent或工作区暂停时，运行按钮禁用。
2. 在会话历史按`browser-review`筛选；打开24条消息的会话，加载更早消息。清空筛选并加载更多，核对用户/入口及创建时模型。共享记忆中CORE/ARCHIVAL分开，`<img…>`仅作为文本显示。
3. 运行local Agent，输入`普通浏览器验证`。查询完整执行与审计；刷新或重新打开页面只查询，不创建新任务。用Enter操作导航/按钮，Escape关闭运行窗口。
4. 输入`纠正参数验证`，应看到“曾有已纠正错误”，详情仍有bad-params、RESOLVED和fixed-params关联。输入`未知结果验证`，应看到UNKNOWN核查提示，而非全部成功；刷新不再次调用接收器。
5. 在定时任务启用、停用local:daily；停用后仍可立即执行，状态保持停用，可查询DISPATCHED历史。页面没有cron、Agent、Provider或通知渠道编辑。
6. 输入`记录故障验证`，应进入PAUSED_RECORDING；故障页面显示资料不完整、缺失记录和已知/未知动作，禁用新执行。仅填写说明不能恢复。此夹具用SQLite触发器制造故障，维护动作是另开终端执行下面的**仅测试入口**，随后在页面勾选确认、填写核对说明、点击“确认恢复（不重放）”。状态回到READY，旧执行仍INCOMPLETE，接收次数不增加。

```bash
curl --fail -X POST http://127.0.0.1:18080/__fixture__/repair
curl --fail http://127.0.0.1:18080/__fixture__/evidence
```

还可在一套**新启动且未手动操作**的夹具上打开`http://127.0.0.1:18080/__admin_test__/index.html`。该测试页会自动执行13组查询与写入/故障/恢复流程，最终显示PASS和请求计数，可保存JSON。它是开发测试页，不打入正式发行；第六批另完成发行CLI及人工浏览器出口，见[CLI/管理台验收](evidence/US5-cli-admin.md)。第五批原结果见[本批证据](evidence/US5-fifth-batch-local.md)。

## US5 第六批验收状态

T093–T095全部通过；续作修复实际OpenAPI，19操作/44schema零差异，T073关闭。全量176项/39类零失败/错误/跳过；真实模型、Mem0、通知和可信MCP缺口，以及T096–T104不因此关闭。

~~~bash
./tools/dev/mvnw -B -ntp package -Dgroups=us5-published-contract
./tools/dev/mvnw -B -ntp test -Dgroups=us5-http \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
./tools/dev/mvnw -B -ntp test -Dgroups=us5-cli-process \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
./tools/dev/mvnw -B -ntp verify
~~~

本地浏览器测试入口与亲自操作步骤见[CLI/管理台验收](evidence/US5-cli-admin.md)，命令与完整证据见[续作记录](evidence/US5-sixth-batch-continuation.md)。初次185项文档差异保留为历史失败基线，没有跳过或降低断言。


## US5 第七批：三个示例与可控全链路

T096–T097已完成；kernel-local 9项及最终全量185项/40类通过。示例在 `examples/`，没有 Node 构建链；业务规则在 Agent/参考/可信脚本中。

```bash
./tools/dev/mvnw -B -ntp package -Dgroups=kernel-local
./tools/dev/mvnw -B -ntp verify \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
```

本地测试不需要真实凭证，自动验证独立CLI保存共享偏好、HTTP手动运行、实际cron、SQLite审计、MCP子进程、脚本和回环通知。
真实配置与操作见 [examples/README](../../examples/README.md)，本批证据见 [US5-kernel-local](evidence/US5-kernel-local.md)。
模型、业务源和通知接收方均为本地夹具；六次正常运行不计作真实SC-005验收。T098–T104及此前十项真实缺口不变。


## US5 第八批：真实场景前提未满足

维护者确认尚未准备真实配置；T098–T100保持未完成。T101已完成续轮四小时复测但未达50ms，T102及后续未进入。
本批只执行已有real-provider、real-provider-tools、real-notify、real-mcp的严格前检，6项均因缺参数失败、零跳过，未发起外部业务动作。
不将前检失败当场景失败演示，也不把第七批本地成功转成真实通过。
所需工作区、模型/来源/MCP/通知配置、恢复顺序及本次命令见[第八批审查](evidence/US5-eighth-batch-review.md)；
三项记录见[天气](evidence/IT01-weather-real.md)、[科技日报](evidence/IT02-tech-real.md)、[GitHub日报](evidence/IT03-github-real.md)。

## 第九批续轮复测（2026-11-16）

57400次任务成功，完整审计与连续性核对通过；内部最大1011.133125ms、49000个样本超过50ms，T101仍未通过。本地203项/43类回归通过。短程入口 `test -Dgroups=kernel-diagnostic` 约三分钟，结果不能替代正式四小时；完整命令、环境条件和失败证据见[续轮性能报告](evidence/US5-performance-continuation.md)。原失败和中断证据全部保留，真实模型/Mem0/MCP/通知缺口不变。

## T101 提交与队列继续验证（2026-11-16）

本轮修改后独立短程最大322.241791ms仍超过50ms；按“短程达标再四小时”未启动新的长跑。全量207项/44类通过，T101不勾选。显式执行短程门禁：

```bash
./tools/dev/mvnw -B -ntp test \
  -Doryxos.tests.mode=soak -Dgroups=kernel-short-gate
```

需先按本文环境部分设置ORYXOS_JAVA_HOME指向本机JDK21；完整命令、原始证据和未完成项见[本轮记录](evidence/US5-ninth-batch-commit-queue-review.md)。普通local功能回归通过不代表50ms性能通过。


## T101 读取与提交后恢复继续验证（2026-11-16）

最终短程500次成功、完整SQLite审计核对通过，但内部最大271.953375ms、500/500超过50ms，入口上界290.153250ms；门禁真实非零退出，不启动新四小时。全量216项/50类、306项必跑引用通过，不能替代性能验收。此前失败和真实联调缺口全部保留。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp verify
./tools/dev/mvnw -B -ntp test -Doryxos.tests.mode=soak -Dgroups=kernel-short-gate
```

短程必须通过后才执行上方固定四小时命令；当前短程预期失败。新增local组 `kernel-lease-read`、`kernel-writer-isolation`、`kernel-http-read`、`kernel-tool-transport`、`kernel-memory-read`、`kernel-evidence-log`；`kernel-reader-probe` 为显式soak最小读诊断，不进入普通local或固定四小时组。[本轮证据、每次试验及剩余瓶颈](evidence/US5-ninth-batch-read-wakeup-review.md)。


## T101 模型传输与组内等待最新验证（2026-11-16）

本节更新上文历史续轮状态。最终严格短程内部最大201.795500ms，500/500超过50ms；入口上界207.072667ms，0稳定性违例且SQLite审计完整。门禁非零退出，未启动新四小时，T101仍开放。221项/51类功能回归、311项必跑引用通过；此前失败与真实联调缺口不变。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp verify
./tools/dev/mvnw -B -ntp test -Dgroups=kernel-provider-timing,kernel-provider-resources,kernel-group-timing,kernel-external-isolation,kernel-soak-harness
./tools/dev/mvnw -B -ntp test -Doryxos.tests.mode=soak -Dgroups=kernel-short-gate
```


短程必须先全部达标，才可执行上文固定四小时命令。新增local组为`kernel-provider-timing`、`kernel-group-timing`、`kernel-external-isolation`；`kernel-provider-resources`补请求体线程验证。`kernel-provider-probe`为显式soak的独立100并发模型HTTP诊断，不含SQLite，不能替代T101。[结果、复现与限制](evidence/US5-ninth-batch-transport-batch-review.md)。
