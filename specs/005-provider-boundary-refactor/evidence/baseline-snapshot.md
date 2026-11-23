# 重构前快照（T001）

- 记录时间：2026-11-23
- HEAD：`1f20b94f81bd24ea79f0d9bbbecc5b2878a32732`（快照时所在分支 `004-evidence-t063`，规格目录未提交；提交时新建 `005-provider-boundary-refactor`）
- `config/quality/baseline.json` 条目总数：460
- 其中 `oryxos-provider` 主代码条目：28
- `ProviderContractTest` 测试方法数：14（清单见 `contract-methods-before.txt`）
- `us1-contract` 必跑方法数：15

## oryxos-provider 基线条目明细

| tool | rule | file |
| --- | --- | --- |
| checkstyle | AvoidStarImportCheck | `AuditedProvider.java` |
| checkstyle | NeedBracesCheck | `AuditedProvider.java` |
| checkstyle | AvoidStarImportCheck | `OpenAiClientFactory.java` |
| checkstyle | NeedBracesCheck | `OpenAiClientFactory.java` |
| checkstyle | NeedBracesCheck | `ProviderErrorMapper.java` |
| checkstyle | AvoidStarImportCheck | `ProviderMessageMapper.java` |
| checkstyle | NeedBracesCheck | `ProviderMessageMapper.java` |
| checkstyle | AvoidStarImportCheck | `ProviderOptionsMapper.java` |
| checkstyle | AvoidStarImportCheck | `ProviderRegistry.java` |
| checkstyle | NeedBracesCheck | `ProviderRegistry.java` |
| checkstyle | AvoidStarImportCheck | `SpringAiProvider.java` |
| checkstyle | NeedBracesCheck | `SpringAiProvider.java` |
| checkstyle | AbbreviationAsWordInNameCheck | `TimedHttpRequestFactory.java` |
| checkstyle | AvoidStarImportCheck | `TimedHttpRequestFactory.java` |
| checkstyle | NeedBracesCheck | `TimedHttpRequestFactory.java` |
| checkstyle | AvoidStarImportCheck | `ToolWireNameMapper.java` |
| pmd | ControlStatementBraces | `AuditedProvider.java` |
| pmd | PreserveStackTrace | `AuditedProvider.java` |
| pmd | ControlStatementBraces | `OpenAiClientFactory.java` |
| pmd | OryxThreadPoolCreation | `OpenAiClientFactory.java` |
| pmd | PreserveStackTrace | `OpenAiClientFactory.java` |
| pmd | ControlStatementBraces | `ProviderErrorMapper.java` |
| pmd | ControlStatementBraces | `ProviderMessageMapper.java` |
| pmd | ControlStatementBraces | `ProviderRegistry.java` |
| pmd | ControlStatementBraces | `SpringAiProvider.java` |
| pmd | PreserveStackTrace | `SpringAiProvider.java` |
| pmd | ControlStatementBraces | `TimedHttpRequestFactory.java` |
| spotbugs | AT_STALE_THREAD_WRITE_OF_PRIMITIVE | `OpenAiClientFactory.java` |

## 重构前测试（T002）

| 命令 | 结果 |
| --- | --- |
| `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract` | BUILD SUCCESS；`Test evidence PASS: 13 required cases`；`ProviderContractTest` 实跑 14、`ProviderSmokeTest` 1 |
| `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=kernel-provider-timing,kernel-provider-resources,kernel-transport` | BUILD SUCCESS；`Test evidence PASS: 6 required cases` |

计数口径：`us1-contract` 分组登记 15 个必跑用例，其中 2 个在 `oryxos-boot`（`StructuredLogAuditTest` 等）。`-pl oryxos-provider -am`
只构建 provider 与 core，入口按模块过滤后报告 13 个。重构后同一命令预期 18 个，分组登记总数预期 20 个。
