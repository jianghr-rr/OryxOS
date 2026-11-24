# Quickstart: 验证 ReAct 边界对齐重构

前提：当前终端已 `export ORYXOS_JAVA_HOME=<JDK21 Home>`；所有命令在仓库根目录执行。

## §1 本节测试分组

```bash
./tools/dev/mvnw -B -ntp test -Dgroups=us2-loop
```

预期：BUILD SUCCESS；`tools/dev/mvnw` 内置的报告门禁核对 `us2-loop` 登记的 44 项与实际执行一致（跨 core/tool/boot 模块，所以不加 `-pl`）。

## §2 课件测试类存在且被执行

开发入口禁止 `-Dtest` / `-Dsurefire.*`，按分组跑 §1 后检查报告：

```bash
ls oryxos-core/target/surefire-reports/ | rg 'TEST-.*\.(ReActLoopTest|PromptBuilderTest|ToolExecutorTest|AgentServiceTest|ContextLoaderTest)\.xml'
```

预期：5 个报告文件，均 `failures="0" errors="0" skipped="0"`。

## §3 迁移方法名无丢失

```bash
git show HEAD:oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/react/ReActContractTest.java \
  | rg -o 'void (\w+)\(' -r '$1' | sort > /tmp/before.txt
rg -o --no-filename 'void (\w+)\(' -r '$1' \
  oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/react/ReActLoopTest.java \
  oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/context/ContextLoaderTest.java | sort > /tmp/after.txt
comm -23 /tmp/before.txt /tmp/after.txt
```

预期：输出为空。

## §4 边界搜索

```bash
rg -n 'ProfileContext\.(set|clear)' --type java -g '!**/test/**'     # 只命中 AgentService
rg -n 'ProfileContext\.current' --type java -g '!**/test/**'         # 主代码零命中（PC5）
rg -n 'ReActContractTest' tools/ specs/001-single-node-agent-runtime/verification.md  # 零命中
```

## §5 质量基线

```bash
./tools/dev/mvnw clean verify
./tools/dev/quality-baseline --prune
./tools/dev/quality-baseline --check-shrink origin/main
rg -c 'AgentService.java' config/quality/baseline.json                 # 预期 0
```

## §6 全量回归与契约零差异

```bash
./tools/dev/mvnw clean verify
```

预期：全绿；`us5-http-api` / `us5-published-contract`（HTTP 契约）与 `us5-cli` 结果与改动前一致。

## §7 故意破坏（结果写入 `evidence/breakage.md`，做完恢复）

| 破坏 | 预期失败 |
| --- | --- |
| `ReActLoop` 中把 `limit` 改为 `maxIterations() + 1`（第 limit 轮会提前 return，只改循环条件构不成破坏） | `ReActLoopTest#modelAlwaysRequestingToolsStopsAtMaxIterations` |
| 删除 `AgentService` 中的 `ProfileContext.clear()` | `AgentServiceTest#profileContextIsClearedWhenProcessingThrows`、`#profileContextIsClearedBeforeCompletionOnWorkerThread` |

## 人工剩余项（不由 harness 判定）

- 用真模型跑 Demo 一对话版：多轮中调用 `http_get` 拿到天气并给出穿搭建议。
- Code review 确认循环自实现，未使用框架现成 Agent 封装（另有 spec 005 `ProviderArchitectureGuardTest` 守护 provider 侧）。
