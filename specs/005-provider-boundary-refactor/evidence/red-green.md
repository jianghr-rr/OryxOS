# 红绿证据（FR-002，T016 / T023）

## 红（T016，旧实现、旧类名 `ProviderRegistry`）

命令：`./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract`；`us1-contract` 登记 18 个（本反应堆 16 个）。

结果：BUILD FAILURE，仅 `ProviderAdmissionTest` 两个用例失败，其余（含新增 `ProviderRoutingTest#invalidRequestsNeverWriteAuditOrReachNetwork`）全绿：

```text
ProviderAdmissionTest.modelCallHoldsExactlyOneAdmissionWhileInFlight:164 one model call must hold exactly one runtime admission ==> expected: <1> but was: <3>
ProviderAdmissionTest.stopAfterAdmissionLetsTheCallFinishAndRecordsActualOutcome:180 an admitted call must still reach the provider ==> expected: <1> but was: <0>
Tests run: 3, Failures: 0 -- in ProviderRoutingTest
```

解读：同一次模型调用在 `ReActLoop`、`AuditedProvider`、`SpringAiProvider` 各准入一次，在途计数为 3；
写入 STARTED 后停止执行时，`AuditedProvider` 内的第二次准入抛出 `EXECUTION_STOPPED`，请求未发出，并以 FAILED 记审计。

## 绿（T023，T017–T022 实现后）

| 命令 | 结果 |
| --- | --- |
| `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract` | BUILD SUCCESS；`Test evidence PASS: 16 required cases`；`ProviderAdmissionTest` 2/2 转绿 |
| `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=kernel-provider-timing,kernel-provider-resources,kernel-transport,us2-loop,deadline-boundary` | BUILD SUCCESS；`Test evidence PASS: 43 required cases`（含 `DeadlineRaceTest` 7、`ReActContractTest` 6、`FailurePolicyContractTest` 6、`SessionRuntimeContractTest` 5、`ProviderTransportTest` 6） |
| `./tools/dev/mvnw -B -ntp -q test-compile`（全仓） | 通过；删除 10 参数构造器与 `withDispatcher` 后无其它模块引用 |

过程记录：第一次转绿运行中 `stopAfterAdmission…` 报 `NullPointerException`。原因是测试本身用 `Set.of(...).contains(null)` 检查成功记录的
null `errorCode`。此前的关键断言（请求到达 1 次、终态 SUCCEEDED）已经通过。修正为先 `filter(Objects::nonNull)` 后重跑通过。实现没有改动。
