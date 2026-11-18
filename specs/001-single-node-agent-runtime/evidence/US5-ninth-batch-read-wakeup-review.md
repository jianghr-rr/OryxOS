# US5 第九批继续：读取、组内等待及提交后恢复

2026-11-16；范围止于T101。**T101未通过、未勾选。** 最终独立短程内部最大271.953375ms（P99=265.394667ms），500/500超过50ms；入口保守上界290.153250ms。短程用例1失败、0错误、0跳过，Maven退出1。没有启动新的四小时复测。不能把本轮修复或本地功能回归写成性能验收通过。

## 已实现及红绿依据

- SessionManager的排他租约只在写入确认后推进序号；新会话原子begin从1开始，既有会话每个租约首次写前查SQLite，重启重新查库。红测试同两次租约读序号次数为[3,4]，要求[0,1]；修复后通过。
- JpaSessionStore的find/lastSequence/turn/history改为有界参数化投影，直接借用既有4连接池；无Session/Execution/历史缓存，保留完整字段和provider opaque数据。投影托管上下文计数从4降为0，关闭数据库仍返回存储错误。最初MAX join方案在20/400回合下VM工作量450→8050，测试失败后改为索引相关子查询。原历史、外键、身份、迁移与故障测试保持。
- 物理写drain由单个平台线程执行。独立子JVM先复现虚拟写线程被占满的承载线程饿死，再验证写入实际落盘；仍只允许单写者、257总容量/256队列、128组上限，FULL/WAL、整组回滚与提交后确认未改。
- HTTP发送消息前的元数据查询复用正式Session池；实际HTTP测试保留身份409、缺失404、暂停503及拒绝时无模型调用。获得租约仍重新检查身份/模型快照/归档状态。
- 工具HTTP从每请求创建/关闭连接池改为所属128连接的有界池；真实回环端口证明复用，100请求可并发进入，超时只取消本请求，不关闭其他请求，不隐式重试，关闭后拒绝新IO。重定向、权限、通知UNKNOWN及失败语义回归通过。
- Markdown共享记忆独立打开完整文件快照；慢读取不阻塞另一读取或原子发布。写入仍共用工作区锁、先同步临时文件再原子替换、目录同步；原20个多实例并发写入无覆盖测试通过。未引入内存记录替身。
- JFR在中间实现中抓到共享记忆锁21.533583ms、测试服务证据日志锁85.870208ms。同步证据日志改用ReentrantLock，序列化移到锁外；每条仍在响应前同步写入、换行、flush，全部时间仍计入。子JVM先复现竞争锁占用唯一承载线程，再验证无关虚拟工作可推进；100条完整记录、逐条flush、故障可见测试通过。**这属于测试服务开销修复，不是删除生产审计或修改扣除口径。**

先红后绿的独立runId、测试数和原始XML/日志哈希见[机器摘要](US5-performance-read-wakeup-summary.json)的targetedChecks/artifacts。租约/读取、写线程、HTTP、工具连接、记忆、日志6类新增本地方法共9项；另有独立soak读探针。读探针早期修复版本27.252291ms、最小写探针28.259542ms均不作为完整短程或T101通过依据。一次选错标签的命令被tools/dev在运行前拒绝，后改用已登记标签执行；未以门禁拒绝日志作为成功证据。最初读取功能绿日志保留，但其XML未在下次构建前归档；后续有独立已归档重跑。

## 七次短程，全部保留

每次10 Agents/100独立会话、120秒稳态加排空、每任务两次10秒模型与一次5秒工具可控等待、真实SQLite完整审计；500稳态任务加20预热。每次只扣min(实际外部sleep,配置sleep)，传输、超睡、组内等待、队列、DB、审计、线程恢复与同步证据写入都保留。所有短程均失败；不同代码/进程/主机状态及JFR条件不是严格A/B，不能据最大值差异宣称整体提升。

| 版本/诊断条件 | runId | 内部最大ms | 入口上界最大ms |
| --- | --- | ---: | ---: |
| bounded projections and lease sequence | `6ae658f1-934c-4020-9178-635ed303c717` | 226.792375 | 242.424875 |
| platform writer isolation | `eed66832-3e3e-4ed4-a0f0-05b44a40490c` | 317.007333 | 335.154333 |
| direct pooled projections | `f1075b4b-e6b9-4bb2-bcc5-b613535c365e` | 444.324791 | 495.261042 |
| HTTP metadata uses pool; GC logged | `b35bfede-76bf-4e82-ada9-fd1a6d309395` | 327.475083 | 338.824250 |
| process-local fixed 512MiB heap experiment | `22893df6-4d1b-4d1a-b0e1-bd128050b1b9` | 230.574583 | 269.906709 |
| shared tool transport; JFR attached | `27d61326-2081-4e7f-9c8c-e97692252c84` | 338.409292 | 367.495000 |
| final code; concurrent memory reads and unpinned evidence wait | `164f35ac-20a9-4208-a61e-50ef4c425a01` | 271.953375 | 290.153250 |

固定512MiB堆只用于第5次测试子进程，没有修改全局JVM或默认运行参数；仍失败。JDK21源码与独立回环探针也核对了TCP延迟猜测，未发现可支持修改网络参数的证据，未修改默认设置。第6次附加JFR；第7次为未附加剖析器的最终代码独立短程。

最终runId `164f35ac-20a9-4208-a61e-50ef4c425a01`，开始于2026-11-16T16:39:44.375042+08:00[Asia/Shanghai]；稳态加排空126.036935秒。创建P99=13.310459ms；500成功、0任务失败、0稳定性违例、峰值100在途。退出后只读immutable方式检查SQLite：integrity=ok、外键0错误；111 sessions、520 executions、2080 messages、1040 llm_calls、520 tool_invocations；所有已确认executionId均存在且终态/记录完整，无重叠/串话/工具配对错误。预热不混入稳态分布；创建探针和会话数额外记录完整。

最慢样本互斥区间分解（仅用于定位、不作扣除）：provider.transport=110.104209ms，db.batch=38.150918ms，提交后调用线程恢复=db.acknowledge.queue=23.029666ms，db.commit=16.451623ms，db.queue=13.485918ms，db.read.connection=7.524792ms，db.read.query=2.018249ms。剩余区间及完整start/end在原始样本和机器摘要中，合计严格等于271.953375ms。仍需继续定位传输内的返回/调度与组内、提交后等待；当前证据不能证明它们已解决，也不能把它们归为可扣除外部延迟。

## 回归与一致性

`tools/dev/mvnw -B -ntp verify`：216项/50类、306项必跑引用，0失败/错误/跳过，runId `c86c021b-4a5d-4d6a-9fd0-10f64855b9b4`。包括SQLite事务与重启/故障恢复、真实本地HTTP/CLI、截止/UNKNOWN/不重放、记忆与工具边界；实际/v3/api-docs仍19操作/44schema、diff=[]。本轮不重新宣称人工浏览器或真实业务服务验收。tools/dev隔离/报告门禁Python 6项通过。

当前源码/配置313文件与最终短程tar快照逐一SHA-256一致；七次原始样本及退出后SQLite逐一重算。以进入本轮时534份Git可见文件哈希为基线核对授权差异，原失败报告、原始失败/中断工件未覆盖；任务仍87/104完成、17项开放，requirements清单16/16未改，链接/差异/空暂存区和无扩展钩子核查见 `.oryxos-dev/evidence/us5-batch9-read-wakeup-consistency.json`。

## 可自行执行

从仓库根目录执行；JDK路径为本机已有JDK21，只读借用，缓存、工作区和日志仍在个人项目`.oryxos-dev`，不写课程仓库或全局配置。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp verify
./tools/dev/mvnw -B -ntp test -Doryxos.tests.mode=soak -Dgroups=kernel-short-gate
```


针对本轮功能测试：

```bash
./tools/dev/mvnw -B -ntp test -Dgroups=kernel-lease-read,kernel-writer-isolation,kernel-http-read,kernel-tool-transport,kernel-memory-read,kernel-evidence-log,kernel-history,kernel-batching,kernel-active-batch
./tools/dev/mvnw -B -ntp test -Doryxos.tests.mode=soak -Dgroups=kernel-reader-probe
```

短程当前应非零退出；只有该门禁先通过才运行既定四小时入口：

```bash
./tools/dev/mvnw -B -ntp verify -Doryxos.tests.mode=soak -Dgroups=kernel-soak \
  -Doryxos.test.agents=10 -Doryxos.test.sessions=100 -Doryxos.test.duration=PT4H
```

正式运行需保持主机持续可用，不能累计合盖/中断时间。最小探针、功能回归与120秒短程不能替代此命令。原始工件目录：`.oryxos-dev/evidence/short-performance-gate/164f35ac-20a9-4208-a61e-50ef4c425a01/`；回归归档：`.oryxos-dev/evidence/batch9-read-wakeup-verify/`。本轮机器摘要列全部工件相对路径、大小与SHA-256。

## 保留的未完成项

T101仍未达标、没有当前实现的新四小时证据。T027/T028、T042/T044、T051/T057/T058、T070/T071/T072及T098–T100的真实模型、Mem0、可信MCP、真实来源和测试通知目标缺口不变；维护者此前确认配置未准备，本轮没有向任何真实通知目标发送。T073此前本地出口保持已完成；T102–T104未进入。原[322.24ms短程失败](US5-ninth-batch-commit-queue-review.md)、[1011.13ms四小时失败](US5-performance-continuation.md)、[2184.36ms首次四小时失败](US5-performance.md)及中断证据全部保留。不提交、不推送。
