# US5 存储、故障与跨进程恢复验收（T093）

2026-11-13，第六批。**T093通过。** 使用正式SQLite及独立发行JAR JVM，经实际HTTP/CLI进入；模型和通知目标是回环协议夹具，不是公网真实模型/通知验收。没有内存Store替代。

## 新增独立进程用例

`CrossProcessAcceptanceIT`通过`RuntimeProcess`启动独立serve/gateway/chat/query进程。子进程使用项目隔离Java user.home/tmp和UTC主机时区，清除继承环境，仅本地测试秘密文件提供夹具凭证。
测试可用classpath运行；本批显式传`oryxos.acceptance.jar`运行正式发行包，XML记录该属性，不能把上下文重建写成跨进程。

| 用例 | 操作与实证 |
| --- | --- |
| historyMemoryAuditsScheduleAndEmptySnapshotsSurviveDistinctJvms | serve中建立空会话、活跃会话和归档会话；实际save_memory分别保存CORE/ARCHIVAL、模型/工具审计落库，停用调度手动运行留历史。停止后改文件模型，再以不同PID的gateway启动：历史4条、原执行2条模型/1条工具审计、两种记忆、停用状态和触发历史均恢复，无启动时新调用；空会话保持旧模型，新会话用新模型；身份/归档/模型冲突拒绝。CLI session list还能查原会话。 |
| httpAdmissionRecordFailureStopsAllIoAcrossRestartUntilExplicitRecovery | SQL触发器拒绝首次llm_calls写入；实际HTTP返回500及INCOMPLETE，模型0、通知0；调度新执行503。修复触发器仍暂停；独立chat进程返回7，重启serve仍暂停。明确HTTP确认恢复READY，模型/通知仍0，不自动重做原任务。 |
| realNotifyAcceptedOnceWithBrokenFaultEvidenceRequiresHttpRecoveryAfterProcessRestart | 正式notify工具向回环webhook发送一次并得到HTTP确认；接收方随后注入工具结果及incidents写失败，fault.json路径被本地目录阻挡。HTTP返回记录失败，新的Agent和调度执行均503；模型1、接收1。修复SQL后仍暂停；停止并重新启动JVM，旧run内容保持，currentIncident与incidentId一致、detailsComplete=false、RUN_MARKER来源可见；只恢复可写不解锁。ack=false拒绝，维护者清理夹具故障并明确确认后READY，重复确认及再重启无重放，原执行仍INCOMPLETE。 |

初期夹具问题（通知URL须用秘密引用、不存在next_run_at列、CLI须显式user，以及同时归档/模型变更的错误优先级）已按实际契约修正，不算产品能力缺失的红基线。归档拒绝在模型变更前独立断言，重启后同时验证旧模型不可续聊；未放宽身份或归档要求。

## 与既有回归合并核对

综合us5-http中的RestartRecoveryIT、RecordingFailureIT、PersistentStorageContractTest、ScheduleIntegrationTest、ScheduleStorageContractTest均通过。覆盖实际JVM异常halt遗留STARTED、故障标记不可写、记录前零IO、迟到动作与在途恢复拒绝、损坏库、资源重建无内存兜底、调度防重叠/不补跑及保存历史。新的三项补齐真实HTTP/CLI和正式notify跨进程边界，没有把同JVM重建上下文冒充新证据。
第一至第五批历史证据原样保留；本批只用新增及重新运行的结果签收T093。

## 运行与证据

- 独立发行JAR定向：3测试、0失败/错误/跳过。run `1f7d9fb9-8373-4093-8548-dc9fe574dc87`，XML/日志/summary在`.oryxos-dev/evidence/us5-batch6-packaged-process/`。
- 被测JAR SHA256：`d5f4dad5be8f11bd2615b16d5660f77d0a3e81a41dca30e4518103c51187c8e5`。运行时代码本批未改；该JAR来自第五批最终产物。
- 综合us5-http：70项中69通过、1项OpenAPI对照失败；存储/恢复用例均通过。run `3c0c4eab-0235-44a0-9c2e-0811e10c473d`，存档`.oryxos-dev/evidence/us5-batch6-http/`。
- 最终回归另输出脱敏逐请求记录（含进程PID、实际执行/会话/故障ID）及三场景计数，见[第六批汇总](US5-sixth-batch-local.md)。所有工作区为项目.oryxos-dev下临时根，JUnit负责清理自身进程和临时数据。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
# 普通独立JVM入口：
./tools/dev/mvnw -B -ntp test -Dgroups=us5-process
# 已有完整发行JAR时，再核对真实分发包：
./tools/dev/mvnw -B -ntp test -Dgroups=us5-process \
  -Doryxos.acceptance.jar=/Users/jianghaoran/github/OryxOS/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar
```

T093通过本身不关闭T073/T094/T095；第六批续作另完成这些出口，见[续作记录](US5-sixth-batch-continuation.md)。Mem0语义、真实通知接收、真实模型和可信MCP等原有验收仍未完成。
