# US5 第九批继续：提交与写队列定位

日期：2026-11-16。范围仅 T101；**T101 未完成、未勾选。** 最终独立短程最大内部开销 **322.241791ms > 50ms**，500/500 个样本超标。依照本轮“短程达标后再完整四小时”的指令，**未启动新的四小时复测**。

此前 [2184.36ms 的首次失败](US5-performance.md)、[1011.13ms 的四小时续轮失败](US5-performance-continuation.md)、机器摘要、中断记录及原始工件保持不变。1011.13ms 来自先前代码，不能作为本次修改后的四小时证据。本轮也不能宣称端到端性能已经改善。

## 已完成的定位与修复

1. 新增 `kernel-short-gate`：10 Agent、100 独立活跃会话、120 秒稳态、原 25 秒外部延迟、真实 Boot/HTTP/SQLite 完整审计。固定要求创建 P99≤200ms，原内部最大值和含入口上界都≤50ms；失败真实返回非零。保留原 `kernel-diagnostic` 的功能诊断标签，两者不混淆，短程通过仍不算 T101。
2. 单写协调器在处理事务期间继续接纳已经排队的记录，单组仍最多128条，不等待凑批。原实现在首条开始后冻结组成员，新测试复现其他记录进入时首条已先提交；现保证同组在提交前均不确认、失败整组回滚并先暂停。256 等待槽、257 总容量及剩余预算不变。晚加入记录的计时从实际接纳时刻开始，排队时间完整保留。
3. 写请求只等待持久结果，移除“开始时唤醒后再次等待提交”的中间 future。新增 `db.acknowledge.queue` 记录提交结果可用到调用方恢复执行的延迟；已开始事务在超时或中断后仍必须收尾才返回，中断标志恢复。
4. 增加 `db.statement.prepare/bind/execute` 分段。参数化 SQL 仅在当前写线程的当前事务内复用，缓存最多32条语句，超出容量仍正常执行并立即关闭；每次清空旧参数并重新绑定，事务结束全部关闭。不缓存审计数据、不提前确认，不跨连接或线程持有语句。100 条写入仅有两种 SQL 的测试，准备次数由100降到2，成功和回滚分支均验证。
5. JFR 基线采样发现646个不同的 HttpClient 选择线程。Provider 每次创建 OpenAiApi 时，未使用的 WebClient 也创建独立传输。[锁定版本 Spring AI 1.1.8 源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/api/OpenAiApi.java)及本地 JAR 字节码均确认此构造路径。现让同步和未使用的流式包装器共用 Provider 所有的传输与编解码器；100个包装器新增选择线程从100降为0。仍只提供阻塞调用，逐请求超时独立，注册表关闭时释放传输。

6. 在当前代码的额外JFR短程中，审计脱敏器 `Redactor.redact` 出现73次监视器竞争，最大59.282875ms；秘密检查另有24次竞争。原实现全方法加锁，每次还排序秘密和编译正则。现注册时原子发布按长度排序的不可变秘密快照，读取不争用注册锁；静态Pattern共享，Matcher按调用创建，投影规则不变。100并发、每人12次接近32KB审计文本的最小探针从133.869ms降至20.680875ms；这是该探针的结果，不是HTTP链路结果。新增并发注册/重叠秘密/null/URL/凭证投影验证。

Session/Execution/Audit 均使用正式 SQLite 实现；WAL、synchronous=FULL、完整消息和审计、外键与终态守卫未放宽。未改固定负载文件、19操作/44schema契约、示例或 website。所有分段仍嵌套在原请求计时内，没有扣除队列、DB、审计、提交后唤醒或外部超出配置的等待。

## 先测试证据

所有原始日志和本次 XML 归档在 `.oryxos-dev/evidence/`，以下 runId 均已核对。

| 测试 | 失败基线 | 修复验证 |
| --- | --- | --- |
| 活跃事务接纳到达请求 | `532e603c-16d6-41fc-9c46-8cd853e985d7`，提交前期望0行却读到1行 | `35c1cd5c-3cfd-44ce-8f3e-39944982a217`，8项通过 |
| Provider 资源数量 | `46486c1c-a89b-4e21-8322-6f814f7a170d`，新增100个选择线程 | `723fad6d-4b2a-44f8-b5b8-1b06889d0b7c`，16项通过（15项必跑引用） |
| 提交后唤醒计时 | `95cad8a7-f7b2-4d92-9819-abe0e6fdb208`，缺少分段断言失败 | `0de15d33-44b5-4ba9-bab2-daa611139656`，16项通过，含已接纳事务的截止/中断收尾 |
| SQL 分段计时 | `2ceefcdd-7a97-45eb-ae76-d59f16be3662`，缺少三个 SQL 分段 | `7b251c1d-3a3b-4d09-b967-d87bee255190`，5项通过 |
| 审计脱敏并发 | `bd8af57b-d453-4f3e-a040-3e922bcffdf9`，最大133.869ms，50ms断言失败 | `71c5d213-f497-487b-888c-73c5da759f05`，同探针最大20.680875ms通过；功能验证`b82bef67-9706-47fd-9317-992b793a1cd2`通过 |
| 事务内 SQL 复用 | `2d05e0d2-c8b7-40bb-b9bf-a27a76304e2f`，期望2次准备、实际100次 | `efab2b3b-2c26-42c9-bff4-c48ccbd630f2`，16项通过，含存储故障、历史和事务边界 |

测试调整保留了全部提交/回滚断言：预排队批次改用受控执行器，计时用例阻塞上一事务已提交后的观察点，使后一请求明确进入新事务。初次调整计时夹具遗漏此边界、SQL计时夹具重复建表产生的失败日志均保留，分别作为夹具错误，不充当预期行为失败证明。

脱敏探针最初用约1.6KB文本运行已通过（`51378d25-6f03-436f-86cb-66b8d8a75457`）；它没有复现症状，随后使用接口允许的32KB以内文本建立上述失败基线。该小文本结果保留，不作为失败证明。

最小写入探针 `kernel-writer-probe` 使用真实 SQLite，100调用者、每次12条写入、3个突发批次：基线带JFR时最大34.506750ms；最终 `844f0487-29c4-424b-848b-6af325c83e44` 最大29.180334ms，300样本通过。它只用于缩小问题范围，不能证明完整 HTTP 链路或 T101 达标；两轮采样条件不同，不据此推断性能提升比例。

## 独立短程门禁结果

每轮均500任务成功、0失败任务、0稳定性违例、完整审计通过；每轮JUnit均1项失败、0错误、0跳过。没有挑掉首轮、最大值或超标样本。JFR轮仅用于诊断。

| 阶段 | runId | 内部最大 ms | 超过50ms |
| --- | --- | ---: | ---: |
| unchanged storage baseline | `36db1fe8-64a6-4df8-80b9-a8cfefe3964e` | 252.655750 | 500/500 |
| profiled baseline | `b18aaa4c-6860-4ab4-b278-aeaff648d87c` | 252.559541 | 500/500 |
| active batch | `bc91b875-b568-486c-ab0b-a7cd6ffc50d7` | 209.738334 | 500/500 |
| owned Provider transport | `52a40432-7900-45c9-b8e3-db6dcc22f2d4` | 477.696875 | 496/500 |
| one outcome wait | `17bfb9d3-cc7a-49da-b42f-e631340da387` | 427.468750 | 500/500 |
| transaction statement reuse | `4599be7c-5934-4c2c-9f26-3a7f0e869b33` | 300.044542 | 500/500 |
| current code JFR attribution | `3a47ede6-c317-4d64-9037-e7b5bfbafc44` | 539.571834 | 500/500 |
| concurrent audit redaction | `c02c797a-9379-4d3a-acc9-fadef7c57228` | 322.241791 | 500/500 |

最终原内部 P50=170.519209ms、P99=306.210167ms；含入口上界最大=331.402250ms。不同轮次存在JVM编译和主机负载差异；不能以不同轮次的最大值相减证明性能改善；最终仍未达到50ms。

最慢任务按互斥区间重算，以下为前十二项（嵌套分段不能直接相加）：

| 区间 | ms |
| --- | ---: |
| `db.batch` | 60.438210 |
| `db.read.query` | 55.311001 |
| `db.acknowledge.queue` | 42.176457 |
| `provider.transport` | 38.154333 |
| `framework.unattributed` | 34.529334 |
| `http.dispatch.queue` | 18.328083 |
| `db.commit` | 17.066665 |
| `agent.dispatch.queue` | 11.602833 |
| `react.tool` | 10.160001 |
| `db.queue` | 8.785123 |
| `db.statement.execute` | 7.897831 |
| `external.oversleep` | 7.477417 |

提交、写队列已有更细证据，剩余读查询、HTTP分派、提交后唤醒和传输开销仍超出目标。额外JFR短程还观察到Markdown记忆加载的共享锁竞争，最大44.891250ms，以及19次GC暂停、最大35.449667ms。这些属于归因线索，不能单凭时间相加推断因果；本轮未修改记忆一致性或GC配置。JFR还观察到测试夹具日志锁竞争（16个监视器竞争事件，最大约39.3ms），没有据此改写计时或移走审计开销；本轮未修改夹具的日志锁。未做系统级fsync采样，不能把所有提交区间解释为物理磁盘原因。

## 回归与完整性

- 本地 `verify`：207项、44类，0失败/错误/跳过，297项必跑引用；runId `33e189b6-2685-4292-8d49-234b3ec91245`。HTTP、截止、故障恢复、跨进程、CLI和管理台契约均纳入现有本地回归；本轮不额外声称新的人工浏览器或发行JAR验收。实际 OpenAPI 仍为19操作/44schema，diff为空。
- 最终短程SQLite独立只读核对：111会话、520执行、2080消息、1040模型调用、520工具调用（含20预热）；所有确认执行ID、终态和完成标记一致，integrity_check=ok、外键违例0、WAL为0。无已确认写入缺失、串话/损坏、工具配对错误或重叠。
- 最终短程目录保存304个源码/配置文件的哈希及内容快照。原始样本与外部等待逐条重算；[机器摘要](US5-performance-commit-queue-summary.json)记录结果及工件SHA-256。一致性记录：`.oryxos-dev/evidence/us5-batch9-commit-queue-consistency.json`。
- 任务保持87/104完成。T101、T098–T100和此前真实模型、Mem0、MCP、通知缺口均保留；不进入T102–T104，不提交或推送。

## 可亲自执行

```bash
cd /Users/jianghaoran/github/OryxOS
oryxos_jdk=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
# 定向功能回归
ORYXOS_JAVA_HOME="$oryxos_jdk" ./tools/dev/mvnw -B -ntp test \
  -Dgroups=kernel-active-batch,kernel-batching,kernel-timing,kernel-provider-resources,kernel-transport,kernel-redactor
# 独立短程性能门禁，当前预期非零退出；不能作为四小时验收
ORYXOS_JAVA_HOME="$oryxos_jdk" ./tools/dev/mvnw -B -ntp test \
  -Doryxos.tests.mode=soak -Dgroups=kernel-short-gate
# 最小写入/脱敏诊断，不能替代完整链路
ORYXOS_JAVA_HOME="$oryxos_jdk" ./tools/dev/mvnw -B -ntp test -Doryxos.tests.mode=soak -Dgroups=kernel-writer-probe,kernel-redactor-probe
# 全量本地回归
ORYXOS_JAVA_HOME="$oryxos_jdk" ./tools/dev/mvnw -B -ntp verify
```

`kernel-short-gate`、`kernel-writer-probe`、`kernel-redactor-probe` 为显式soak诊断组；普通local命令不运行它们，固定四小时 `kernel-soak` 也不会混入这些诊断用例。这是分组隔离，不是跳过已要求的短程用例：本轮短程已实际失败并保留XML。未来只有短程达标后，才执行原四小时命令（10/100/PT4H），见[先前复测命令](US5-performance-continuation.md#可亲自执行的命令)。所有运行沿用 tools/dev 和独立工作区，JDK只读使用，无全局环境或ACSP配置变更。
