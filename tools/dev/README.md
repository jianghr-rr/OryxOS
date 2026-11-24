> 当前进度：US5完成T073–T097及T102发布说明与30分钟新使用者路径验证（7.22s，使用回环模型桩）；第九批 Slice 5–6 已将短程内部最大从201.80ms降至112.14ms（0违例、审计全过、322项本地 `verify` 全绿），已按用户确认将压测目标时长改为2分钟（`120s` / `PT2M`），因仍超过50ms按路线 C 止损保持 T101 未勾选；T027/028/042/044/051/057/058/070/071/072/098–100 真实前提缺口不变，T103–T104 尚待全部前置关闭后进入。

## 004 工程门禁

门禁清单、例外登记、存量基线与 CI 说明见 [docs/EngineeringBaseline.md](../../docs/EngineeringBaseline.md)，以下命令与该文档一致：

```bash
export ORYXOS_JAVA_HOME=/absolute/path/to/jdk-21/Contents/Home
./tools/dev/mvnw clean verify                                   # 格式、规约、安全、测试与基线判定
./tools/dev/mvnw spotless:apply                                 # 自动格式化；只检查用 spotless:check
./tools/dev/install-hooks                                       # 安装提交前钩子（格式 + PMD/Checkstyle）
./tools/dev/quality-baseline --check-shrink origin/main         # 基线只减不增
./tools/dev/quality-baseline --prune                            # 在一次 clean verify 之后删除已清零条目
# 依赖漏洞扫描：先在当前 shell 中 export NVD_API_KEY，不要写进文件或命令参数
./tools/dev/mvnw -Pdependency-scan dependency-check:aggregate
```

`-Dspotbugs.skip`、`-DskipTests` 等跳过门禁的参数会被拒绝（退出码 2）。

## T102 新使用者路径与当前边界（2026-11-18）

- 个人构建与运行入口统一为：`./tools/dev/mvnw -B -ntp verify` 与 `./tools/dev/oryxos --root <workspace> <cmd>`（先设置 `ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home`）。
- 新工作区须在 `runtime.yaml` 与 `agents/<name>/AGENT.md` 中声明 `schema_version: 1`；`serve`/`gateway` 端口通过 `SERVER_PORT` 环境变量覆盖（默认 `8080`）。
- 完整 12 项 CLI、19 个 HTTP 操作、`/admin/` 管理台、故障人工恢复与当前限制见[CliGuide](../../docs/CliGuide.md)及[上手证据](../../specs/001-single-node-agent-runtime/evidence/US5-onboarding.md)。

# 个人项目工具入口

只作用于此脚本所属的 OryxOS checkout，不调用官方课程仓库或课程 run.sh。
选择已安装 JDK 21，只给当前命令设置 `ORYXOS_JAVA_HOME`；不需要改系统 Java、Node、Maven。

```bash
export ORYXOS_JAVA_HOME=/absolute/path/to/jdk-21/Contents/Home
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw --version
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp clean verify
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw test -Dgroups=f0-discovery
```

构建固定回到个人仓库，复用根目录 Maven Wrapper。仅子进程设置 JAVA_HOME/PATH，
普通构建环境清除个人 Maven/Java 参数及凭证；settings、global-settings 都由本项目提供。
Wrapper 缓存、本地仓库、Java user.home 和临时目录均在被忽略的 `.oryxos-dev/`。
可只读复用已有 JDK，但不会向该 JDK 所在项目写文件。不安装全局软件。

普通 test/package/verify 必须通过当前已登记 local 分组的本次 XML 报告核查，
`*Test` 和 `*IT` 同在 Surefire test 阶段运行一次；未知分组、漏跑、跳过、旧报告失败。
`-pl <module> -am` 只核对所选 reactor 中承担该组的模块；不存在登记测试则明确报错。
实现后续故事时串行扩展 `test-groups.json`，不要提前登记还未实现的分组。
real/soak 模式必须显式选择同类型分组；真实验收仍未通过。第九批登记了 kernel-soak 四小时入口，另有独立 local 分组 kernel-soak-harness；短程夹具通过不代表长跑通过。
该入口不接受跳过测试、改写 settings/本地仓库/项目根或发布操作；这些操作不属于验收命令。

```bash
python3 tools/dev/test_report_gate.py
```

测试报告核查器只依赖 Python 3 标准库。普通本地测试只使用临时工作区、回环网络和真实临时 SQLite；
无真实模型、MCP、通知或 Mem0 联调。完整业务操作以后续 US 阶段验收为准。

## F0 可亲自执行的操作

```bash
ORYXOS_REPO=/Users/jianghaoran/github/OryxOS
"$ORYXOS_REPO/tools/dev/mvnw" test -Dgroups=f0-foundation
python3 "$ORYXOS_REPO/tools/dev/verify-foundation.py"

ORYXOS_WS=$(mktemp -d /tmp/oryxos-f0.XXXXXX)
"$ORYXOS_REPO/tools/dev/oryxos" --root "$ORYXOS_WS" init
"$ORYXOS_REPO/tools/dev/oryxos" --root "$ORYXOS_WS" init
"$ORYXOS_REPO/tools/dev/oryxos" --root "$ORYXOS_WS" status
"$ORYXOS_REPO/tools/dev/oryxos" --root "$ORYXOS_WS" serve
# Ctrl-C 停止；可另开终端查看status或验证第二个serve返回WORKSPACE_BUSY（退出4）。
```

本机验证选择的已有JDK是 `/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home`。
设置 `ORYXOS_JAVA_HOME` 只读复用它；不调用课程包装器、不写官方实验目录。
运行入口保留调用者cwd和显式业务环境变量，但清除继承的JVM注入选项；所有入口统一按
`--root > -Doryxos.root > ORYXOS_ROOT > cwd/.oryxos` 定位。JVM属性方式适用于直接运行完整JAR。

F0已验证：稳定workspace UUID、默认Agent/Bootstrap模板、幂等init、status、基础serve，
安全YAML及按Provider隔离的配置诊断、受控SQLite迁移/JPA工厂、同根进程互斥。
serve默认127.0.0.1:8080，无参数完整JAR也启动serve。端口占用时明确启动失败并释放锁。
JDK版本/仓库信息写stderr，CLI内容写stdout；init/status显示规范工作区与数据库路径。

以下描述 F0/US1–US3 当时的装配限制：chat曾缺正式Session/Execution存储端口而返回NOT_READY，业务HTTP及管理台尚未交付；这些限制已在US5解除，当前chat使用正式SQLite，见本文顶部和US5章节。
当前serve/gateway已提供业务HTTP与管理台；实际就绪状态仍须查看health/runtime/state，不能只看HTTP进程存活。
配置或DB失败会在启动日志显示CONFIGURATION_ERROR/STORAGE_UNAVAILABLE，HTTP诊断进程仍存活。
status只读文件和进程锁，明确数据库健康未检查；不创建数据库、不启动Spring/MCP/模型。
US5已实现正式故障记录、人工确认恢复API及持久化会话/审计；资源工厂重建不会自动重新开放执行许可。

`verify-foundation.py`仅使用临时工作区和随机回环端口，验证两个真实JVM的互斥、损坏数据库降级、
无参数启动和根优先级，自动结束自己启动的进程，输出 `.oryxos-dev/evidence/f0-smoke.json`。
测试发现采用[Surefire显式包含规则](https://maven.apache.org/surefire/maven-surefire-plugin/examples/inclusion-exclusion.html)，
额外的DiscoverySuffixIT只匹配新增的`*IT`规则，避免默认`Test*`规则掩盖漏配。
JPA由存储工厂按[Hibernate程序化装配方式](https://docs.hibernate.org/orm/6.6/quickstart/html_single/)受控创建，
当前实际依赖版本仍按研究锁定的6.6.53.Final，不随在线文档示例升级。

## US1 Provider 验证

```bash
export ORYXOS_JAVA_HOME=/absolute/path/to/jdk-21/Contents/Home
ORYXOS_REPO=/Users/jianghaoran/github/OryxOS
"$ORYXOS_REPO/tools/dev/mvnw" -B -ntp clean verify
"$ORYXOS_REPO/tools/dev/mvnw" -B -ntp test -Dgroups=us1-contract
python3 "$ORYXOS_REPO/tools/dev/test_build_environment.py"
```

`us1-contract`有20个必跑用例。Provider按验收点分为`ProviderRoutingTest`、`ProviderToolSchemaTest`、`ProviderAuditTest`、`ProviderProtocolTest`、`ProviderTransportTest`、`ProviderAdmissionTest`和`ProviderArchitectureGuardTest`；其中`ProviderSmokeTest`是可执行独立演示：
两个AGENT.md → 真实适配代码 → 两个本地HTTP接收器，检查来源、响应与调用记录。
不需要公网账号，不声称这证明真实DeepSeek/Kimi连通。`status`读取当前磁盘上的Agent配置，
只显示通知名称；运行中进程仍持有启动配置快照。US1当时模型调用只有关联结构化日志；当前US5已提供SQLite审计。
端口只接收消息、Schema和时限，供应商选项由`ProviderService`取runtime.yaml快照，不接受任务覆盖。
工具提议只作为数据返回；未知wire名携带UNKNOWN_TOOL，后续引擎负责配对错误，无自动工具执行。

真实普通请求另有严格入口。需先具备：

1. DeepSeek、Kimi各自有效密钥、可用额度及账号实际支持的模型名。
2. 独立私有工作区的runtime.yaml，以及agents/real-deepseek/AGENT.md、agents/real-kimi/AGENT.md。
3. 到`https://api.deepseek.com/chat/completions`、`https://api.moonshot.cn/v1/chat/completions`的直接HTTPS访问。
   当前传输不读取系统代理，默认不跟随重定向；密钥只发送给配置目标。

可把不含秘密的模板复制到一个新目录：

```bash
ORYXOS_REAL_WS=$(mktemp -d "$ORYXOS_REPO/.oryxos-dev/real-provider.XXXXXX")
cp -R "$ORYXOS_REPO/tools/dev/examples/real-provider/." "$ORYXOS_REAL_WS/"
# 编辑两个AGENT.md的REPLACE_WITH_ACCOUNT_ENABLED_*_MODEL，填写账号实际可用的模型。
# 在当前终端提供DEEPSEEK_API_KEY和KIMI_API_KEY；或写入该工作区的secrets.local.yaml并chmod 600。
"$ORYXOS_REPO/tools/dev/mvnw" -B -ntp -pl oryxos-provider -am test \
  -Doryxos.tests.mode=real -Dgroups=real-provider \
  -Doryxos.real.workspace="$ORYXOS_REAL_WS"
```

只有显式`real`模式和`real-provider`或`real-provider-tools`组会向构建子进程传递`DEEPSEEK_API_KEY`、`KIMI_API_KEY`两个环境变量；
普通构建继续清除全部凭证，ACSP等其他变量不传递。凭证不要写进`-D`参数或仓库文件。
两个Provider都先完成配置检查；缺任一配置/凭证会失败，不skip、不切换到假服务。
用例各发一次普通请求，使用60秒预算，不自动重试；实际响应、模型、时间、版本和安全失败证据位于
`.oryxos-dev/evidence/us1-real/<runId>/`。其中不记录秘密或reasoning_content。
公网工具回填由US2的T042验证，不能用本次普通请求替代。

本次真实前检因工作区/凭证/账号模型未配置而失败；T027及依赖它的T028保持未完成。
详见[本地证据](../../specs/001-single-node-agent-runtime/evidence/US1-local.md)、
[真实前提](../../specs/001-single-node-agent-runtime/evidence/US1-real-models.md)和
[核对记录](../../specs/001-single-node-agent-runtime/evidence/US1-review.md)。

## US2 ReAct 验证

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp test -Dgroups=us2-loop
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp test -Dgroups=deadline-boundary
```

us2-loop登记44项：顺序工具/直答/上限（含恰好转满10轮）、逐轮累积与完整会话历史、Prompt顺序与历史截断、ToolExecutor成功/失败/异常/审计写失败、AgentService的ProfileContext设置与清理及会话落库、ContextLoader热读取与Bootstrap校验、身份与创建模型快照、离线Schema、HTTP权限/重定向/读取边界、安全重试、未知副作用、受控纠正、截止竞争及真实CLI主入口集成。第17节课件五个测试类（ReActLoopTest、PromptBuilderTest、ToolExecutorTest、AgentServiceTest、ContextLoaderTest）均在此组。
deadline-boundary的12项是上述集合的子集；US2批次全量回归实际61项；加入US3后全量81项，各执行一次。

CLI集成用例通过`OryxOsApplication.execute`进入真实解析器、Boot和AgentService，真实Provider适配连接回环模型，真实http_get查询回环天气；续聊复用同一Session。
`TransientSessionStore`、`TransientExecutionStore`只在测试装配中使用；会话策略由正式SessionManager提供。US3已将EmptyMemoryFacade装配替换为正式MemoryService和所选真实后端，不复制会话逻辑。
默认发行执行`chat --profile <name> --user <id> [--session <uuid>]`会因正式端口未齐返回`RUNTIME_NOT_READY`（退出7），不能作为生产可聊演示。

本地状态为T029–T041、T043完成。T027/T028、T042/T044保持未完成；无真实模型证据，无数据库工具审计或跨进程会话恢复证据。
真实工具联调的独立模板位于`examples/real-provider-tools/`；初始化私有工作区、密钥/模型/网络/端口前提及命令见[US2-real-tools](../../specs/001-single-node-agent-runtime/evidence/US2-real-tools.md)。
该入口要求两家各≥2次真实模型调用、恰好1次已知天气工具请求和reasoning_content回传；缺前提失败，不skip、不替换为假模型。
[本地证据](../../specs/001-single-node-agent-runtime/evidence/US2-local.md)、[边界证据](../../specs/001-single-node-agent-runtime/evidence/RV01-RV06-loop.md)、[核对记录](../../specs/001-single-node-agent-runtime/evidence/US2-review.md)。

## US3 Memory 验证

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp test -Dgroups=us3-memory
```

本组20项，无需凭证：真实MD/SQLite、回环Mem0协议夹具、统一工具链、Session归档和ONE_SHOT迟到收尾。
全量clean verify为81项/23类，0失败/错误/跳过；93个必跑登记含12个跨标签重叠项。
源码/依赖/基础镜像固定，但Docker daemon不可用，应用镜像digest未核实，T051仍未完成；T057/T058也未完成。
真实服务配置、运行与显式重启测试命令见[tools/mem0](../mem0/README.md)。real-mem0仅转发MEM0_API_KEY，
不把提炼/embedding服务密钥交给JVM。默认chat仍等待US5正式Session/Execution存储。
[本地证据](../../specs/001-single-node-agent-runtime/evidence/US3-local.md)、[真实前提](../../specs/001-single-node-agent-runtime/evidence/US3-real-mem0.md)、[阶段审查](../../specs/001-single-node-agent-runtime/evidence/US3-review.md)。


## US4 Tool 验证

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp test -Dgroups=us4-tools
./tools/dev/mvnw -B -ntp clean verify
```

us4-tools共21项：实际文件/脚本、回环HTTP/Webhook、SDK stdio MCP、九工具CLI合流、原生注解扩展、启动引用校验和子进程CANARY隔离。
本批最终全量102测试/27类，0失败/错误/跳过；此前各节的61/81为当时批次历史计数。
默认chat仍缺正式Session/Execution存储，测试专用临时Store不进入主JAR。

T059–T069本地完成，T070/T071/T072未完成；T027/T028/T042/T044/T051/T057/T058继续未完成。
real-notify、real-mcp仅在显式real模式运行，不继承任意父环境秘密；凭证放私有工作区权限600的secrets.local.yaml。
缺目标/服务/明确确认参数时入口失败而非skip；不会调用假接收方/夹具代替真实验收。
[本地证据](../../specs/001-single-node-agent-runtime/evidence/US4-local.md)、
[真实通知前提与命令](../../specs/001-single-node-agent-runtime/evidence/US4-real-notify.md)、
[可信MCP前提与命令](../../specs/001-single-node-agent-runtime/evidence/US4-configured-mcp.md)、
[阶段审查](../../specs/001-single-node-agent-runtime/evidence/US4-review.md)。

## US5 第一批：正式持久化与故障恢复

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp test -Dgroups=storage-faults
./tools/dev/mvnw -B -ntp test -Dgroups=restart-recovery
./tools/dev/mvnw -B -ntp clean verify
```

两组分别16项和其中5项，使用真实SQLite、正式Store/Audit及独立JVM；外部模型和接收方为回环夹具。
全量118测试/30类通过，无失败/错误/跳过。T073的HTTP部分及T082以后仍未完成。

普通chat需初始化且可写的独立工作区、有效Agent/Provider/model、显式密钥引用；默认md不要求Mem0/MCP。

```bash
./tools/dev/oryxos --root /absolute/path/to/private-workspace init
# 编辑runtime.yaml与agents/<name>/AGENT.md；密钥放环境引用或权限600的secrets.local.yaml
./tools/dev/oryxos --root /absolute/path/to/private-workspace chat --profile <name> --user alice
# /quit退出后，用同身份和输出的session ID续聊
./tools/dev/oryxos --root /absolute/path/to/private-workspace chat --profile <name> --user alice --session <uuid>
```

serve/gateway共用持久引擎与同根进程锁，业务HTTP/调度/管理台仍未实现。
记录故障立即暂停；内部人工恢复服务已验证，但使用者HTTP/管理台恢复入口待T088/T092，不能靠删除标记或重启绕过确认。
本批未执行真实外部服务验收，原十项真实缺口保持未完成。
[配置与运行指南](../../specs/001-single-node-agent-runtime/quickstart.md#us5-第一批现在运行普通-chat)、
[本地证据](../../specs/001-single-node-agent-runtime/evidence/US5-first-batch-local.md)、
[阶段审查](../../specs/001-single-node-agent-runtime/evidence/US5-first-batch-review.md)。

## US5 第二批调度验证

```bash
export ORYXOS_JAVA_HOME=/absolute/path/to/jdk-21/Contents/Home
./tools/dev/mvnw -B -ntp test -Dgroups=us5-schedules
./tools/dev/mvnw -B -ntp test -Dgroups=us5-schedules,storage-faults
./tools/dev/mvnw -B -ntp clean verify
```

调度组18项，全量136个唯一测试；旧组和真实模式门禁不变。真实CLI serve/gateway会注册有效文件任务，chat不会。
启停状态保存在SQLite，文件enabled只用于首次创建；无停机补跑。运行控制HTTP/页面入口还未实现。
配置与执行步骤见[quickstart](../../specs/001-single-node-agent-runtime/quickstart.md#us5-第二批运行文件调度)，
[本地证据](../../specs/001-single-node-agent-runtime/evidence/US5-second-batch-local.md)包含测试输入、结果与边界。

## US5 第三批 HTTP API（本地）

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp clean test -Dgroups=us5-http-api
./tools/dev/mvnw -B -ntp clean verify
python3 tools/dev/verify-foundation.py
```

us5-http-api登记15项，其中一个实际HTTP用例等待60秒。隔离入口验证本次XML的方法清单，不复用旧报告。
最终151测试/33类通过；这不替代真实模型、Mem0、通知或MCP验收，也不代表管理台/完整CLI完成。
打包黑盒现在要求health返回真实READY/PAUSED_RECORDING和200/503，而不是旧F0的404；其他进程锁、轻命令、幂等init和坏库断言保留。
详见specs/001-single-node-agent-runtime/evidence/US5-third-batch-local.md。


## US5 第四批 CLI（本地）

`tools/dev/mvnw -B -ntp clean test -Dgroups=us5-cli`运行本批文件/查询/退出码、正式SQLite、独立JVM JSON、stdio MCP只读发现及实际60秒CLI截止测试。`clean verify`另回归此前所有本地组，真实模型/Mem0/通知/可信MCP仍需各自前提，不跳过替代。

完整发行支持init/status/chat/serve/gateway、profile list/create/show/delete、provider list、tool list、session list共十二项。所有命令继承`--root`和`--json`；轻命令不启动Spring、模型或MCP。进程日志写stderr、结果写stdout，chat每轮一个JSON信封。默认无参数仍serve，薄CLI仍仅版本入口。

普通chat的必要配置、退出码与可直接执行命令见[quickstart](../../specs/001-single-node-agent-runtime/quickstart.md#us5-第四批-cli-验证)。profile删除仅停机移入agent-archive；只读session查询不创建数据库、不删除历史。运行中tool查询标识实际注册表的启动快照，离线MCP明确NOT_DISCOVERED。人工恢复走HTTP；第五批已提供调用同一接口的管理台。

## US5 第五批管理台

```bash
# 全量回归，或仅本批静态服务契约
./tools/dev/mvnw -B -ntp clean verify
./tools/dev/mvnw -B -ntp test -Dgroups=us5-admin
# 正式运行（替换为已配置工作区）；访问 http://127.0.0.1:8080/admin/
./tools/dev/oryxos --root /absolute/path/to/your-workspace serve
# 无真实账号的本地浏览器验证；访问 http://127.0.0.1:18080/admin/
./tools/dev/admin-preview --port 18080
```

所有命令仍要求ORYXOS_JAVA_HOME指向现有JDK21。admin-preview使用构建产物中的测试classpath，
不会加载用户模型密钥；创建项目.oryxos-dev下的新工作区，正式HTTP/SQLite配合明确标记的回环模型和接收器。
`--root`如显式提供，只接受.oryxos-dev内且未初始化的目录，防止覆盖已有数据。Ctrl-C停止。
可在新夹具打开`/__admin_test__/index.html`自动执行13组浏览器验证（包含写入与故障恢复），
PASS JSON记录请求及副作用计数；手动操作与自动测试各用独立新夹具。测试页/辅助端点不在发行JAR。
可亲自操作的步骤和纯测试repair命令见[quickstart](../../specs/001-single-node-agent-runtime/quickstart.md#us5-第五批管理台验证)。
第五批当时未关闭T073/T093–T095；第六批续作已补齐这些本地出口。真实模型/Mem0/通知/可信MCP缺口不因此关闭。

## US5 第六批验收

~~~bash
./tools/dev/mvnw -B -ntp package -Dgroups=us5-published-contract
./tools/dev/mvnw -B -ntp test -Dgroups=us5-http \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
./tools/dev/mvnw -B -ntp test -Dgroups=us5-cli-process \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
./tools/dev/mvnw -B -ntp verify
~~~

实际us5-http 71项、发行CLI进程3项均通过；全量176项/39类零失败/错误/跳过。未传acceptance.jar时使用独立main JVM，不能标为发行包验收。
管理台测试宿主沿用上一节admin-preview，新工作区自动页现为13组（增加真实SQLite调度写入/恢复确认失败），不在正式JAR中。T093–T095及T073完成，本批止于T095，模型/notify接收器为回环夹具，既有真实缺口不变。
[续作与证据](../../specs/001-single-node-agent-runtime/evidence/US5-sixth-batch-continuation.md)、[CLI/浏览器操作步骤](../../specs/001-single-node-agent-runtime/evidence/US5-cli-admin.md)。


## US5 第七批可控整合

```bash
./tools/dev/mvnw -B -ntp package -Dgroups=kernel-local
./tools/dev/mvnw -B -ntp verify \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
```

9项新增测试使用真实CLI/HTTP/SQLite/MCP子进程/脚本，模型、业务数据和通知接收端均为本地服务，测试后清理。
三个Agent示例及真实配置说明见[examples](../../examples/README.md)；cron默认关闭，缺真实模型/源/MCP/通知配置不可冒充真实验收。
本批止于T097，证据见[US5-kernel-local](../../specs/001-single-node-agent-runtime/evidence/US5-kernel-local.md)。


## US5 第八批真实场景前检

当前尚未准备独立真实工作区、实际模型/来源/MCP和测试通知目标；T098–T100未完成，不启用自动任务或发送通知。
本轮已有真实分组的严格前检6项均因缺参数失败、0跳过；没有新增空分组、跳过校验或使用本地替身。
恢复所需配置、原始命令和实际失败原因见[第八批审查](../../specs/001-single-node-agent-runtime/evidence/US5-eighth-batch-review.md)。

## US5 第九批性能与稳定性

```bash
# 本地夹具与计时规则回归：10 Agent / 100 会话，短程结果不算 T101
./tools/dev/mvnw -B -ntp test -Dgroups=kernel-soak-harness
# 明确运行真实四小时，真实 SQLite 完整审计，外部仅回环可控延迟
./tools/dev/mvnw -B -ntp verify -Doryxos.tests.mode=soak -Dgroups=kernel-soak \
  -Doryxos.test.agents=10 -Doryxos.test.sessions=100 -Doryxos.test.duration=PT4H
```

四小时入口不允许用短时长或较少会话替代正式验收。新工作区、逐任务/等待样本、环境、进度和最终结果保存在
`.oryxos-dev/evidence/kernel-soak/<run-id>/`；只有全部阈值和稳定性条件满足才返回 PASSED，失败不勾选 T101。
首次完整运行56599次任务成功，创建P99为78.538541ms，内部最大2184.36475ms；四小时用例1失败/0跳过，T101未勾选。具体口径、结果和限制见[性能证据](../../specs/001-single-node-agent-runtime/evidence/US5-performance.md)。
本批止于 T101；真实模型/Mem0/MCP/通知及 T098–T100 缺口保留，不进入 T102 及以后。

### 第九批续轮（2026-11-16）

最终全量回归203项/43类通过；新的完整四小时57400次任务成功，创建P99=16.056833ms，内部最大=1011.133125ms、49000次超过50ms。正式长跑仍返回失败，T101不勾选。新增 `test -Dgroups=kernel-diagnostic` 约三分钟短程诊断，永不算正式验收；历史查询、100条组提交与故障恢复的红绿测试及完整复现命令见[续轮性能报告](../../specs/001-single-node-agent-runtime/evidence/US5-performance-continuation.md)。

本次使用临时 `caffeinate -is` 防止空闲/接电系统休眠，未改系统设置；长跑期间需保持开盖及接电。原合盖中断不计作连续四小时。完整工件在 `.oryxos-dev/evidence/kernel-soak/1f61d38e-5854-4701-b46f-cb6b3d1ba4ad/`，包含清单内301个源码/配置文件快照。

### T101 提交与写队列本轮验证

新增显式soak组 `kernel-short-gate`（约三分钟、原10/100/完整审计、严格50ms）、`kernel-writer-probe`（最小写入诊断）和 `kernel-redactor-probe`（最小脱敏诊断）；普通local不运行它们，固定 `kernel-soak` 四小时组也不混入这些诊断用例。本轮短程真实失败：最大322.241791ms、500/500超标，所以未启动新四小时；全量207项本地测试通过。

```bash
./tools/dev/mvnw -B -ntp test -Doryxos.tests.mode=soak -Dgroups=kernel-short-gate
```

仍须先设置本文开头的ORYXOS_JAVA_HOME；当前该短程门禁预期非零退出。修复、分段及复现命令见[本轮证据](../../specs/001-single-node-agent-runtime/evidence/US5-ninth-batch-commit-queue-review.md)。此前四小时报告原样保留，不充当当前代码的新长跑验收。


### T101 读取与提交后恢复最新验证（2026-11-16）

最终短程500次成功、完整SQLite审计核对通过，但内部最大271.953375ms、500/500超过50ms，入口上界290.153250ms；门禁真实非零退出，不启动新四小时。全量216项/50类、306项必跑引用通过，不能替代性能验收。此前失败和真实联调缺口全部保留。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp verify
./tools/dev/mvnw -B -ntp test -Doryxos.tests.mode=soak -Dgroups=kernel-short-gate
```

短程必须通过后才执行上方固定四小时命令；当前短程预期失败。新增local组 `kernel-lease-read`、`kernel-writer-isolation`、`kernel-http-read`、`kernel-tool-transport`、`kernel-memory-read`、`kernel-evidence-log`；`kernel-reader-probe` 为显式soak最小读诊断，不进入普通local或固定四小时组。[本轮证据、每次试验及剩余瓶颈](../../specs/001-single-node-agent-runtime/evidence/US5-ninth-batch-read-wakeup-review.md)。


## T101 模型传输与组内等待最新验证（2026-11-16）

本节更新上文历史续轮状态。最终严格短程内部最大201.795500ms，500/500超过50ms；入口上界207.072667ms，0稳定性违例且SQLite审计完整。门禁非零退出，未启动新四小时，T101仍开放。221项/51类功能回归、311项必跑引用通过；此前失败与真实联调缺口不变。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp verify
./tools/dev/mvnw -B -ntp test -Dgroups=kernel-provider-timing,kernel-provider-resources,kernel-group-timing,kernel-external-isolation,kernel-soak-harness
./tools/dev/mvnw -B -ntp test -Doryxos.tests.mode=soak -Dgroups=kernel-short-gate
```


短程必须先全部达标，才可执行上文固定四小时命令。新增local组为`kernel-provider-timing`、`kernel-group-timing`、`kernel-external-isolation`；`kernel-provider-resources`补请求体线程验证。`kernel-provider-probe`为显式soak的独立100并发模型HTTP诊断，不含SQLite，不能替代T101。[结果、复现与限制](../../specs/001-single-node-agent-runtime/evidence/US5-ninth-batch-transport-batch-review.md)。
