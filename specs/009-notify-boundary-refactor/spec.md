# Feature Specification: Notify 通知模块边界对齐与验收 harness（对齐第 19 节课件与技术方案）

**Feature Branch**: `009-lesson19-notify-boundary`（规格目录名 `009-notify-boundary-refactor`）

**Created**: 2026-11-24

**Status**: Draft

**Input**: User description: "第19节需求：Notify 通知模块——补齐与入站 Channel 对称的出站主动推送能力。背景与价值：CLI 与 Web Service 都是人发起的同步请求-响应链路，而定时任务（如每日天气、科技日报）到点自动运行后无人等待响应，Agent 必须主动将结果送到人能看到的通知目标；若无统一通知模块，每个业务 Agent 都要重复拼装外发逻辑或额外挂载专用 MCP server。沿用 PR #7（005）/ PR #8（006）/ 008 的边界对齐规则：落地课件“先定接口、后挂实现”的渠道中立出站抽象，保留已确认的 spec 001~008 生产契约与偏差记录，补齐课件验收 harness 命名的两套测试套件，并交付第 19 节课件修订建议。"

## 背景与现状（规格输入）

本规格覆盖 OryxOS 的 **出站通知推送（Notify）模块**，是一次**保持既有外部行为不变**的边界对齐、接口抽象补齐与验收 harness 补齐。
输入是仓库内 `docs/第19节：Notify 模块 原理解析、实现与代码讲解.md`（下称“课件”）、`docs/TechnicalSolution.md`（§6.8、§10）与 2026-11-24 当前检出的代码。课件是教学示意，凡与已确认宪章 v1.0.0、spec 001~008 冲突处，以后者为准（见“保留的偏差”）。

### 课件原则与当前代码对照

| 课件要求 | 当前实现 | 结论 |
| --- | --- | --- |
| 先定渠道中立的出站抽象接口与目标模型，表达“把内容送到某个通知目标”，不携带具体渠道专属词 | 当前仅有具体类 `WebhookNotifyAdapter` 与嵌套记录 `NotificationTargetResolver.Target`，`NotifyTool` 直接绑定具体类，**缺少渠道中立的通知适配器接口与独立通知目标抽象** | **需补齐：新增渠道中立的通知适配器接口与目标模型，让具体 webhook 实现该接口、通知工具依赖接口** |
| 核心阶段只做通用 webhook 一档实现，不逐家接企微/飞书/钉钉专用签名与 AccessToken 刷新 | `WebhookNotifyAdapter` 已承接通用 HTTP webhook 推送，支持模板替换、JSON 安全转义与响应确认 | 已满足（需对接新抽出的渠道中立接口） |
| 外发前必须先过域名白名单校验，与 HTTP 工具共享同一套白名单配置、不另造安全层 | 通知发送前先经目标白名单授权检查，与 HTTP 工具共用同一份 `http` 目标策略 | 已满足（需在 harness 中用顺序断言钉死） |
| 通知目标配置在 Agent Profile 的 `notify_channels`，地址与凭证通过环境变量引用注入，不暴露在对话和日志中 | `AgentDefinitionReader` 与 `AgentProfile` 已支持 `notify_channels`、`default_target`、密钥引用解析与 `toString()` 脱敏 | 已满足 |
| 调用时 `channel` 参数可选，缺省取默认渠道；未配置目标时明确报错，不静默失败 | `NotificationTargetResolver` 支持单渠道/显式默认渠道自动解析，多渠道无默认或未配置时在网络 IO 前明确报错 | 已满足 |
| 审计复用统一工具执行审计路径，落 `tool_invocations`，不另造审计机制 | `NotifyTool` 实现统一工具契约，经统一工具执行器记录成功、失败与拒绝审计 | 已满足 |
| 验收 harness：`WebhookNotifyAdapterTest`、`NotifyToolsTest` | 两个测试类均不存在；相关断言散落在 `NotifyContractTest` 与 `ToolIntegrationTest` | **可定位性不足，需新增两个课件命名测试类并补齐关键回归守点** |

### 保留的偏差（不按课件改动）

| 课件写法 | 保留的已确认依据 |
| --- | --- |
| 工具类名为 `NotifyTools`，以框架 `@Tool` 注解声明 `notify(content, channel)`，直接注入 `Sandbox`、`ProfileContext` | 宪章 II 禁止框架自动工具执行；本仓库所有内置工具统一实现 `OryxTool` 契约并由 `ToolRegistry` + `ToolExecutor` 统一校验、调度与落库审计。工具类保留 `NotifyTool`（工具名 `"notify"`，参数 `content` 与可选 `targetName`），避免破坏装配层、CLI 查询与集成测试既有契约 |
| `WebhookNotifyAdapter` 使用 Spring `RestClient` 发送固定 `{"content": ...}`，5xx 时抛异常 | `oryxos-tool` 不引入 `spring-web`；现行实现基于受控 HTTP 客户端，具备域名白名单、超时、响应上限、禁用重定向、自定义 payload 模板与 `$CONTENT` 安全 JSON 转义、业务 ack 校验，以及“已发出但连接断开返回 `UNKNOWN` 且绝不重试”的副作用安全契约（宪章 II / IV / spec 001） |
| `notify_channels` 为简化列表（`- type: webhook` / `url: ${...}`） | 现行 `AGENT.md` frontmatter 采用按目标名索引的映射结构（支持多目标、可选 `default_target`、私有 `headers`、自定义 `payload` 与 `acknowledgement`），并强制 URL/Header 走密钥引用而非明文（宪章 IV / spec 001） |
| 权限校验调用 `Sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))` | 统一 `Sandbox` 抽象属于第 24 节范畴；本节保留既有授权前置链（工具 `authorize` → 通知适配器 `authorize` → 受控 HTTP 客户端目标白名单校验），在 24 节再统一收敛，不在本节提前发明 24 节对外接口 |
| `NotifyTools` 完整接线推迟至 20/24 节后 | 本仓库 spec 001 已交付统一工具执行器与 HTTP 白名单策略，因此本节可同时落地 `WebhookNotifyAdapterTest` 与 `NotifyToolsTest` 两批验收测试，无需留空 |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 渠道中立的通知抽象与通用 Webhook 推送（Priority: P1）

系统维护者希望出站通知层先定义不携带具体渠道细节的抽象接口与通知目标模型，核心阶段由通用 webhook 实现承接；未来接入企业微信、飞书或邮件等专用渠道时，只需新增适配器实现而不必修改通知工具或调用方。

**Why this priority**: “先定接口、后挂实现”是第 19 节最核心的架构习惯；没有渠道中立抽象，通知工具就会与具体 webhook 实现强耦合，违背宪章 III 职责分离原则。

**Independent Test**: 使用本地假 webhook 服务运行 `WebhookNotifyAdapterTest`，验证请求确实发往通知目标配置的地址而非硬编码地址、消息正文正确编码在请求体中、5xx 或业务拒绝时明确报失败不静默吞掉、连接中断时标记结果未知且不重试；同时验证通知工具仅依赖抽象接口即可工作。

**Acceptance Scenarios**:

1. **Given** 一个配置了本地假 webhook 地址的通知目标与通用 webhook 适配器，**When** 发送包含引号、换行与表情符的消息内容，**Then** 本地服务收到一次 POST 请求，请求路径与目标配置一致，请求体中的内容与原文完全一致且返回成功结果。
2. **Given** 本地假 webhook 服务返回 5xx 错误（或业务确认字段表示拒绝），**When** 通过适配器发送通知，**Then** 发送结果明确反映失败而不静默当作成功，且不会静默重试导致重复推送风险。
3. **Given** 本地假 webhook 服务在收到请求后直接断开连接，**When** 通过适配器发送通知，**Then** 返回结果状态为未知（不伪造成功或确定未达），且不自动重试。
4. **Given** 需要新增另一种通知渠道实现，**When** 实现统一通知适配器接口并注入通知工具，**Then** 无需修改通知工具或上层调用方接口签名。

---

### User Story 2 - 通知工具的安全前置校验与默认渠道解析（Priority: P1）

当 Agent 在定时任务或对话中调用 `notify` 推送消息时，系统必须从当前 Agent 配置解析通知目标（单渠道或显式默认渠道时可省略渠道名；未配置或歧义时明确报错），并且**必须在任何实际外发动作之前完成白名单授权校验**，校验不通过时绝不触发网络外发。

**Why this priority**: 通知推送本质是向外网发起带副作用的请求，必须守住“白名单先行、配置缺失不静默、地址不进对话”三条安全红线。

**Independent Test**: 运行 `NotifyToolsTest`，使用录制调用顺序的测试替身验证：未配置通知渠道时明确报错且不发请求；缺省渠道参数时自动选取唯一或默认渠道；授权校验严格先于发送调用；授权校验失败时绝不调用发送；通过统一工具执行器执行时成败与拒绝均记录审计。

**Acceptance Scenarios**:

1. **Given** 当前 Agent 未配置任何 `notify_channels`，**When** 调用 `notify` 工具，**Then** 系统在发起任何发送前明确报错，不静默返回成功，也不产生外发调用。
2. **Given** 当前 Agent 仅配置了一个通知渠道（或显式指定了默认渠道），**When** 调用 `notify` 且省略渠道名参数，**Then** 系统自动解析到该默认渠道并完成推送。
3. **Given** 当前 Agent 配置了合法通知渠道，**When** 调用 `notify` 执行授权与发送，**Then** 白名单授权校验严格发生在消息发送之前；若授权校验拒绝，消息发送绝不被调用。
4. **Given** `notify` 通过统一工具执行器被调用，**When** 推送成功或被拒绝，**Then** 工具执行审计记录准确反映对应结果，且不包含 webhook 地址或私有凭证等敏感信息。

---

### User Story 3 - 既有通知契约零回归与课件差异可追溯（Priority: P2）

维护者与学员在对齐第 19 节后，既要确保 spec 001~008 已建立的内置工具、模板配置与跨进程验收测试全部保持绿色，也要能在一份修订建议文档中清楚查到课件写法与现行实现的每一处差异及代码定位。

**Why this priority**: 保持既有生产契约稳定与文档可追溯，避免后续第 20/24/25 节叠加演进时产生口径分裂。

**Independent Test**: 运行既有工具测试套件与全量构建门禁全部通过；逐条核对修订建议文档，每项保留偏差均有真实文件路径与依据。

**Acceptance Scenarios**:

1. **Given** 既有 `NotifyContractTest`、`ToolIntegrationTest` 及配置模板测试，**When** 执行全量构建与验收分组，**Then** 全部既有测试零改动或兼容通过，外部可观察行为不变。
2. **Given** 第 19 节课件修订建议文档，**When** 对照“保留的偏差”逐项检查，**Then** 每项差异均标注课件原文、现行实现、代码位置与验收测试映射。

---

### Edge Cases

- Agent 配置了多个通知渠道但未指定默认渠道，且调用时未传渠道名：必须在网络 IO 前明确拒绝，要求调用方指定目标名。
- Agent 调用时传入了未在自身 `notify_channels` 中声明的渠道名（或属于其他 Agent 的渠道名）：必须在网络 IO 前以目标拒绝报错，防止越权推送。
- 通知目标 URL 或私有 Header 包含敏感凭证（如 webhook token query 参数或自定义鉴权头）：目标对象的字符串表示、工具 Schema 描述、返回结果与审计记录均不得泄露明文凭证。
- 发送请求已发出但对端无响应或中途断连：返回未知状态且禁止自动重试，避免重复通知轰炸群聊。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 定义渠道中立的通知适配器抽象接口与通知目标模型，接口签名与目标模型不得绑定单一渠道实现专属概念（如企业微信、飞书或特定厂商认证字段）。
- **FR-002**: 通用 webhook 通知适配器 MUST 实现该渠道中立通知适配器接口，使用目标配置中的地址和参数发起推送，不得硬编码目标地址。
- **FR-003**: 通知工具 MUST 依赖渠道中立的通知适配器抽象接口而非具体 webhook 实现类，同时保持既有构造与调用方式兼容，不破坏现有装配层与测试契约。
- **FR-004**: 通用 webhook 通知适配器在收到 5xx 响应或业务确认失败时 MUST 明确返回失败结果，不得静默吞掉错误，且不得自动重试产生重复副作用；连接在发送后中断时 MUST 返回未知状态且不重试。
- **FR-005**: 当当前 Agent 未配置通知渠道、配置多个渠道却未指定默认且调用未传目标名、或传入未声明目标名时，系统 MUST 在任何外发动作之前明确报错。
- **FR-006**: 当当前 Agent 仅配置单一通知渠道或已配置默认渠道时，调用 `notify` 省略目标名参数 MUST 自动解析至该默认渠道。
- **FR-007**: `notify` 执行链路 MUST 保证白名单授权校验严格先于实际发送动作执行；授权校验失败时 MUST 阻断发送，零网络 IO。
- **FR-008**: 通知目标敏感配置（URL、凭证头等）MUST 仅通过环境变量或受控本地配置解析，不得暴露在工具输入 Schema、对象默认字符串表示、工具返回消息或审计记录中。
- **FR-009**: 系统 MUST 提供与课件验收 harness 对应的 `WebhookNotifyAdapterTest` 与 `NotifyToolsTest` 两个测试类，全部测试方法名为英文并以显示名标注课件对照语义，并纳入既有测试分组门禁管理。
- **FR-010**: 本次变更 MUST 不新增第三方依赖，不修改前序第 16/17/18 节的公共接口与数据库表结构，且 MUST 交付第 19 节课件修订建议文档记录全部保留偏差与代码映射。

### 明确不做（边界）

- 不接入企业微信、飞书、钉钉等任何单一平台的专用 SDK、签名算法或 AccessToken 刷新逻辑。
- 不在本节设计或新增独立于现有体系的 `Sandbox` 完整框架（留待第 24 节统一收敛），也不另建独立的通知审计表。
- 不把 webhook 地址或密钥暴露在对话上下文、工具参数提示或普通日志中。
- 不替代专用 MCP server 提供的富文本卡片、交互式消息等复杂通道能力。
- 不引入新的第三方 HTTP 客户端或测试框架依赖。

### Key Entities *(include if feature involves data)*

- **通知目标（NotifyTarget）**: 渠道中立的出站目标描述，包含渠道类型标识与渠道配置映射（对敏感内容脱敏），不泄露具体渠道实现细节。
- **通知渠道适配器（NotifyChannelAdapter）**: 出站通知抽象契约，定义外发前的授权校验与内容推送行为，使通知工具与具体传输渠道解耦。
- **Agent 通知配置（notify_channels）**: 声明在 Agent Profile 中的出站目标集合，包含渠道类型、密钥引用形式的目标地址、可选私有头、消息模板、确认方式与默认目标标记。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `WebhookNotifyAdapterTest` 与 `NotifyToolsTest` 两个课件命名测试类全部存在且 100% 通过，覆盖动态目标地址、正文编码、5xx/拒绝失败不静默、断连不重试、未配置报错、缺省渠道解析、白名单授权严格先于发送等全部回归点。
- **SC-002**: 通知工具不再直接依赖具体 webhook 适配器类，替换为一个全新的自定义通知适配器实现无需修改通知工具任何代码。
- **SC-003**: 既有工具契约测试、前序第 16/17/18 节全部回归测试与全量工程质量门禁（构建、单测、格式与静态分析、质量基线检查）100% 通过，零新增依赖、零新增基线告警。
- **SC-004**: 修订建议文档完整记录本节全部保留偏差、代码定位与验收测试映射，且明确列出剩余需人工确认项（真实 webhook 推送验证与接口中立性评审）。

## Assumptions

- 前序第 16、17、18 节交付的 Provider、ReAct、ToolExecutor、ProfileContext、CLI 与会话层均已在代码库就位且保持契约稳定。
- 统一 `Sandbox` 接口抽象将在第 24 节落地；本节通过现有目标白名单授权前置机制守住“白名单先行”不变量，并在接口与注释中清晰标注后续对接点。
- 真实外部 webhook（如企业微信/飞书/钉钉群机器人）推送验证属于人工冒烟确认项，不纳入离线自动化单测。
