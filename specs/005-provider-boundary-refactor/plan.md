# Implementation Plan: Provider 边界重构（对齐第 16 节课件与技术方案）

**Branch**: `005-provider-boundary-refactor` | **Date**: 2026-11-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/005-provider-boundary-refactor/spec.md`

## Summary

这是一次 Provider 层的行为保持型重构，外加修正一个可观察缺陷。

1. **一次准入**：一次模型调用现在被 `ReActLoop`、`AuditedProvider`、`SpringAiProvider` 各申请一次运行准入，
   在途计数为 3；且准入后被停止时会留下“失败但从未发出请求”的审计。改为只在 `ReActLoop` 准入一次，并从 `ProviderRequest`
   删除准入句柄（FR-001 ~ FR-003）。
2. **一次校验、一次映射**：请求校验只在入口做，原始异常映射只在协议适配层做（FR-004、FR-005）。
3. **命名对齐**：`ProviderRegistry` → `ProviderService`；删除死代码 `ToolWireNameMapper`（FR-006、FR-007）。
4. **测试可定位**：1010 行的 `ProviderContractTest` 按验收点拆成 7 个专项类加 1 个共用夹具（5 个迁移类、2 个新增类），方法名不变；新增 5 个测试，包括结构守护与非法请求零审计（FR-004、FR-008、FR-009）。
5. **质量基线只减**：改动文件的 12 条基线条目清零，`PreserveStackTrace` 走登记例外（FR-012）。
6. **课件修订建议**：实现完成后写 `lesson-16-revision.md`（FR-013）。

技术决定详见 [research.md](./research.md)，调用边界见 [contracts/provider-port.md](./contracts/provider-port.md)，
测试归属见 [data-model.md](./data-model.md)，验证步骤见 [quickstart.md](./quickstart.md)。

## Technical Context

**Language/Version**: Java 21（父 POM `maven.compiler.release=21`，enforcer 校验）

**Primary Dependencies**: Spring Boot 3.5.16；Spring AI BOM 1.1.8，provider 模块只用 `spring-ai-openai` 的低层 `OpenAiApi`；
Apache HttpClient 5（既有）。本次不新增、不升级依赖（FR-011）。

**Storage**: SQLite `llm_calls`（`V004__call_audits.sql`），结构与迁移脚本不变

**Testing**: JUnit 5 + Surefire；统一入口 `tools/dev/mvnw`；分组登记 `tools/dev/test-groups.json`；报告门禁 `tools/dev/check-test-reports.py`

**Target Platform**: 单机 JVM 服务（本地与腾讯云单机部署，部署链不受影响）

**Project Type**: Maven 多模块后端服务；本次只涉及 `oryxos-core`、`oryxos-provider`、`oryxos-boot`（装配）

**Performance Goals**: 无新目标；`kernel-provider-timing` / `kernel-provider-resources` / `kernel-transport` 分组断言不变且通过

**Constraints**: 外部契约零差异（HTTP/OpenAPI、CLI、`runtime.yaml`、`llm_calls`、日志字段、错误码集合）；质量基线只减不增；
不得在源码内用注释或注解绕过门禁

**Scale/Scope**: 主代码修改 6 个文件（core 2、provider 3、boot 1），删除 1 个（provider `ToolWireNameMapper`）；测试新增 8 个类、删除 1 个；登记与文档 5 处

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 检查点 | 结论 |
| --- | --- | --- |
| I 单机内核优先 | 不引入 fallback/重试/熔断/新能力 | 通过 |
| II ReAct 与工具执行统一掌控 | provider 不执行工具；新增结构守护钉死“不接入框架自动工具执行” | 通过（加强） |
| III 职责分离 | 准入归 ReAct、校验归入口、映射归适配层、审计归包装层；按名显式映射不变；无新的模块依赖 | 通过（加强） |
| IV 凭证与数据边界 | 不把原始异常挂为 cause；`PreserveStackTrace` 例外写明安全理由；审计不含密钥/私有 URL 的断言保留并新增 | 通过 |
| V 审计与持久化 | `llm_calls` 结构不变；修正误记的 FAILED，使记录反映真实结果；不改迁移脚本 | 通过 |
| VI 可演示可检查 | 每个 FR 有对应测试或命令；红绿证据与三次故意破坏写入 evidence；只用假服务，真实联调不冒充 | 通过 |
| VII 一致性 | spec 已澄清 5 项；保留的偏差已列明依据；不改写历史证据；改名同步现行文档 | 通过 |

**Post-design re-check**：Phase 1 产物未引入新偏差。唯一需要说明的取舍是 `PreserveStackTrace` 以登记例外而非修复的方式清除基线（见 Complexity Tracking），符合 spec 004 quality-gates 契约的例外机制。

## Project Structure

### Documentation (this feature)

```text
specs/005-provider-boundary-refactor/
├── spec.md
├── plan.md                     # 本文件
├── research.md                 # Phase 0
├── data-model.md               # Phase 1
├── contracts/provider-port.md  # Phase 1：core ↔ provider 调用边界
├── quickstart.md               # Phase 1：验证步骤
├── checklists/requirements.md
├── evidence/                   # 实施阶段：红绿、故意破坏、全量验证记录
├── lesson-16-revision.md       # 实施阶段末尾（FR-013）
└── tasks.md                    # $speckit-tasks 生成
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/github/oryxlabs/oryxos/core/
├── provider/ProviderRequest.java          # 改：删除 dispatcher 组件、10 参数构造器、withDispatcher
└── context/PromptBuilder.java             # 改：不再调用 withDispatcher
    （react/ReActLoop.java 不改：保留唯一准入）

oryxos-provider/src/main/java/io/github/oryxlabs/oryxos/provider/
├── ProviderService.java                   # git mv 自 ProviderRegistry.java；承接 validate；显式导入、补花括号
├── AuditedProvider.java                   # 改：删 dispatcher 分支；OryxError 原样记录/抛出；显式导入、补花括号
├── SpringAiProvider.java                  # 改：删 dispatcher 分支与重复校验；唯一映射点；删多余代码块；显式导入、补花括号
└── ToolWireNameMapper.java                # 删
    （OpenAiClientFactory / TimedHttpRequestFactory / Provider*Mapper 不改）

oryxos-provider/src/test/java/io/github/oryxlabs/oryxos/provider/
├── ProviderTestSupport.java               # 新：共用夹具（自 ProviderContractTest 抽出）
├── ProviderRoutingTest.java               # 新（迁入 2）
├── ProviderToolSchemaTest.java            # 新（迁入 2）
├── ProviderAuditTest.java                 # 新（迁入 2 + 新增 1）
├── ProviderProtocolTest.java              # 新（迁入 2）
├── ProviderTransportTest.java             # 新（迁入 6，保留 kernel-* 方法级标签）
├── ProviderAdmissionTest.java             # 新（新增 2，走真实 ReActLoop + PromptBuilder）
├── ProviderArchitectureGuardTest.java     # 新（新增 1）
├── ProviderContractTest.java              # 删
├── ProviderSmokeTest.java                 # 改：类名与演示文案
├── RealProviderIT.java                    # 改：类名
└── ProviderConcurrencyProbeIT.java        # 改：类名

oryxos-boot/src/main/java/io/github/oryxlabs/oryxos/boot/config/ProviderConfiguration.java  # 改：类型与构造

config/quality/baseline.json               # 改：--prune 删除 12 条
config/pmd/exclude-pmd.properties          # 改：新增 PreserveStackTrace 登记例外（带 reason/registered）
tools/dev/test-groups.json                 # 改：4 个分组的 class 字段与新增方法
tools/dev/README.md                        # 改：us1-contract 必跑数 15 → 20
specs/001-single-node-agent-runtime/verification.md  # 改：us1-contract 行的类清单（现行分组表，非历史证据）
```

**Structure Decision**：不新增、不合并模块。依赖方向保持 `provider → core`、`boot → provider/core`。
`ProviderAdmissionTest` 放在 provider 模块，因为它需要真实 `ProviderService` 与真实 `ReActLoop`，而 provider 已依赖 core 主代码；
core 未发布 test-jar，所以测试内自建最小执行上下文，不跨模块复用 `LoopFixture`。

## 实施顺序与依赖

按“先证明缺陷、再改结构、最后清理”的顺序，保证每一步可独立验证：

| 阶段 | 内容 | 依赖 | 退出条件 |
| --- | --- | --- | --- |
| S0 基线快照 | 记录 `origin/main` 的基线条目数、`ProviderContractTest` 方法清单、`us1-contract` 必跑数 | — | evidence 中有三项数字 |
| S1 夹具与测试拆分 | 抽 `ProviderTestSupport`；原方法迁入 5 个专项类；删 `ProviderContractTest`；更新 `test-groups.json` | S0 | 分组命令全绿；方法名 `comm` 无差异（quickstart §3） |
| S2 红灯证据 | 新增 `ProviderAdmissionTest`（引用旧名）并登记 | S1 | 两个用例按预期失败，结果写入 evidence |
| S3 一次准入 | 删 `ProviderRequest.dispatcher`、`PromptBuilder.withDispatcher`、两处内层 `dispatcher.call` | S2 | S2 两个用例转绿；`kernel-*` 计时分组不变 |
| S4 一次校验/映射 | 新增 `invalidRequestsNeverWriteAuditOrReachNetwork`；validate 移入入口；`AuditedProvider` 只处理 `OryxError`；`SpringAiProvider` 唯一映射 | S3 | `ProviderRoutingTest`、`ProviderAuditTest`、`ProviderProtocolTest` 全绿 |
| S5 改名与死代码 | `git mv` → `ProviderService`；更新 boot 装配与测试；删 `ToolWireNameMapper` | S4 | quickstart §4 三条搜索符合预期 |
| S6 新增守护与失败审计 | `ProviderArchitectureGuardTest`、`failedCallsAreAuditedWithClassifiedCodeBeforeRethrow`；登记；README 计数 | S5 | `us1-contract` 必跑 20 全绿 |
| S7 基线清零 | 显式导入、补花括号；登记 `PreserveStackTrace` 例外；`clean verify`；`--prune`；`--check-shrink` | S6 | quickstart §5 全部符合；基线 −12 |
| S8 全量与故意破坏 | quickstart §6、§7 | S7 | 对外契约零差异；三次破坏结果入 evidence |
| S9 文档 | `verification.md` 分组表；`lesson-16-revision.md` | S8 | FR-013 表覆盖 6 条偏差 + 改名 + 测试名对照 |

S1 与 S2 之后的阶段严格串行；S1 的拆分是纯移动，单独成步便于审查 diff。
按宪章 VII，每个用户故事完成后（S4、S5、S6 结束时）做一次故事级复查：对照 `contracts/provider-port.md` 不变量 P1–P8 与本故事 FR
审查 diff，结合行为验证结果写入 `evidence/review-usN.md`。

## 风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 拆分后某分组少跑用例 | 报告门禁失败或静默漏测 | `test-groups.json` 按 data-model §4 改；quickstart §1、§2 核对必跑数；§3 核对方法名 |
| 去掉内层准入后，暂停/关闭等待逻辑行为变化 | `StorageLifecycle`/`RuntimeStartupCheck` 依赖 `inFlight` | 计数从 3 变 1 只会让“归零”更早、更准确；运行 `restart-recovery`、`storage-faults`、`kernel-local` 分组确认 |
| `AuditedProvider` 收到非 `OryxError` | 理论上只会来自编程错误 | 防御分支归为 `PROVIDER_PROTOCOL` 并写 FAILED，与现状结果一致 |
| `PreserveStackTrace` 例外被视为绕过门禁 | 审查争议 | 例外只登记在 `exclude-pmd.properties`，理由引用 `OryxError` 类注释与宪章 IV；不在源码中写抑制 |
| 改名遗漏现行文档 | 课件对照失真 | quickstart §4 全仓搜索（排除历史 specs） |
| 改造 `AuditedProvider` 时引入 `UUID.randomUUID()` | `HttpCompletionResumptionTest#hotExecutionIdentifiersAvoidSynchronizedRandomUuid` 扫描其字节码会失败 | 继续使用 `Uuids.v4()`；该测试在全量 `clean verify` 中覆盖 |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
| --- | --- | --- |
| `PreserveStackTrace` 以登记例外清除，而不是修复 | provider 层必须丢弃原始异常，防止响应体、私有 URL、凭证进入日志与错误响应（宪章 IV、`OryxError` 类约束） | 给 `OryxError` 挂 cause 会把原始消息带入堆栈；把条目留在基线违反 Clarifications Q3（改动文件必须清零），且 `--check-shrink` 不接受改名后的路径 |
