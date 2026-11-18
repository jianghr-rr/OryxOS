# US5 第九批继续：模型传输、组内等待及提交后恢复

2026-11-16，范围止于T101。**T101仍未通过、未勾选。** 最终无剖析器严格短程内部最大201.795500ms、P99=199.531125ms，500/500超过50ms；入口保守上界207.072667ms。用例1失败/0错误/0跳过，Maven退出1。没有启动新的四小时复测，功能修复与回归不能替代性能验收。

## 测试先行的修改

1. 为现有JDK模型HTTP路径添加可选计时装饰器：请求体编码、响应头前等待、响应体读取/消费、关闭。无观察器时返回原请求；保留流式写入、可重复性标记、响应关闭与截止。真实延迟HTTP测试先因缺少分段失败，补齐后通过，返回与STARTED/终态审计投影保留。计时不采集正文或密钥。
2. 写协调器新增`db.group.before`、`db.group.after`，区分本动作前等待与本动作完成后等待其他组成员。真实SQLite测试让第二成员受控等待，证明第一成员仍等待整组提交后才得到成功确认。单写者、257总容量/256队列、128组上限、无主动收集延时、FULL/WAL与整组回滚不变。全部等待继续计入50ms。
3. 本地Spring源码核对及100个真实请求测试发现：默认JdkClientHttpRequestFactory为请求体序列化分配100个平台线程。改为Provider拥有并关闭的虚拟线程执行器，100请求编码记录中平台线程降为0。复用原JDK HTTP连接与逐请求deadline，不引入新transport、不隐式重试。
4. 对带1ms阈值的中间版本JFR剖析发现40次HttpServer请求体读取占住虚拟载体（最大16.790791ms），以及309次负载驱动turn监视器竞争（最大20.025209ms）。两个实际子JVM测试先在唯一载体被占用时失败，再验证独立运行端虚拟任务能推进。受控外部服务与负载驱动分别使用预启动128平台线程/256队列；100闭环客户端和创建探针仍全部运行，执行器按原生命周期关闭。原同步证据写入/flush和外部等待全部保留。**这是消除测试设施与运行端共享载体的干扰，不是生产审计优化，也没有扣除测试设施的耗时。**

最终源码没有修改`load-profile.json`、`SoakTiming.java`、`ShortPerformanceGateIT.java`、`KernelSoakIT.java`。模型、队列、DB、审计、组内等待、提交后线程恢复、外部超睡和HTTP入口上界仍按原规则计量。没有内存持久化替身、异步延迟审计或按百分位放宽最大值。

## 红绿记录与诊断限制

下表为用例总数/失败/错误/跳过；原XML和日志哈希见[机器摘要](US5-performance-transport-batch-summary.json)。

| 归档名 | runId | 测试/失败/错误/跳过 |
| --- | --- | --- |
| batch9-provider-timing-red | `2e27bcad-435f-4e34-95ff-67158e606bbf` | 1/1/0/0 |
| batch9-provider-timing-green | `7b3eeb2a-3e2c-4fd5-80b4-719db31d1bfb` | 17/1/0/0 |
| batch9-provider-timing-verified | `c1232327-6d6f-4bef-bc77-6dd40c97389c` | 17/0/0/0 |
| batch9-group-timing-red | `7312036a-991c-4000-b4bb-82c1c736133b` | 1/1/0/0 |
| batch9-group-timing-green | `99cb6a7f-6783-45be-8bd5-d1c0517e6a7c` | 10/0/0/0 |
| batch9-provider-probe-red | `826ae588-57b7-4125-9952-031e321a3353` | 1/0/0/0 |
| batch9-provider-body-worker-red | `994680b9-8545-425b-9f7a-cd02401c8b40` | 2/1/0/0 |
| batch9-provider-body-worker-green | `3badbf59-472a-4ccd-8f8b-c5e541e22ec8` | 18/0/0/0 |
| batch9-external-carrier-red | `73aa08f2-c2d2-4928-beb0-4554f2f696c7` | 1/1/0/0 |
| batch9-external-carrier-green | `8d2263a8-55ac-4fb2-aa3d-c25e1acf1a97` | 6/0/0/0 |
| batch9-driver-carrier-red | `139c343e-8497-41e0-b983-f21c71a0900a` | 2/1/0/0 |
| batch9-driver-carrier-verified | `e2ce7f2f-8bb6-48d4-990e-c46a8935a6da` | 7/0/0/0 |

`provider-timing-green`虽然名称含green，实际仍有1项失败：新增测试误把每次模型调用STARTED+终态的两条审计投影只算一条；按既有契约改为3次调用6条投影后独立verified重跑通过，失败日志没有覆盖。`provider-probe-red`也只是初始文件名：请求体修复前的独立100调用方探针实际通过，最大36.613584ms，未复现全链路问题；它没有SQLite，不能替代短程。`driver-carrier-green.log`因误用未登记标签被tools/dev在运行前拒绝，改用正确标签的verified记录才是成功证据。没有跳过失败断言或将门禁拒绝记作通过。

## 四次严格短程

每次10个Agent、100独立会话，固定120秒稳态加排空；每任务两次10秒模型等待及一次5秒工具等待，真实SQLite完整审计，500稳态任务和20预热任务。预热不混入稳态。只扣min(实际外部sleep,配置sleep)，超睡继续计入。JFR只附加在第二次；其余未附加。代码/线程设施/运行时状态不同，不以数值差异宣称受控A/B性能提升。

| 条件 | runId | 内部最大ms | 入口上界最大ms |
| --- | --- | ---: | ---: |
| transport and group timing | `b494a4ef-5571-4948-99cd-9dcceea25456` | 342.726042 | 357.213125 |
| owned virtual request body workers; JFR attached | `a375aad2-a0d7-44ce-a490-74012cc0986c` | 271.300667 | 290.197084 |
| isolated external service workers | `fdee48c9-7d70-4c2d-85f3-c423e328ec16` | 264.177917 | 291.442167 |
| isolated external service and load driver; no JFR | `c8e5b2e7-90f8-4ac7-a1de-9b41fef371f7` | 201.795500 | 207.072667 |

最终runId `c8e5b2e7-90f8-4ac7-a1de-9b41fef371f7`，开始于2026-11-16T17:28:22.456073+08:00[Asia/Shanghai]，稳态加排空125.876986秒。创建P99=15.291125ms，500成功、0任务失败、0稳定性违例，峰值100在途。退出后以只读immutable方式独立检查真实SQLite：integrity=ok、0外键违例；111 sessions、520 executions、2080 messages、1040 llm_calls、520 tool_invocations。已确认executionId全部存在、终态和记录完整，无串话、重叠或工具配对错误。

最慢样本互斥区间分解如下，仅定位、不作扣除；完整分段合计严格等于201.795500ms。

| 区间 | ms |
| --- | ---: |
| `provider.http.headers` | 52.355001 |
| `db.group.before` | 26.868249 |
| `db.queue` | 21.355542 |
| `db.acknowledge.queue` | 19.968833 |
| `react.tool` | 17.019708 |
| `db.group.after` | 14.343374 |
| `external.oversleep` | 14.156084 |
| `db.read.connection` | 13.318958 |
| `db.commit` | 10.751541 |
| `framework.unattributed` | 4.478791 |

模型响应头前等待仍含发送、服务端排队、返回传输及线程恢复，现有边界不能将52.355001ms进一步归因为单一网络问题。组内等待合计41.211623ms，写队列21.355542ms，提交后恢复19.968833ms仍未解决；不能将它们排除后宣称通过。JFR属于中间版本，最终代码未再次附加剖析器，不能据此声称所有载体竞争已消失。下一步需要把最终版本的调度与组成员时序关联，继续用相同负载验证。

## 回归与一致性

`tools/dev/mvnw -B -ntp verify`：221项/51类、311项必跑引用，0失败/错误/跳过，runId `e63294da-0378-46f7-bec8-eb3e597e5115`。覆盖持久化、重启/故障恢复、本地HTTP/CLI、取消/截止、UNKNOWN与无重放、工具/记忆边界。实际/v3/api-docs仍19操作/44schema，差异为空。tools/dev隔离和报告门禁Python 6项通过。本轮未重新宣称人工浏览器或真实模型/通知验收。

最终源码/配置317文件与该短程tar快照逐一SHA-256一致；四次样本分布、外部扣除、退出后SQLite与各自源码tar独立复核。以进入本轮545份Git可见文件哈希为基线核对授权差异；旧报告、失败和中断工件保留。任务仍87/104完成、17项开放，requirements清单16/16未改；链接、差异、空暂存区及扩展钩子核查见`.oryxos-dev/evidence/us5-batch9-transport-batch-consistency.json`。

## 可自行执行

从仓库根目录运行。只读借用已有JDK21，缓存/测试工作区/日志仍在个人项目`.oryxos-dev`，不写官方实验仓库、全局环境或ACSP配置。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp verify
./tools/dev/mvnw -B -ntp test -Dgroups=kernel-provider-timing,kernel-provider-resources,kernel-group-timing,kernel-external-isolation,kernel-soak-harness
./tools/dev/mvnw -B -ntp test -Doryxos.tests.mode=soak -Dgroups=kernel-short-gate
```


短程当前应非零退出。只有严格短程全部通过，才可运行既定四小时入口：

```bash
./tools/dev/mvnw -B -ntp verify -Doryxos.tests.mode=soak -Dgroups=kernel-soak \
  -Doryxos.test.agents=10 -Doryxos.test.sessions=100 -Doryxos.test.duration=PT4H
```

保持主机持续可用，不累计合盖或中断时间。原始短程工件位于`.oryxos-dev/evidence/short-performance-gate/c8e5b2e7-90f8-4ac7-a1de-9b41fef371f7/`，回归归档位于`.oryxos-dev/evidence/batch9-transport-batch-verify/`。机器摘要列出原始样本、SQLite、源码快照、红绿记录、JFR和日志的大小及哈希。

## 未完成项保留

T101不通过，没有当前实现的新四小时证据。T027/T028、T042/T044、T051/T057/T058、T070/T071/T072以及T098–T100的真实模型、Mem0、可信MCP、真实数据源和测试通知目标缺口不变；维护者已确认配置尚未准备，本轮没有向真实通知目标发送。T073此前本地出口保持，T102–T104未进入。[271.95ms短程](US5-ninth-batch-read-wakeup-review.md)、[322.24ms短程](US5-ninth-batch-commit-queue-review.md)、[1011.13ms四小时](US5-performance-continuation.md)、[2184.36ms首次四小时](US5-performance.md)及中断证据原样保留。不提交、不推送。
