# US1 红绿记录（T008–T012）

## 红 1：类不存在（T009）

`AgentServiceTest` 登记进 `us2-loop`（30 → 34）后：

```text
./tools/dev/mvnw -B -ntp -pl oryxos-core -am test-compile
[ERROR] .../core/agent/AgentServiceTest.java:[73,22] 找不到符号
[ERROR] .../core/agent/AgentServiceTest.java:[102,18] 找不到符号
[ERROR] .../core/agent/AgentServiceTest.java:[113,24] 找不到符号
[ERROR] .../core/agent/AgentServiceTest.java:[118,70] 找不到符号
BUILD FAILURE
```

## 红 2：类存在但未接线（T010 后、T011 前）

```text
./tools/dev/mvnw -B -ntp test -Dgroups=us2-loop
Tests run: 4, Failures: 2 -- in io.github.oryxlabs.oryxos.core.agent.AgentServiceTest
AgentServiceTest.profileContextIsAvailableDuringToolExecution:84 expected: <AgentProfile[name=demo, ...]> but was: <null>
AgentServiceTest.profileContextIsClearedBeforeCompletionOnWorkerThread:122（during 为 null）
```

两个清理用例在未接线时因“从未设置”而空过；它们拦截泄漏的能力由 T022 的故意破坏（去掉 `clear()`）补证，见 [breakage.md](./breakage.md)。

## 绿（T011 后）

```text
./tools/dev/mvnw -B -ntp test -Dgroups=us2-loop
Tests run: 4, Failures: 0, Errors: 0 -- in io.github.oryxlabs.oryxos.core.agent.AgentServiceTest
BUILD SUCCESS
Test evidence PASS: 34 required cases; run cdd171db-53ca-4ea0-85ed-74b25ec1cee9
```
