# 改动前基线快照（T001、T002）

- HEAD：`37cd09805a2d102cd6da3170b74aa53bdbfc55ae`（spec 005 的提交；分支 `006-lesson17-react-boundary` 由此切出，切出时工作区干净）
- JDK：OpenJDK 21.0.13（`ORYXOS_JAVA_HOME` 指向课程 JDK）
- `config/quality/baseline.json` `entries` 总数：**448**
- `oryxos-core/.../core/agent/AgentService.java` 基线条目 4 条：
  - checkstyle `AvoidStarImportCheck`
  - checkstyle `NeedBracesCheck`
  - pmd `ControlStatementBraces`
  - pmd `OryxThreadPoolCreation`
- `ReActContractTest` 方法 6 个，见 [react-contract-methods-before.txt](./react-contract-methods-before.txt)
- `tools/dev/test-groups.json` 中 `us2-loop` 登记数：**30**（`tools/dev/README.md` 写的 28 已过时，本次一并更正）

## 改动前测试

```text
./tools/dev/mvnw -B -ntp test -Dgroups=us2-loop
Test evidence PASS: 30 required cases; run ffba9498-9f2a-495f-ba03-96d955d113cd
./tools/dev/mvnw -B -ntp test -Dgroups=deadline-boundary
Test evidence PASS: 13 required cases; run eb4bd736-8931-4b0f-aad4-5d22d5068b4a
```

## 改动后（T021）

```text
./tools/dev/quality-baseline --prune
Baseline pruned: 4 removed, 444 kept
./tools/dev/quality-baseline --check-shrink origin/main
Baseline did not grow against origin/main: 444 entries
```

`AgentService.java` 在 `config/quality/baseline.json` 中剩余条目：0。基线总数 448 → 444。
