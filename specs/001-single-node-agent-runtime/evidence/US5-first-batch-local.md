# US5 第一批：持久化、故障恢复与正式装配

2026-11-13；仅个人仓库 `/Users/jianghaoran/github/OryxOS`。范围为T073的存储/故障/重启子集与T074–T081，止于T081。
所有模型和接收方都是回环协议夹具；Session/Execution/Audit、迁移、运行标记和恢复均为正式实现，SQLite为实际文件。
没有真实模型、Mem0语义、真实通知接收方或可信外部MCP的新验收结论。

## 环境与执行入口

- JDK21.0.13+9只读复用已有安装；通过`ORYXOS_JAVA_HOME`选取。
- Maven3.9.16、Boot3.5.16、Hibernate6.6.53.Final、SQLite JDBC3.51.3.0，保持项目锁定版本。
- Maven/JVM使用`tools/dev`隔离settings、缓存、home和临时目录；测试工作区均临时化，主机测试TZ=UTC、工作区Asia/Shanghai。
- 新增Java源码使用已有`.oryxos-dev/google-java-format-1.24.0-all-deps.jar`格式化，不安装全局工具。
- 开始前454个已有项目文件的SHA256快照保存在`.oryxos-dev/evidence/us5-batch1-before.json`；最终差异另存comparison，未删除已有文件。

```bash
cd /Users/jianghaoran/github/OryxOS
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp test -Dgroups=storage-faults
./tools/dev/mvnw -B -ntp test -Dgroups=restart-recovery
./tools/dev/mvnw -B -ntp clean verify
python3 tools/dev/test_build_environment.py
python3 tools/dev/test_report_gate.py
python3 tools/dev/verify-foundation.py
```

`storage-faults`登记16项，`restart-recovery`登记其中5项；两组重叠不会重复执行。
`us5-http`仍未启用，不能以本批小集合代替最终HTTP验收。没有Disabled、Assumptions、skip或空测试代替通过。

## 测试先行与结果

实施前通过已有Boot入口准备三项可编译测试，运行`test -Dgroups=storage-faults`：3失败、0错误、0跳过。
失败分别为没有正式SessionStore、遗留run没有暂停、坏库没有恢复服务；日志`us5-batch1-red.log`。
其后扩充实际SQLite故障注入、接收方计数、JVM进程退出和重启夹具。初始阶段失败不能作为验收成功。

16项本批用例以及既有US2循环28项的定向回归共44项通过，run ID：
`9537352e-d037-4669-b5f9-68417772d297`，日志`us5-batch1-regression.log`。
全量与发行检查的最终计数见本文件末尾和[阶段审查](US5-first-batch-review.md)。

| 要求/任务 | 用例及实际断言 |
| --- | --- |
| FR-018 / T074 | `upgradingV2PreservesMemoryLedgerAndChecksums`：先建V001/V002并写既有记忆，再升级至V005；原文保留，五个迁移登记齐全。V001/V002脚本未改。 |
| FR-008/010/012 / T074–T075 | `structuredMessagesPaginationModelSnapshotAndForeignKeysPersist`：53完整turn/106消息重开后保留；最近100条seq=7–106升序，beforeSeq=7得到1–6；孤儿execution消息被真实外键拒绝，原106条保留；私有opaque仅内部重放。 |
| RV-03 / T075 | `emptySessionSnapshotArchiveAndReadonlyQueriesSurviveRestart`：空会话保留requested-model；文件改为changed-model并重启，旧会话拒绝续聊且模型0请求；归档时间与身份保留；缺库只读查询失败且不创建数据库。 |
| FR-038/039 / T076 | `auditsRetainFailuresCorrectionLinksDenialsUnknownsAndNullUsage`：原ERROR保持，补RESOLVED和双向关联；DENIED/UNKNOWN/NOT_EXECUTED保留；实际model、NULL token、脱敏参数可查，失败改成功被拒绝。 |
| FR-040 / T075/T077 | `userInsertFailureRollsBackSessionExecutionAndMessageBeforeAnyIo`：SQLite触发器拒绝USER插入，三表均0行、模型/接收方0请求、闸门暂停。 |
| FR-033/040 / T077 | `writerQueueIsBoundedAndWaitingUsesRemainingBudget`：1写者+256等待者后拒绝额外提交；30ms预算排队失败，低于1秒退出；非无限排队。既有DeadlineRaceTest继续证明STARTED后重新检查截止和迟到收尾。 |
| US5-A7 / T077/T080 | `startedFailureSendsNothingAndRequiresExplicitAcknowledgement`：STARTED插入失败模型0请求；修复触发器不解锁；错误ID/未确认拒绝，正确确认和重复确认不发模型，随后新任务成功。 |
| US5-A7/RV-05 / T079–T080 | `acceptedSideEffectAndFailedResultNeverReplayAcrossRestart`：接收方已确认1次，结果UPDATE失败；返回PARTIAL_RECORDING_FAILURE、INCOMPLETE；故障ID跨重启一致，内存观察到的SUCCESS保存在故障资料，原缺失审计仍不伪造；恢复前后接收始终1次。 |
| US5-A7 / T078–T080 | `runEvidenceStillPreventsRestartBypassWhenDatabaseAndFaultWritesFail`：接收后关闭存储并用非空目录阻止fault写入；原run/STARTED仍阻止重启绕过，明确资料不完整；修复并确认后不重放。 |
| US5-A7 / T080 | `recoveryWaitsForInflightActionAndFinalRecording`：接收方阻塞期间另一执行记录失败；在途恢复被拒绝；释放并完成最终记录后方能恢复，模型和接收方均只1次。 |
| US5-A7 / T080–T081 | `repairedStorageRebuildsTheOriginalPortsWithoutMemoryFallback`：坏库诊断存活；测试人工保留坏库副本并修复路径；确认后原Session/Execution端口对象继续使用新资源，普通任务成功且记录可查。 |
| FR-018/038 / T075–T081 | `actualJvmChatResumesStructuredHistoryAndAuditsAfterRestart`：两个独立JVM走生产CLI，首次2次模型/1次POST，重启续聊后累计3次模型/仍1次POST；同session的6条消息和2执行恢复；工具ID和opaque原样重放，但公开历史/审计/日志不包含opaque或密钥。正常退出清理本次run。 |
| FR-040 / T078 | `actualJvmCrashLeavesStartedAttemptAndRequiresRecoveryWithoutReplay`：独立JVM持久化execution/USER/STARTED后halt(23)；旧run内容不变；重启为INTERRUPTED/UNKNOWN且暂停；人工确认后模型0请求，再次干净重启READY。 |
| T078/T081 | `abandonedRunPausesWithoutReplacingOldEvidence`、`productionUsesPersistentPortsAndVersionedTables`、`unreadableDatabaseKeepsDiagnosticAndRecoveryServiceAvailable`：旧标记保持，默认Bean均Jpa实现，坏库仍有恢复端口和独立fault；未创建scheduled_tasks。 |

测试execution/session/incident使用应用生成UUID，断言查回相同ID；进程测试的execution ID由子进程写入临时`probe-execution.txt`再由父进程查库。
不是固定示例ID冒充真实执行。详细JUnit时间、run ID及每方法结果保存在当前Surefire XML及隔离证据归档。

## 运行行为与交付限制

- 正式SessionManager继续负责身份、模型快照、忙碌、归档与完整turn；Jpa适配器只负责存取。新会话+execution+USER在一个短事务提交。
- WorkspaceWriteCoordinator覆盖正式会话、执行、审计和SQLite/Mem0本地账本写入；写失败先关全局闸门，再尽力写独立fault和incident。
  STARTED提交后再由统一dispatcher重新核验许可；网络等待不持有事务，实体不跨工作线程。
- 运行时恢复会重建工厂/连接、保留缺口与操作者说明、清理已确认旧证据，然后开闸。已有实例的存储供应器动态取得新资源。
  不把重启或磁盘自动变可写当作确认；诊断不调用模型/工具；无法证明的详情始终`detailsComplete=false`。
- 正式`chat`已可运行、跨进程续聊；无需内存Store、Mem0或MCP。需可写初始化工作区、有效Agent、Provider地址/账号可用模型与密钥引用。
- `serve`/`gateway`已共用持久引擎和同根进程锁，仍无业务HTTP、调度、管理台。轻命令仍不启Spring。
- 本批只交付RecoveryPort内部服务。人工恢复已由真实SQLite测试验证；面向使用者的HTTP/管理台操作入口待T088/T092，没有新CLI恢复命令。
  暂停后不能删除标记或反复重启来绕过确认。完整管理体验尚未交付。

## 仍未验收

T073的19个HTTP操作、44个schema的真实端口业务断言、HTTP 60秒截止、调度/页面/配置整合仍未完成。
T093–T095是后续完整验收出口，本批没有提前勾选。调度状态恢复、三个真实场景、4小时性能、新用户路径均保留。
T027/T028、T042/T044、T051/T057/T058、T070/T071/T072保持原有真实前提与出口缺口。
真实模型凭证/可用模型、Mem0服务与镜像digest、所选通知目标及可信MCP配置，分别按原US1–US4证据补齐。

## 最终验证结果

`clean verify`最终run ID为`49d69d8d-276a-4452-96f6-7c99ba297ff3`：118测试、30类、0失败、0错误、0跳过，九个子模块构建成功。
135项报告门禁登记包含跨组重复，唯一测试数仍是118。XML、日志、摘要归档于`.oryxos-dev/evidence/us5-batch1-full-verify/`。

`verify-foundation.py`通过五组打包黑盒检查，并断言坏库暂停时EOF输入的chat返回7。
首次复验发现该EOF路径误返回0；在正式chat入口增加闸门检查后，重新完整构建和黑盒复验通过，原脚本断言未降低。
`test_build_environment.py`的5项及`test_report_gate.py`的1项通过。
Boot JAR及8个内嵌Oryx模块JAR未包含早期内存Store、测试装配/进程夹具或`secrets.local.yaml`。
这些检查仅支持本批交付，不提前完成T103/T104。
