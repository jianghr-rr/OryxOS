# US5 第二批：调度本地证据

2026-11-13；范围为T082、T083。前置T081已完成，本批先测试，再实现文件调度定义、持久运行状态、自动与手动触发。
不进入T084及后续业务HTTP、管理台或完整CLI任务；T073/T093/T094仍需未来HTTP能力才能完成。
所有模型服务和接收方均为回环夹具，Session/Execution/Audit/Schedule使用实际SQLite及正式生产装配，没有内存降级。

## 可执行入口与测试先行

```bash
cd /Users/jianghaoran/github/OryxOS
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp test -Dgroups=us5-schedules
./tools/dev/mvnw -B -ntp test -Dgroups=us5-schedules,storage-faults
./tools/dev/mvnw -B -ntp clean verify
python3 tools/dev/verify-foundation.py
```

使用锁定的JDK21、Maven3.9.16、Boot3.5.16、Hibernate6.6.53.Final、SQLite JDBC3.51.3.0，未改POM或版本。
JDK只读复用；Maven settings、缓存、home及临时工作区继续使用个人`tools/dev`隔离路径。测试主机和子进程TZ=UTC。

先新增三个可编译的行为用例，运行`test -Dgroups=us5-schedules`得到3失败、0错误、0跳过：
缺`scheduled_tasks/task_executions`、缺正式协调器、真实cron未触发。基线日志为`.oryxos-dev/evidence/us5-batch2-red.log`。
随后扩充本批风险用例；没有禁用、跳过、空断言或把编译失败计作行为基线。

中途11项调度测试通过，run ID `2134e658-7def-4045-908b-e80b11a26477`；
其后15项调度加16项第一批存储故障回归共31项通过，run ID `61e8ebc8-a973-4e97-9c5b9ee11fba`。
最终组含18项（Boot16、storage2），完整回归及报告归档见本文件末尾和[一致性审查](US5-second-batch-review.md)。

## 测试与实际断言

| 任务／依据 | 用例 | 实际检查 |
| --- | --- | --- |
| T082 / FR-018 | `v5UpgradePreservesSessionExecutionAuditAndMigrationChecksums` | 先运行V001–V005并保存真实会话、执行、模型审计，再升V006；原模型快照、终态、实际模型、NULL用量和前五个checksum不变。调度表只有状态字段，孤儿事件被外键拒绝。 |
| T082 | `scheduleTablesStoreStateAndHistoryWithoutAuthoritativeDefinitions`、`productionProvidesSchedulerUsingPersistentEngine` | 正式启动创建两张表与JpaScheduleStore；没有DB里的cron/message/timezone定义源。 |
| T082 / FR-036 | `historyOrdersFractionalInstantsAndRejectsForeignCursorsWithoutPausing` | 同秒100ms/110ms事件按真实先后排序；游标向前翻页；错误归属游标返回INVALID_INPUT且不暂停工作区。存储UTC统一九位小数，避免字符串时间排序歧义。 |
| T082 / US5-A5 | `persistedEnablementSurvivesDefinitionChangesDeletionAndRestoration` | 改cron/message并重启采用文件新值，但已保存disabled不变；删文件保留历史/lastExecutionId且不调度；恢复同ID文件也不重置enabled。 |
| T083 / US5-A3 | `automaticCronEntersThePersistentEngine` | 实际ThreadPoolTaskScheduler每秒cron产生模型请求，经AgentService保存执行、审计和一次性归档会话；系统channel/user明确；停用后零新增请求且nextRunAt为空。 |
| T083 / US5-A4 | `disabledManualRunsKeepIdentityFreshSessionsAndSixtySecondBudget` | 停用自动不执行；内部手动cli/alice与http/bob各建新Session、保留原身份、仍disabled；手动预算60秒，自动文件预算180秒；事件可分页。 |
| T083 / FR-035、NC-02 | `manualAndAutomaticOverlapRemainLockedThroughTimeoutAndLateRecording` | 回环模型阻塞时自动和手动均SKIPPED_OVERLAP；推进测试单调时钟到61秒后调用者返回TIMED_OUT，但会话未归档且锁未释放；迟到响应及记录完成后才可新执行。此测试不冒充真实HTTP等待60秒验收。 |
| T083 / FR-035 | `ordinaryFailureLeavesLaterTriggersUsable` | 真实协议401得到FAILED并落库，工作区仍READY；下一次触发成功，原失败保留。 |
| T083 / US5-A3 | `realCronObservesOverlapAndContinuesAfterProviderFailure` | 实际cron定时器在第一请求阻塞时记录重叠跳过；释放后第一模型失败，后续自动tick成功；未因失败取消注册。 |
| T083 / FR-040 | `dispatchRecordFailurePausesBeforeModelAndRecoveryNeverReplaysSkippedTicks` | SQLite触发器拒绝DISPATCHED记录时模型0次、全局暂停；自动及手动均SKIPPED_PAUSED；修好触发器仍暂停，明确人工确认后仅新的手动任务发送1次，旧tick不补跑。重启保留事件。 |
| T083 / FR-036、040 | `failedEnabledCommitDoesNotClaimStateChange` | SQLite启用UPDATE失败，状态仍false、无新注册，门禁暂停，不报告控制成功。 |
| T083 / RV-06 | `workspaceTimezoneAndFileSnapshotsApplyToBothTriggerKinds` | 固定UTC主机时刻跨上海午夜；手动与自动提示词均是11月14日、Asia/Shanghai；cron下次时间正确。运行中改Provider/message仍使用启动快照，正文下一次读取生效。 |
| T083 / FR-034 | `invalidTimezoneOrBudgetOnlyDisablesItsAgent` | 不一致时区和小于60秒的自动预算使所引用Agent不可用，另一有效Agent继续存在且工作区READY。 |
| T082–083 / US5-A5 | `actualJvmRestartPreservesHistoryAndDisabledStateWithoutCatchup` | 独立JVM运行并停用，再启动另一个JVM；相同执行UUID、状态与历史保留。已错过的每日cron不会因重启或启用补跑，模型总数始终1。 |
| T083 / 入口边界 | `chatDoesNotRegisterAutomaticJobsEvenWhenFilesEnableThem` | Agent有每秒enabled任务，真实chat保持运行超过一次tick仍模型0次、无调度事件。 |
| T083 / 正式装配 | `serveAndGatewayCliProcessesActivateTheSamePersistentScheduler` | 各启动独立JVM走真实CLI主类，serve和gateway均自动产生持久执行；关闭后锁释放，重开READY，系统身份一致，日志无fixture密钥。不是直接调用模型替代入口测试。 |
| T083 / 启停竞争 | `callbackAlreadyTakenByTimerCannotRunAfterDisableAndReenable` | 用可控定时器交接边界重现已取出但延后交接的旧回调；停用再启用后旧回调模型0次、无新事件；新注册回调经真实SQLite/协议适配执行1次。防止cancel(false)无法撤回已开始回调时补跑旧任务。 |

## 实现约束与审查点

- ScheduleDefinition来自启动时已验证的Agent快照；id为`agent:localId`。V006只保存enabled、定义hash、configuredPresent、lastExecutionId及触发历史。
- 初始enabled只在第一次创建行时使用；文件失效/删除保留行及历史。手动运行停用任务不更改enabled。
- 自动/手动共用每schedule执行占用，迟到收尾才释放；启停写入与自动接收串行校验，防止停用成功后旧检查仍发起新执行。
  每次注册另有当前有效性标识；已取消的旧回调即使在重新启用后才到达，也不能成为新的执行。
- DISPATCHED及关联executionId在执行/USER提交后、首次外部动作之前落库；失败复用已有全局记录故障门禁。状态写入成功后才变更定时句柄。
- 触发决策只记DISPATCHED/FAILED/SKIPPED_OVERLAP/SKIPPED_PAUSED；最终结果来自对应Execution，不在调度事件中另造一套结果状态。
- 自动channel=`scheduler`、user=`oryxos-scheduler`；手动保留cli/http和调用者身份。所有调度触发的新Session均为ONE_SHOT，实际收尾后归档。
- 手动预算60秒，自动默认60秒且只允许在文件中放大；从协调器接收时刻起计入预算。工作区时区用于cron和提示词，与服务器默认时区无关。
- 注册使用六字段`CronTrigger`的lenient语义，回调仅交接AgentService，不等待模型；不使用fixed/resume补偿触发。句柄随停用与关闭取消，后续启用从当前时刻计算。
  依据：[Spring CronTrigger](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/scheduling/support/CronTrigger.html)、[ThreadPoolTaskScheduler](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.html)。
- 运行标记、存储重建和恢复语义继续沿用第一批；恢复仍关闸重建、协调文件快照与状态，然后完成确认后开放。没有内存Store兜底或自动重放。

## 未完成的跨批验收

T073整体仍未完成；T084以后全部保留。调度HTTP查询/历史/启停/立即执行是T087，使用者恢复操作是T088/T092，管理台是T091/T092。
本批内部运行控制已测试，但没有新CLI子命令或可供用户调用的业务HTTP入口。
T093/T094的完整真实端口/HTTP截止、页面与配置整合不能由本批内部测试替代；18项仅登记`us5-schedules`，不提前启用`us5-http`。
T027/T028、T042/T044、T051/T057/T058、T070/T071/T072保持未完成；三真实场景、性能长跑、新用户验收及最终出口均未提前勾选。

## 最终完整验证

最终`clean verify`的run ID为`0959faf8-93d0-44b9-a630-58618a8bacce`：136测试、32类，0失败/错误/跳过，九个子模块构建成功。
XML、完整日志及summary.json已归档至`.oryxos-dev/evidence/us5-batch2-full-verify/`。
报告门禁153项为分组登记数之和，包含17项跨组重复；不等于153个唯一测试。

原打包黑盒`verify-foundation.py`五组通过；Python环境与报告门禁分别5项/1项通过。
Boot及8个内嵌Oryx模块JAR已检查正式调度类和测试类隔离，记录在`us5-batch2-release-inspection.json`。
任务状态、规格一致性、边界及可审阅限制见[本批审查](US5-second-batch-review.md)。
