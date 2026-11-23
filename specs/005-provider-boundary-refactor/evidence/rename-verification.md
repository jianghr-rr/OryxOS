# 改名与死代码清理核对（T025–T029）

| 项 | 命令 | 结果 |
| --- | --- | --- |
| 旧名与死代码无残留 | `rg -n 'ProviderRegistry\|ToolWireNameMapper' --glob '!specs/00[1-4]*/**' --glob '!specs/005*/**' .` | 只命中 `config/quality/baseline.json` 中的 4 个旧路径条目（Polish T042 由 `--prune` 清理）；代码、测试、文档无命中 |
| 入口唯一 | `rg -n 'class ProviderService' oryxos-provider/src/main/java` | 1 处：`ProviderService.java:14` |
| 请求无准入句柄 | `rg -n dispatcher oryxos-provider/src/main/java oryxos-core/.../core/provider` | 无输出 |
| 装配与契约 | `./tools/dev/mvnw -B -ntp -pl oryxos-boot -am test -Dgroups=us1-contract,f0-foundation` | BUILD SUCCESS；`Test evidence PASS: 32 required cases`；`OryxOsApplicationTest`、`DegradedStartupTest`、`StructuredLogAuditTest` 通过 |

变更：`git mv ProviderRegistry.java ProviderService.java`（类名与构造器同步改名，构造参数与 `implements ProviderPort, AutoCloseable` 不变）；
`ProviderConfiguration` 的 import 与 `new` 改为 `ProviderService`，Bean 方法名本来是 `providerPort`，不含 `Registry`，所以未改；
测试中的 `ProviderTestSupport`、`ProviderAuditTest`（任务清单未列，实际有 2 处直接构造）、`ProviderSmokeTest`（含演示文案）、`RealProviderIT`、`ProviderConcurrencyProbeIT` 已同步；
删除 `ToolWireNameMapper`，`ProviderToolSchemaTest` 改用 `ToolNameMap.Entry` + `ToolNameMap.wireName`，用私有辅助方法 `entry(...)` 包装，断言不变。
