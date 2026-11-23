# Quickstart: 验证 Provider 边界重构

**Feature**: `005-provider-boundary-refactor` | 契约见 [contracts/provider-port.md](./contracts/provider-port.md)，测试归属见 [data-model.md §3](./data-model.md#3-测试拆分映射)

全部步骤只用可控假模型服务（回环 HTTP），不需要真实密钥。真实 DeepSeek/Kimi 联调不是本次验收项。

## 0. 前置

```bash
export ORYXOS_JAVA_HOME=/absolute/path/to/jdk-21/Contents/Home   # 仅当前终端
cd "$ORYXOS_REPO"
```

## 1. Provider 专项分组

```bash
./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract
```

预期：
- `Test evidence PASS` 行显示的必跑用例数为 18（重构前 13）。`us1-contract` 分组登记总数为 20（重构前 15），
  其中 `oryxos-boot` 的 2 个不在 `-pl oryxos-provider -am` 反应堆内，入口按模块过滤后不计入。
- 报告中出现 `ProviderRoutingTest`、`ProviderToolSchemaTest`、`ProviderAuditTest`、`ProviderProtocolTest`、
  `ProviderAdmissionTest`、`ProviderArchitectureGuardTest`、`ProviderSmokeTest`；不再出现 `ProviderContractTest`。

## 2. 原 kernel 分组仍可选中

```bash
./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=kernel-provider-timing,kernel-provider-resources,kernel-transport
```

预期：6 个必跑用例全部来自 `ProviderTransportTest`，方法名与重构前相同。

## 3. 方法名一个不少

```bash
git show origin/main:oryxos-provider/src/test/java/io/github/oryxlabs/oryxos/provider/ProviderContractTest.java \
  | rg -o 'void (\w+)\(\) throws' -r '$1' | sort > /tmp/before.txt
rg -o --no-filename 'void (\w+)\(\) throws' -r '$1' oryxos-provider/src/test/java/io/github/oryxlabs/oryxos/provider/ | sort > /tmp/after.txt
comm -23 /tmp/before.txt /tmp/after.txt   # 预期：无输出
```

## 4. 改名与死代码清理

```bash
rg -n 'ProviderRegistry|ToolWireNameMapper' --glob '!specs/00[1-4]*/**' --glob '!specs/005*/**' .   # 预期：无输出
rg -n 'class ProviderService' oryxos-provider/src/main/java                                         # 预期：1 处
rg -n 'dispatcher' oryxos-provider/src/main/java oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/provider  # 预期：无输出
```

## 5. 全量门禁与基线

```bash
./tools/dev/mvnw clean verify
./tools/dev/quality-baseline --prune
./tools/dev/quality-baseline --check-shrink origin/main
rg -c 'ProviderRegistry|ProviderService|SpringAiProvider|AuditedProvider|ToolWireNameMapper' config/quality/baseline.json  # 预期：0
```

预期：`clean verify` 通过（格式、Checkstyle、PMD、SpotBugs、全部已登记分组）；基线条目数比 `origin/main` 少 12 条；
`config/pmd/exclude-pmd.properties` 新增的 `PreserveStackTrace` 例外每条都带 `# reason:` 与 `# registered:`。

## 6. 对外契约零差异

```bash
./tools/dev/mvnw -B -ntp test -Dgroups=us5-published-contract,us5-cli,kernel-local
```

预期：OpenAPI 19 操作 / 44 schema 零差异；CLI 输出与退出码测试、`KernelLocalIT` 全部通过。

## 7. 故意破坏（SC-005，手工，验证后还原）

对 [data-model.md §3](./data-model.md#3-测试拆分映射) 表中三种破坏逐一修改源码、运行步骤 1，记录失败的测试类，然后 `git checkout -- <file>` 还原。
结果写入 `specs/005-provider-boundary-refactor/evidence/`。

## 8. 红绿证据（FR-002）

在实现 FR-001/FR-003 与改名之前，先只加入 `ProviderAdmissionTest`（此时引用旧类名 `ProviderRegistry`，经真实
`ReActLoop` + `PromptBuilder` 发起调用），运行：

```bash
# tools/dev/mvnw 要求所选分组的已登记用例全部出现在报告中，且禁止 -Dsurefire.*，因此不用 -Dtest 单跑一个类
./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract
```

预期（只有下面两个用例失败，其余已登记用例通过）：
`stopAfterAdmissionLetsTheCallFinishAndRecordsActualOutcome` 预期失败（审计出现 `EXECUTION_STOPPED` 的 FAILED），
`modelCallHoldsExactlyOneAdmissionWhileInFlight` 预期失败（实测 3）。完成重构后两者转绿。
