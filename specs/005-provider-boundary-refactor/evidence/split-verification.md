# 测试拆分核对（T003–T011）

日期：2026-11-23；JDK 21.0.13（只在当前终端用 `ORYXOS_JAVA_HOME` 指定）。

## 变更

- 新建 `ProviderTestSupport`（包内 `abstract class`，无类级标签），从原 `ProviderContractTest` 原样迁入共享夹具。
- 新建 `ProviderRoutingTest`、`ProviderToolSchemaTest`、`ProviderAuditTest`、`ProviderProtocolTest`、`ProviderTransportTest`，类级 `@Tag("us1-contract")`；
  `ProviderTransportTest` 保留方法级 kernel 标签，私有辅助方法 `selectorThreads`、`readHttpRequestHeadersLength` 随之迁入。
- 删除 `ProviderContractTest`。
- 任务清单之外发现的引用：`ProviderSmokeTest`、`ProviderConcurrencyProbeIT` 直接引用 `ProviderContractTest.Receiver/ordinary/definition/request`，
  已改为 `ProviderTestSupport.*`，断言未动。
- `tools/dev/test-groups.json` 四个分组中原类条目拆为新类条目，方法名不变；`us1-contract` 登记总数仍为 15。

## 核对

| 项 | 命令 | 结果 |
| --- | --- | --- |
| quickstart §1 | `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract` | BUILD SUCCESS；`Test evidence PASS: 13 required cases`（与重构前相同）；5 个新类全部出现，`ProviderContractTest` 不再出现 |
| quickstart §2 | `... -Dgroups=kernel-provider-timing,kernel-provider-resources,kernel-transport` | BUILD SUCCESS；`Test evidence PASS: 6 required cases`，6 个全部来自 `ProviderTransportTest` |
| quickstart §3 | `comm -3 contract-methods-before.txt after.txt` | 无输出（14 个方法名一个不少、没有多出） |
| 断言未变 | 脚本逐个比较 `git show HEAD:…/ProviderContractTest.java` 与新文件中同名方法的完整文本 | 26 个方法/构造器全部逐字节一致，差异 0 |

计数口径说明：`-pl oryxos-provider -am` 只构建 provider 与 core，`us1-contract` 中 `oryxos-boot` 的 2 个用例被入口按模块过滤，
因此报告值为 13 而非登记总数 15；quickstart §1 的预期已据此改为“18（重构前 13），登记总数 20（重构前 15）”。
