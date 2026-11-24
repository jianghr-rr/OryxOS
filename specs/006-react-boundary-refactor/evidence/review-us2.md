# US2 故事级复查（课件 harness 可定位）

```text
./tools/dev/mvnw -B -ntp test -Dgroups=us2-loop
Tests run: 5 -- ReActLoopTest；3 -- PromptBuilderTest；4 -- ContextLoaderTest；4 -- ToolExecutorTest；4 -- AgentServiceTest（均 0 失败）
BUILD SUCCESS
Test evidence PASS: 44 required cases; run e7f193b3-1b03-4e82-8240-e13331067b59
```

新增 10 个 US2 测试首次运行即全绿（针对已实现行为，符合预期），未修改任何断言或主代码。首次失败仅为 Spotless 格式（`ToolExecutorTest` 行宽），`spotless:apply` 后通过。

## 课件 harness 子项对号（data-model §3）

| 课件行 | 子项 | 测试 | 结果 |
| --- | --- | --- | --- |
| ReActLoopTest | 无工具调用一轮收尾 | `ReActLoopTest#directReplyStopsWithoutToolIo` | 绿 |
| | 有工具则执行并回填 | `#toolsRunSequentiallyAndHistoryContainsPairedMessagesOnce` | 绿 |
| | 转满最大轮数强制停 | `#modelAlwaysRequestingToolsStopsAtMaxIterations`（10 次模型调用、9 次工具执行）、`#iterationLimitPairsUnexecutedProposalsAndStopsNewIo` | 绿 |
| | 每轮累积进 Session | `#everyRoundAccumulatesReplyAndToolResultsInSession` | 绿 |
| PromptBuilderTest | 四部分顺序 | `PromptBuilderTest#systemPromptOrdersBodyBootstrapDateTimeThenMemory`、`#historyPrecedesCurrentTurnAndToolsAreSeparate` | 绿 |
| | 历史超 N 轮截断 | `#requestHistoryIsBoundedByMaxHistoryTurns`（25 轮存量 → 20 轮进请求，存量不减） | 绿 |
| | 含当前日期时间 | `#systemPromptOrdersBodyBootstrapDateTimeThenMemory` | 绿 |
| ToolExecutorTest | 成功写审计 | `ToolExecutorTest#successfulToolWritesStartedThenSuccessAudit` | 绿 |
| | 失败写审计带原因 | `#failedToolWritesFailureAuditWithErrorCode` | 绿 |
| | 异常不吞 | `#throwingToolIsAuditedNotSwallowed`、`#auditWriteFailureIsRethrownAsRecordingFailure` | 绿 |
| AgentServiceTest | 处理期间可取 Profile | `AgentServiceTest#profileContextIsAvailableDuringToolExecution` | 绿 |
| | 抛异常 finally 也清 | `#profileContextIsClearedWhenProcessingThrows`、`#profileContextIsClearedBeforeCompletionOnWorkerThread` | 绿 |
| | 结束后 Session 持久化 | `#sessionMessagesArePersistedWhenProcessingEnds` | 绿 |
| ContextLoaderTest | 改文件下次立即生效 | `ContextLoaderTest#loadRereadsFilesOnEveryCall`、`#bodyAndBootstrapAreReloadedWithoutChangingStartupConfiguration` | 绿 |
| | 显式引用缺失报错 | `#missingOversizedAndSecretBootstrapFailBeforeModelIo` | 绿 |
| | Bootstrap 缺失 WARN | 保留偏差：缺失即在模型调用前失败（更严格），同上测试 | 绿 |

课件写出代码的两个测试均落地：英文方法名，`@DisplayName` 保留课件原文
“模型一直要调工具_转满最大轮数强制停”“处理中抛异常_ProfileContext也必须被清掉”。
