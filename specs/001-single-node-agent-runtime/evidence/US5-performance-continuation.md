# US5 第九批续轮：T101 四小时复测

日期：2026-11-16。**T101 未通过，保持未勾选。** 本轮完成分段计时、存储路径修复、短程与完整四小时复测，但任务内部最大开销仍为 **1011.133125ms > 50ms**。不能宣称最大开销问题已修复。

## 本轮结果

- runId：`1f61d38e-5854-4701-b46f-cb6b3d1ba4ad`；开始时间：`2026-11-16T10:02:23.990+08:00[Asia/Shanghai]`。
- 10 个 Agent、100 个独立活跃会话、每客户端一在途、无思考等待；完整稳态 14400 秒，加 9.542393 秒排空。
- 尝试 57400 次，成功 57400 次，失败 0 次；运行违例 0，末尾 runtimeInFlight=0。
- 内部超过 50ms：49000 个样本；含入口上界超过 50ms：49309 个样本，最大 1012.571541ms。
- 正式四小时用例：1 项、1 失败、0 错误、0 跳过；Maven 退出 1。性能门禁失败如实保留。
- 本地全量回归：203 项/43 类、293 项必跑引用，零失败/错误/跳过，runId `805b0176-a707-4095-b1ed-ad35719fb260`；实际 OpenAPI 19 操作/44 schema 零差异。

| 验收条件（机器摘要键） | 结果 |
| --- | --- |
| sessionCreateP99AtMost200ms | 通过 |
| fourHours | 通过 |
| stabilityAndAudit | 通过 |
| everyTaskIncludingIngressAtMost50ms | 未通过 |
| everyTaskInternalAtMost50ms | 未通过 |

## 与原始失败记录比较

单位均为 ms。两个独立完整运行的负载文件相同；操作系统与背景负载不是严格受控 A/B，不能把数值变化全部归因于某一个修复。

| 指标 | 首次完整四小时 | 本轮完整四小时 |
| --- | ---: | ---: |
| 创建 P99 | 78.538541 | 16.056833 |
| 任务内部 P50 | 352.932167 | 83.391208 |
| 任务内部 P95 | 1128.873417 | 204.799750 |
| 任务内部 P99 | 1476.884042 | 308.029333 |
| 任务内部最大值 | 2184.364750 | 1011.133125 |

首次失败的 56350 个超标样本与全部原始工件保留，见[首次性能报告](US5-performance.md)、[首次机器摘要](US5-performance-summary.json)。本轮没有以平均值或 P99 替代最大值，也没有删去任何超标样本。

## 修复与计时边界

正式 `JpaSessionStore`、`JpaExecutionStore`、`JpaAuditSink` 继续使用真实 SQLite WAL/FULL。序号改为 MAX，历史按选定回合索引读取；V007 只新增索引，旧消息不删除。会话锁按 Session ID 隔离。高频写入采用同一 JPA 事务连接上的绑定 SQL，保持身份、终态、外键和故障暂停校验。

协调器把已排队的最多 128 条记录合并提交，不增加收集等待。等待队列仍最多 256，总未完成容量 257；每条 flush/clear，提交和资源释放后才确认，任何组内失败整组回滚并暂停。Provider 复用所属 HTTP 连接与转换器，逐请求超时独立。

`internal = serverNanos - externalDeductedNanos` 保留原口径，只扣每段 `min(实际外部等待, 配置等待)`。队列、连接、查询、事务提交、审计、外部超出配置的等待均留在内部开销中。新增 `max(serverNanos, clientNanos) - externalDeductedNanos` 为保守上界；它也包含客户端与回环传输开销，不称作精确的纯服务端耗时。

`RuntimeTiming` 默认关闭，仅记录阶段名和单调时钟区间。嵌套阶段不能直接相加；本轮最慢任务用互斥区间重新分解，所有内部区间之和严格等于原内部值，再加入口/客户端差额等于上界。

| 最慢样本互斥区间（前十项） | ms |
| --- | ---: |
| `db.commit` | 428.767124 |
| `db.queue` | 276.121752 |
| `db.batch` | 153.556003 |
| `provider.transport` | 59.044000 |
| `db.read.query` | 36.930127 |
| `db.write` | 14.859622 |
| `external.oversleep` | 10.443168 |
| `db.action` | 9.232291 |
| `provider.client` | 7.067125 |
| `react.tool` | 5.949708 |

这些区间定位的是耗时路径；未做系统级 fsync/调度采样，不能仅凭提交区间推断全部物理原因。剩余开销仍需继续定位和优化，不能通过移除数据库、审计或排队时间达到 50ms。

## SQLite 与连续性核对

审计 verified=`True`；已确认写入缺失 0，损坏/串话记录 0，工具配对错误 0。下表包含单独报告的 20 次预热任务；预热不混入稳态性能分布。

| 数据项 | 数量 |
| --- | ---: |
| sessions | 349 |
| executions | 57420 |
| messages | 229680 |
| llm_calls | 114840 |
| tool_invocations | 57420 |
| overlappingExecutionIntervals | 0 |
| activeSessionsAtEnd | 100 |

JVM 退出、WAL 清空后，以 `mode=ro&immutable=1` 独立检查主数据库：integrity_check=`ok`、外键违例 0、迁移 7 项。逐条任务/外部等待记录重新计算最大值、分位数、身份分布与 5 分钟窗口。

墙钟与单调时钟相邻间隔的最大差为 0.087556 秒，连续性观察结论为 `True`。桌面应用更新曾打断监控，独立测试进程和原始心跳持续运行；未重启负载或累计别的运行时长。

环境：Mac OS X 15.3.1 / aarch64，Java 21.0.13，10 个可用处理器，最大 JVM 堆 4.0 GiB。硬件快照：`hw.model: Mac16,1; hw.memsize: 17179869184; hw.physicalcpu: 10; hw.logicalcpu: 10`。

## 中断记录与保留范围

- `1153581f-2cec-4b65-b2e9-b7ea46b2aabd`：发现写线程启动前等待队列容量边界后主动停止，补红绿测试，不能算四小时。
- `eec3051c-9d11-45bc-984b-e853c83c1cd0`：合盖睡眠后 100 客户端超时，进程最终退出；无正式终态摘要，最后进度只有 8700 秒。睡眠前最大 3747.499417ms 已超标。其 26 个原始工件哈希保留；副本中的 25220 条已确认任务（含预热）无缺失，另有 100 次服务端成功但未获客户端确认，未自动重试或算作已确认。
- 首次完整失败的 9 个原始工件与原报告保持不变。所有修复红绿测试、短程和中断详情见[续轮审查](US5-ninth-batch-continuation-review.md)。

真实模型、Mem0、MCP、通知及 T098–T100 缺口保持开放。这里的模型与工具端点是明确标记的本地可控延迟夹具；生产 Boot/HTTP/SQLite 与驱动在同一 JVM，不是独立发行 JAR 服务器压测，也不构成真实外部服务验收。止于 T101，不进入 T102–T104，不提交或推送。

## 可亲自执行的命令

```bash
cd /Users/jianghaoran/github/OryxOS
oryxos_jdk=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
# 约三分钟短程，只检查计时、功能和审计，不算 T101
ORYXOS_JAVA_HOME="$oryxos_jdk" ./tools/dev/mvnw -B -ntp test -Dgroups=kernel-diagnostic
# 本地全量回归
ORYXOS_JAVA_HOME="$oryxos_jdk" ./tools/dev/mvnw -B -ntp verify
# 独立、完整四小时；需要持续接电并保持开盖，临时防休眠不改系统设置
ORYXOS_JAVA_HOME="$oryxos_jdk" /usr/bin/caffeinate -is ./tools/dev/mvnw -B -ntp verify \
  -Doryxos.tests.mode=soak -Dgroups=kernel-soak \
  -Doryxos.test.agents=10 -Doryxos.test.sessions=100 -Doryxos.test.duration=PT4H
```

每次创建独立 runId 与工作区。四小时未达标应返回失败，不能通过缩短时长、减少会话或放宽阈值使其变绿。不要在长跑期间并行运行其他回归任务。JDK 路径只读使用，工具链、缓存和生成工件均沿用个人项目 `tools/dev` 入口。

## 证据索引

[本轮机器摘要](US5-performance-continuation-summary.json)包含 301 项源码/配置哈希、全部计时分布和审计摘要。原始目录：`.oryxos-dev/evidence/kernel-soak/1f61d38e-5854-4701-b46f-cb6b3d1ba4ad/`。`source-snapshot.tar.gz` 保存清单内 301 个文件，归档内容逐项与快照哈希核对。全量回归归档为 `.oryxos-dev/evidence/batch9-resume-final-verify/`，长跑 XML/日志归档为 `.oryxos-dev/evidence/batch9-resume-four-hour-final/`；退出码文件为 `.oryxos-dev/evidence/batch9-resume-four-hour-exit.json`。

| 原始工件 | bytes | SHA-256 |
| --- | ---: | --- |
| `samples.jsonl` | 777212564 | `5d5ef4ce679646bb64c6154969f3e285f1168d54b0157d41f5f5b5cc1f5b584b` |
| `external-waits.jsonl` | 28781880 | `53f33dad462779540174b79fb6e8da072caa00994095bfef97f1e13e31163444` |
| `environment.json` | 1654 | `c23380f4226181c4f70f9c3058c6dfd7541078ca46f9db7140454ff8ccbbacdd` |
| `summary.json` | 2081 | `abc7f57a01553d703962a42228a01505554e688a407f63fabc3099917ecca525` |
| `sessions.json` | 16062 | `356dd3ed09bdc0f95438663a069af37c335e2d31c234dc8d935abeddcb2b0603` |
| `source-manifest.json` | 47667 | `cfbbce1f5ed87947b1cc8f9fe42445fb7f0146228f2bdd566127d08ee389091f` |
| `observer.jsonl` | 79783 | `d4861f1cb9899bcf162ebe6e7c8b666e9f4d598147d6a8704aee373441537d48` |
| `shutdown-database-check.json` | 282 | `df4a26bed20e784adc1154661aa11ab3fd229fe02cdcc6067545ba8248ff499c` |
| `worst-timings.json` | 410762 | `aa30a01912c4042f8d3524fe9f6296c03df77208461e5b267971459644d9535b` |
| `workspace/oryxos.db` | 281034752 | `7496b4f6bdffb41cce2fee0a4f7dbb564b54ca3c55526a0899c37039a0c6d5c3` |
| `source-snapshot.tar.gz` | 268914 | `f2bb7e0f942b21d5316aa1161112792f7d93efbbfe987ff4c3f8d1c67e42e0b0` |
