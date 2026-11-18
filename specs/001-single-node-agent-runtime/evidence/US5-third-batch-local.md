# US5 第三批本地证据：HTTP API

2026-11-13。本批授权T084–T089，先准备契约与截止测试，止于T089。
使用个人tools/dev入口、临时工作区、真实SQLite与实际MVC端口；模型和接收方是回环协议夹具。
没有真实模型、Mem0语义、外部通知接收或可信MCP联调结论。

## 先失败，再实现

初始HttpApiContractTest四项均可编译运行，分别因创建会话、健康/暂停诊断和截止测试的入口返回404而失败：4失败、0错误、0跳过。
日志：`.oryxos-dev/evidence/us5-batch3-red.log`。先前一次参数被隔离入口拒绝、一次旧构建产物导致编译失败，均不计作行为失败证据；clean test取得上述基线。

实现过程中契约测试发现并修复模型审计枚举差异、首次拒绝的序号投影、Jackson数字转字符串、暂停中执行的503映射。
另外补充“模型已回复但最终记录未完成”的截止：到时先封闭动作许可、返回504和UNKNOWN记录状态，仍保留已知模型结果。真实收尾前保持会话/调度占用。
既有契约JSON、数据库迁移和验收要求没有为测试改写。

## 本批15项验证

`us5-http-api`登记boot HttpApiContractTest 14项及core DeadlineRaceTest新增1项。
其中12项是实际HTTP端口整合，2项是DTO/HTTP响应边界，1项是可控时钟核心截止。

| 方法 | 输入、验证结果和副作用 |
| --- | --- |
| allNineteenOperationsUseContractEnvelopesAndPersistentRecords | 顺次请求全部19个操作；空会话保留requested-model；messages调用实际模型及http_post，接收1次；模型审计2条、工具1条，未知token为null；历史升序分页；重复归档幂等；invoke为独立ONE_SHOT且归档；停用调度手动执行后仍停用；恢复ID一致、重复确认不重放。 |
| invalidInputAndIdentityNeverReachModel | 缺/过长用户头、未知JSON字段、数字/空白正文、32769 UTF-8字节、无效UUID/cursor/limit/beforeSeq、其他用户续聊均拒绝；未配置Agent404；模型0次。 |
| pausedHealthAndQueriesStayHonestAndRecoveryRequiresConfirmation | 记录故障后health 503且live=true/ready=false；新任务503；ack=false为400，错误incidentId为409；模型0次。 |
| actualSixtySecondHttpDeadlineSealsActionsBefore504 | 模型真实阻塞超过60秒；HTTP等待实测断言59–65秒、返回504和执行ID；TIMED_OUT；放行迟到工具提案后模型总计1次、工具接收0次，GET执行仍TIMED_OUT。非虚拟时钟替代。 |
| everySchemaHasExactDtoFieldsAndValidNullableSerialization | 读取全部44schema；29数据DTO字段精确匹配，15信封共用ApiResponse；required、nullable、枚举、额外字段、UUID/date-time及数值边界断言。此项不单独充当业务验收。 |
| paginationAndSharedConfirmedMemorySurviveHttpRestart | 105条CORE全量、105条ARCHIVAL按100+5分页且标共享；会话cursor不重复；完整上下文关闭重建后历史、实际模型审计及续聊上下文恢复。 |
| actualSqlFailuresPauseAndHttpRecoveryDoesNotReplayAcceptedAction | 接收方实际接收1次后SQL trigger拒绝结果更新；HTTP500及INCOMPLETE，后续执行503；故障详情明确不完整；删除trigger并HTTP人工确认后模型/接收数仍各1，不重放。 |
| unreadableStorageReturnsErrorsAndRecover503WithoutFakeLists | 写入损坏数据库后HTTP诊断仍可用；sessions/executions查询500，恢复503，详情不完整；原坏库字节保留，模型0次。 |
| providerIterationAndCorrectedToolOutcomesKeepTheirEvidence | Provider503含executionId；参数错误经同执行纠正后SUCCEEDED且hasToolErrors=true/hasUnresolvedToolErrors=false；旧ERROR、resolvedByCallId及correctionOfCallId保留；实际接收1次；迭代上限422。 |
| concurrentScheduleAndSessionMutationsAreRejectedUntilSettlement | 同schedule第二次run、在途session归档/发消息均409；同一恢复操作在未收尾时409；全局暂停后在途执行结束返回503，没有第二次模型调用。 |
| disconnectedClientDoesNotCancelWorkOrReleaseSessionLock | 使用真实Socket发invoke后断开；执行中归档仍409；放行后模型2次、接收1次，最终归档；断开不宣称撤销外部动作。 |
| failedScheduleControlCannotClaimEnabledAndBadAgentIsIsolated | 坏Agent在profiles显示UNAVAILABLE且调用409；有效Agent模型名称中的秘密canary不外泄；错误Boolean格式400；SQL trigger拒绝启用写入后500、enabled仍false、health503、模型0次。 |
| httpReadonlyCrossChannelAndEmptyModelSnapshotStayBoundAcrossReload | HTTP可读CLI会话但续聊409；32768字节边界可运行；重启加载新模型后，旧空Session仍保留旧模型并以SESSION_MODEL_CHANGED拒绝，新的Session使用新模型。 |
| httpTimeoutPreservesCompletedModelAndUnconfirmedRecord | 响应边界测试：已完成模型、收尾等待截止的Handle仍映射504；已知SUCCEEDED模型结果与UNKNOWN记录状态同时保留，不假报记录完整。 |
| completedModelStillExpiresCallerWhileFinalRecordingIsBlocked | 核心可控时钟测试：最后ExecutionStore写阻塞，到60秒结束调用方等待并标UNKNOWN；Session仍BUSY；放行后最终记录COMPLETE，已返回快照仍UNKNOWN，模型只有1次。 |

实际HTTP断言逐响应读取contract/schema校验；正文不包含测试秘密和PRIVATE_OPAQUE_CANARY。
模型/工具尝试来自正式SQLite，公共历史不选取provider_opaque_json。
HTTP的ToolAttempt序号1表示第一次提案/尝试记录；引擎内部0为调用前拒绝，投影不改写其ERROR/DENIED/NOT_EXECUTED和NONE副作用，也不把它算成外部IO。

## 完整回归与产物

- `tools/dev/mvnw -B -ntp clean verify`：**151测试、33类，0失败、0错误、0跳过**；九子模块构建成功。
- run ID：`7a77bdaa-b0e5-4b87-9e63-5aa941ab7ad5`。
- 清单170个必跑登记含19个跨组重复；唯一执行数为151。截止补例同时归属us2-loop/deadline-boundary/us5-http-api。
- Python隔离环境/报告门禁：6测试通过。
- `verify-foundation.py`五组打包黑盒通过：轻命令、幂等初始化、根选择、进程互斥/正常关闭、无参数serve、损坏数据库诊断；health改为验证真实200/503及READY/PAUSED_RECORDING，原404骨架预期不再适用，其余断言保留。
- Boot JAR及8个内嵌Oryx JAR检查通过：正式HTTP类存在；指定早期内存替身、测试夹具/探针及secrets.local.yaml文件名不在产物中。不是任意秘密扫描，不提前完成T104。

完整XML、构建日志和摘要：`.oryxos-dev/evidence/us5-batch3-full-verify/`。
另有`us5-batch3-foundation.json`、`us5-batch3-release-inspection.json`、`us5-batch3-consistency.json`。
中间失败与修复日志保留为`us5-batch3-targeted*.log`及首次`us5-batch3-full-verify.log`，不以它们替代最终成功报告。

## 可复跑命令与剩余边界

```bash
cd /Users/jianghaoran/github/OryxOS
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp clean test -Dgroups=us5-http-api
./tools/dev/mvnw -B -ntp clean verify
python3 tools/dev/verify-foundation.py
python3 -m unittest discover -s tools/dev -p 'test_*.py'
```

普通chat已经使用正式SQLite运行，需要有效Agent/Provider及其秘密配置；HTTP在serve/gateway提供相同引擎。工具白名单、通知目标和MCP仍需相应配置。暂停工作区先核查故障，再经HTTP明确恢复；chat不启动自动调度，同工作区与serve互斥。
具体curl和恢复步骤见[quickstart第三批](../quickstart.md#us5-第三批-http-验证)。

本批HTTP重启验证是完整Spring上下文关闭重建；跨JVM的chat、调度、崩溃恢复由此前用例在本次全量回归中复跑。
不以这些子集提前勾选T073或T093–T095完整跨入口/浏览器出口。T090–T104未实施；T027/T028、T042/T044、T051/T057/T058、T070/T071/T072仍未完成。
没有改官方实验仓库、全局环境或ACSP配置；已有改动保留；未提交或推送。
