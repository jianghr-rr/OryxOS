# Research: Kimi Provider 独立接入与真实验收

**Feature**: [spec.md](./spec.md) | **Date**: 2026-11-24

本文件记录 Phase 0 的事实核对与决定。外部事实来自 Kimi 开放平台官方文档（`https://platform.kimi.com/docs/api/overview`、`/docs/api/chat`、`/docs/api/models-overview`，2026-11-24 查阅）；仓库事实来自当前检出。

## R1 Kimi 接入是否需要新协议或新适配

- **Decision**: 不新增。沿用 `protocol: openai_chat_completions`，`endpoint: https://api.moonshot.cn`，`completions_path: /v1/chat/completions`。
- **Rationale**: 官方概述列出 OpenAI Chat Completions 兼容入口 `https://api.moonshot.cn/v1` + `/chat/completions`，鉴权 `Authorization: Bearer`；现有 `OpenAiClientFactory` 正是“origin + completionsPath”拼接，与开发模板一致。`ProviderMessageMapper` 已在多轮中按 provider/model 校验并原样回传 `reasoning_content`，满足官方“工具调用多轮须回传完整 assistant 消息（含 reasoning_content）”要求。`ProviderOptionsMapper` 不使用会附带 temperature=0.8 的快捷构造，满足“temperature 固定、不应显式传入”。
- **Alternatives considered**: Anthropic Messages 兼容入口（`/anthropic`）——需要新协议适配，违背 FR-001；OpenAI Responses 入口——同理。

## R2 中国站与国际站

- **Decision**: 只认 `api.moonshot.cn`（spec Clarifications Q1）。
- **Rationale**: 维护者密钥来自 `platform.kimi.com`；官方与第三方资料均说明两站账号/密钥独立，混用返回 401。
- **Alternatives considered**: 同时认 `api.moonshot.ai`——无账号可验证，徒增断言分支。

## R3 Kimi 协议选项取值

- **Decision**: 示例与验收模板固定 `options: {reasoning_effort: low}`；不写 `temperature`、不写 `thinking`。不修改 `RuntimeConfigLoader` 的选项校验集合（仍为 `low/medium/high`）。
- **Rationale**: 官方：K3 / K3.1 Pro 始终思考，`reasoning_effort` 支持 `low/high/max`，默认 `max`；`thinking` 对 K3 不支持或只能 enabled；temperature 固定。`low` 是维护者确认的默认（Clarifications Q3）。校验集合是跨 Provider 的通用规则（DeepSeek 等使用 `medium`），按 Provider 名称分支校验会违背“Provider 无特例、显式映射”的设计；`medium` 被 Kimi 拒收已在 spec Edge Cases 规定为真实失败 + 文档说明。
- **Alternatives considered**: 把 `max` 加入校验集合——默认值已可通过省略获得，且耗时超出 60 秒预算，无需求；按 provider 区分校验——引入 Provider 特例。

## R4 为什么必须新建 Kimi 专属测试分组

- **Decision**: 新增两个 real 模式分组 `real-provider-kimi`、`real-provider-tools-kimi`，各对应一个新测试类；原 `real-provider`、`real-provider-tools` 分组与类、方法名均不变。
- **Rationale**: `tools/dev/check-test-reports.py` 要求分组登记的每个方法都在本次 runId 下实际通过；原分组登记了 deepseek 与 kimi 两个方法，且 `@BeforeEach` 先对两家做前置检查——只有 Kimi 时整组必然失败（这是 FR-007 要保留的语义）。JUnit 按 `@Tag` 选择类，因此 Kimi 专属入口必须是带新 Tag 的独立类，不能给原类加 Tag（否则双家分组会额外跑一遍 Kimi 请求）。
- **Alternatives considered**: 在原类里用系统属性选择 Provider——改变原入口语义且分组报告检查无法表达“只要 Kimi”；`assumeTrue` 跳过 DeepSeek——宪章 VI 与现有“不 skip”约定禁止。

## R5 避免两套真实验收逻辑分叉

- **Decision**: 把现有两个真实用例中的“前置检查 + 一次执行 + 写证据”抽到同包测试支持类（`RealProviderAcceptance`、`RealToolLoopAcceptance`），参数为需要检查的 Provider 集合与 Agent 名；原类与新 Kimi 类都只是薄壳，声明 Tag 与 `@Test` 方法。
- **Rationale**: 同一断言集合（主机、HTTPS、路径、占位模型、无工具/恰一次工具、配对、reasoning 回传、审计）只维护一份，保证 Kimi 单家与双家判定口径一致。
- **Alternatives considered**: 复制一份 Kimi 类——两处断言迟早漂移。

## R6 FR-003 “普通请求先行通过”如何保证

- **Decision**: 推荐一次命令同时选择两个 Kimi 分组：`-pl oryxos-boot -am -Dgroups=real-provider-kimi,real-provider-tools-kimi`。Maven reactor 先构建并测试 `oryxos-provider`，失败即停止，`oryxos-boot` 的工具回填不会执行；分开运行时，仓库摘要必须同时引用普通请求通过的 runId，否则 T042-Kimi 不得勾选。
- **Rationale**: 利用既有 reactor 顺序与 fail-fast，无需让测试读取上一次证据文件（那会把本机证据目录变成测试输入，增加脆弱性）。
- **Alternatives considered**: 工具回填用例开头再做一次普通请求——多花一次付费调用且与 US1 证据重复。

## R7 证据字段补齐

- **Decision**: 普通请求证据补 `durationMs`、`completionsPath`；工具回填证据补 `requestedModel`、`finalReplyHasFacts`、`durationMs`、各次模型调用 token 汇总（取不到为 `null`）、`failureReason`（`NO_TOOL_CALL` / `MULTIPLE_TOOL_CALLS` / `TOOL_NOT_EXECUTED_ONCE` / `PAIRING_MISSING` / `REASONING_NOT_REPLAYED` / `EXECUTION_FAILED` / `FINAL_REPLY_MISSING_FACT` / `PROVIDER_ERROR`）。原实现未断言最终回复内容，本特性新增该断言（spec US2 场景 1、SC-002），双家入口共用同一断言。证据仍写入 `.oryxos-dev/evidence/us1-real/<runId>/kimi.json`、`.oryxos-dev/evidence/us2-real/<runId>/kimi.json`。
- **Rationale**: FR-006 要求耗时与 token；Clarifications Q5 要求“未调用工具”可读地判失败。沿用 spec 001 的目录，双家与单家证据格式一致，摘要可统一引用。
- **Alternatives considered**: 新目录 `kimi-real/`——与双家证据割裂，无收益。

## R8 凭证只传 Kimi

- **Decision**: `tools/dev/build.py` 把“分组 → 允许转发的环境变量”改为显式映射：`real-provider`/`real-provider-tools` → `DEEPSEEK_API_KEY`、`KIMI_API_KEY`；`real-provider-kimi`/`real-provider-tools-kimi` → 仅 `KIMI_API_KEY`。`tools/dev/test_build_environment.py` 增加 Kimi 分组只转发 Kimi、不转发 DeepSeek/ACSP/Mem0 的用例。
- **Rationale**: FR-011 与宪章 IV 最小暴露。

## R9 Kimi 单家验收工作区模板

- **Decision**: 新增 `tools/dev/examples/real-provider-kimi/`：`runtime.yaml` 只含 `kimi` 段 + 回环工具夹具白名单（`http://127.0.0.1:18473`）；`agents/real-kimi/AGENT.md`（无工具）与 `agents/real-tools-kimi/AGENT.md`（`http_get`），模型名为 `REPLACE_` 占位。两个 Kimi 分组共用这个工作区。
- **Rationale**: 现有 `real-provider-tools/runtime.yaml` 含 DeepSeek 段，复制后会出现 deepseek 不可用诊断，干扰维护者判断；单独模板让“只有 Kimi”成为显式、可复制的路径。

## R10 init 模板与用户示例

- **Decision**: `oryxos-cli/src/main/resources/templates/runtime.yaml` 与 `examples/runtime.example.yaml` 各加入一段**注释状态**的 Kimi Provider 块（内容见 [contracts/kimi-provider-config.md](./contracts/kimi-provider-config.md)）；`providers: {}` / 现有 `example` 段保持生效内容不变。
- **Rationale**: Clarifications Q4；`KernelFixture` 会读取 `examples/runtime.example.yaml` 并替换 `model.example.invalid` 端点，若加入生效的 kimi 段，会使本地内核测试多出一个“不可用 Provider”，改变既有 9 项内核测试的前提。注释块对 YAML 解析零影响。
- **Alternatives considered**: 生效段 + 默认禁用标记——配置模型里没有“禁用”字段，需要新契约。

## R11 本地可验证的 US3 链路

- **Decision**: 新增本地分组 `kimi-onboarding`（oryxos-boot，测试类 `KimiOnboardingLocalIT`），覆盖：①用真实 `init` 模板初始化，状态不出现 kimi；②按文档取消注释、把端点替换为回环假服务、注入夹具密钥，经真实 CLI 主入口对话一次；③假服务断言 `POST /v1/chat/completions`、`Authorization: Bearer <fixture>`、请求模型名、`reasoning_effort: low`、请求体无 `temperature`/`thinking`；④同工作区另一引用 `example`/deepseek 假服务的 Agent 只到达自己的 Provider；⑤状态/错误输出不含夹具密钥字面值。另加一个纯配置用例：`examples/runtime.example.yaml` 与 init 模板的注释块取消注释后可被 `RuntimeConfigLoader` 解析为可用的 `kimi` 定义，且两处块内容一致。
- **Rationale**: spec US3 Independent Test；宪章 VI 要求从 CLI 入口验证。端点替换只发生在测试内存中，不改示例文件。

## R12 spec 001 任务拆分方式

- **Decision**: 在 `specs/001-single-node-agent-runtime/tasks.md` 中把 T027、T042 各拆成两个子项（`T027-K` Kimi、`T027-D` DeepSeek；`T042-K`、`T042-D`），父任务说明“两个子项都完成才可勾选”；T028、T044 及 T098–T100 的前置引用不变（仍指父任务）。`verification.md` 分组表登记两个新分组 → 子项。`evidence/US1-real-models.md`、`evidence/US2-real-tools.md` 增加指向 007 摘要的链接。
- **Rationale**: Clarifications Q2（FR-008）；保持 spec 001 验收口径不变。

## 风险与未决

- 真实 Kimi 返回中若出现现有映射未覆盖的字段（例如新版本的 usage 子字段），属于真实联调缺陷，按 Assumptions 另行处理。
- 本机网络若必须经代理才能访问 Kimi，现有传输不读系统代理，真实验收会失败；本特性不引入代理支持。
- 维护者在对话中泄露过一把密钥，已建议作废重建；本计划所有产物不包含该字面值（SC-003 检索验证）。
