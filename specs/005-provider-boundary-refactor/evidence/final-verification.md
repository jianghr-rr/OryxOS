# 最终验证（T041–T046）

日期：2026-11-23。JDK 21.0.13 只在当前终端用 `ORYXOS_JAVA_HOME` 指定，未改全局 Java。只使用回环假模型服务；
**真实 DeepSeek/Kimi 联调不在本次范围，未执行**。

## 命令结果

| 任务 | 命令 | 结果 |
| --- | --- | --- |
| T041 | `./tools/dev/mvnw spotless:apply` → `./tools/dev/mvnw clean verify` | BUILD SUCCESS；`Test evidence PASS: 339 required cases`；`Quality gate PASS: 460 baseline entries`（prune 前） |
| T042 | `./tools/dev/quality-baseline --prune` | `Baseline pruned: 12 removed, 448 kept` |
| T042 | `./tools/dev/quality-baseline --check-shrink origin/main` | `Baseline did not grow against origin/main: 448 entries` |
| T042 | `rg -c 'ProviderRegistry\|ProviderService\|SpringAiProvider\|AuditedProvider\|ToolWireNameMapper' config/quality/baseline.json` | 无命中（0） |
| T042 | `python3 tools/dev/quality-gate.py check`（prune 与新增 PMD 例外之后） | `Quality gate PASS: 448 baseline entries` |
| T043 | `./tools/dev/mvnw -B -ntp test -Dgroups=us5-published-contract,us5-cli,kernel-local,restart-recovery,storage-faults` | BUILD SUCCESS；`Test evidence PASS: 47 required cases`；`PublishedOpenApiTest` 2/2（19 个操作、44 个 schema 零差异的对照测试）、`CliOperationsTest` 6、`CliRuntimeContractTest` 9、`KernelLocalIT` 9、`RestartRecoveryIT` 5、`RecordingFailureIT` 7、`PersistentStorageContractTest` 19 |

删除的 12 条基线：`AuditedProvider`、`ProviderRegistry`、`SpringAiProvider` 各 `AvoidStarImportCheck`、`NeedBracesCheck`、`ControlStatementBraces`
（共 9 条）；`ToolWireNameMapper` 的 `AvoidStarImportCheck`（1 条）；`AuditedProvider`、`SpringAiProvider` 的 `PreserveStackTrace`（2 条），
这两条改由 `config/pmd/exclude-pmd.properties` 的类级例外承接，每条都带 `# reason:` 和 `# registered: 2026-11-23`。
T019 改造后 `AuditedProvider` 仍然报告 `PreserveStackTrace`（失败分支，以及 `write()` 中转成 `RECORDING_*` 的分支），所以按 T040 的条件一并登记。

## FR / SC 对照

| 条目 | 结论 | 证据位置 |
| --- | --- | --- |
| FR-001 单次准入 | 满足 | `review-us1.md`；`ProviderAdmissionTest#modelCallHoldsExactlyOneAdmissionWhileInFlight` |
| FR-002 准入后持续到退出 | 满足 | `red-green.md`；`#stopAfterAdmissionLetsTheCallFinishAndRecordsActualOutcome`；没有新增审计状态值 |
| FR-003 请求对象移除准入句柄 | 满足 | `ProviderRequest` 9 个组件；全仓 `test-compile` 通过；`us2-loop`、`deadline-boundary` 通过 |
| FR-004 入口一次校验，先于审计与网络 | 满足 | `ProviderService#validate`；`ProviderRoutingTest#invalidRequestsNeverWriteAuditOrReachNetwork` |
| FR-005 异常映射一次，错误码集合不变 | 满足 | `review-us1.md`（只在 `SpringAiProvider` 调用 `ProviderErrorMapper.failure`） |
| FR-006 入口改名 `ProviderService` | 满足 | `rename-verification.md`；`git mv` 保留历史 |
| FR-007 删除 `ToolWireNameMapper` | 满足 | `rename-verification.md` |
| FR-008 按验收点拆分，方法名不变，登记同步 | 满足 | `split-verification.md`；`tools/dev/test-groups.json`；`tools/dev/README.md`；`specs/001…/verification.md` 第 38、78 行 |
| FR-009 结构守护 | 满足 | `ProviderArchitectureGuardTest`；`breakage.md` ② |
| FR-010 外部契约不变 | 满足（本地） | T043：OpenAPI 零差异、CLI、恢复、存储故障测试全部通过；T041 全量 339 项通过 |
| FR-011 无迁移、无新依赖、无新能力 | 满足 | `git status` 中没有 `pom.xml` 或 `*.sql` 变更 |
| FR-012 改动的 provider 主代码文件基线清零，只减不增 | 满足 | 上表 T042；`ProviderRequest`、`PromptBuilder`（core）和 `ProviderConfiguration`（boot）不属于 provider 模块，它们原有的基线条目保留 |
| FR-013 课件修订建议 | 满足 | `../lesson-16-revision.md`（覆盖“保留的偏差”6 条、改名和测试类对照；课件原文未修改） |
| SC-001 在途计数为 1 | 满足 | 重构前实测 3，重构后 1（`red-green.md`） |
| SC-002 误记失败为 0 | 满足 | 同上 |
| SC-003 全量通过，场景不减 | 满足 | `clean verify` 通过；登记的唯一用例数从 257 增加到 262（+5：准入 2、非法请求 1、失败审计 1、守护 1），失败 0 |
| SC-004 对照表可定位，旧名为 0 | 满足 | `review-us2.md`；主代码和测试中 `ProviderRegistry` 出现 0 次 |
| SC-005 三类破坏命中专项类 | 满足 | `breakage.md`（② 的破坏方式有调整，见该文件） |
| SC-006 HTTP 契约与 CLI 结果一致 | 满足（本地） | T043 |
| SC-007 provider 改动文件条目为 0，总数下降 | 满足 | 460 → 448 |

## 与任务原文的偏差

1. 计数口径：`-pl oryxos-provider -am` 的报告值比登记总数少 2（boot 用例被按模块过滤）。quickstart §1、T011、T016、T034 已注明“18（重构前 13），登记 20（重构前 15）”。
2. `ProviderSmokeTest`、`ProviderConcurrencyProbeIT` 原本直接引用 `ProviderContractTest` 的静态夹具，`ProviderAuditTest` 中有 2 处直接构造入口类。这些引用任务清单没有列出，已一并更新。
3. T035 的还原方式改为从备份复制，破坏 ② 改为字段引用（原因见 `breakage.md`）。
4. `ProviderTestSupport` 新增 `Receiver.onRequest` 钩子和带 `AuditSink` 参数的 `registry` 重载，供准入测试使用。

## 未验证项

- 真实模型服务联调（`RealProviderIT`，需要 `mode=real` 和真实凭证）未执行，本次结论只覆盖回环替身。
- `ProviderConcurrencyProbeIT`（显式 soak）只验证了编译，没有运行。
- 未提交、未推送。
