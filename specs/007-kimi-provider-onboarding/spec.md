# Feature Specification: Kimi Provider 独立接入与真实验收

**Feature Branch**: `007-kimi-provider-onboarding`（规格目录名；本次未创建 Git 分支）

**Created**: 2026-11-24

**Status**: Draft

**Input**: User description: "我申请了一个 kimi 的 key，咱们把 kimi 模型也加入这个项目吧"（原文中的密钥明文已从规格输入中移除，不在仓库任何文件中出现）

## 背景与现状（规格输入）

“加入 Kimi”在当前检出中**不是从零接入**。2026-11-24 核对实际代码与文档的结果如下：

| 事项 | 当前检出 | 结论 |
| --- | --- | --- |
| 协议适配 | Provider 层只支持 `openai_chat_completions` 协议；Kimi（Moonshot）提供该协议兼容接口 | 已可调用，无需新增协议 |
| Kimi 专有选项 | 请求映射不会默认附带 Kimi 拒收的 temperature 默认值；支持 `reasoning_effort` 选项 | 已覆盖 |
| 配置样例 | `tools/dev/examples/real-provider*/runtime.yaml` 含 `kimi` 段（`https://api.moonshot.cn` + `/v1/chat/completions` + `${KIMI_API_KEY}`）；面向用户的 `examples/runtime.example.yaml` 只有匿名 `example` 段 | 开发者模板有，用户样例无 |
| 凭证传递 | 构建入口仅在显式 real 模式下向子进程传递 `KIMI_API_KEY`，普通构建清除 | 已覆盖 |
| 本地验证 | 可控假模型服务覆盖 Kimi 名称路由与工具 Schema | 已覆盖 |
| 真实普通请求验收（spec 001 T027） | 真实用例**强制 DeepSeek 与 Kimi 同时就绪**，任一缺失即整体失败 | **只有 Kimi 凭证时无法完成，阻塞** |
| 真实多轮工具回填（spec 001 T042） | 同样同时要求两家 | **阻塞** |
| 真实联调证据 | 从未在真实凭证下执行 | 未验证 |

因此本特性的价值是：让 Kimi 成为**可单独配置、单独验证、单独计入证据**的一等 Provider，使维护者凭现有的一把 Kimi 密钥即可完成真实联调；不重复实现已存在的协议适配。

## Clarifications

### Session 2026-11-24

- Q: 密钥由哪个 Kimi 开放平台签发？ → A: 中国站控制台 `platform.kimi.com/console/api-keys`；按官方 API 概述，对应服务地址 `https://api.moonshot.cn`、OpenAI 兼容路径 `/v1/chat/completions`（与现有开发模板一致）。
- Q: Kimi 单家真实验收通过后，spec 001 的 T027/T042 如何记账？ → A: 拆为 Kimi / DeepSeek 两个子项，Kimi 子项可先勾选；spec 001 验收口径与 Provider 运行规则均不变。
- Q: 示例配置与真实验收工作区中 Kimi 推理强度默认设为多少？ → A: `low`（最快、最省、60 秒预算内最稳）。
- Q: 示例配置和文档是否给出推荐的 Kimi 模型名？ → A: 用户文档推荐 `kimi-k3.1-pro` 并注明以账号控制台可见模型为准；真实验收模板保留 `REPLACE_` 占位，由维护者确认后填写。
- Q: `oryxos init` 生成的工作区配置是否预置 Kimi？ → A: 加入注释掉的 Kimi Provider 段，去掉注释即可生效；`providers` 默认仍为空，未启用时不产生“不可用”提示。
- Q: Kimi 真实验收证据放在哪里、哪些进入仓库？ → A: 原始证据只留本机开发证据目录（不入库）；仓库提交一份人工核对的摘要，spec 001 的 Kimi 子项引用该摘要勾选。
- Q: 工具回填验收中 Kimi 未调用工具、直接回答，是否算通过？ → A: 判失败并记录原因，不自动重跑；维护者可手动重跑，每次运行单独留证据。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 仅凭 Kimi 凭证完成真实普通请求验收 (Priority: P1)

维护者只申请了 Kimi 密钥，没有 DeepSeek 密钥。他在私有工作区配置 Kimi Provider 与一个引用它的 Agent，运行 Kimi 专属的真实验收入口，得到一次真实的 Kimi 普通文本回复，以及不含秘密的证据记录。DeepSeek 未配置不影响本次 Kimi 验收的成败判定。

**Why this priority**: 这是“把 Kimi 加入项目”最直接、可证明的结果；也是解除 spec 001 中 Kimi 侧真实联调阻塞的前提，后续工具回填依赖它。

**Independent Test**: 在只设置 `KIMI_API_KEY`、未设置任何 DeepSeek 凭证的终端中运行 Kimi 专属真实验收入口，检查其通过并产生证据文件；证据中可见实际 Provider 为 kimi、实际模型名、token 用量（或明确标注未知），且不含密钥。

**Acceptance Scenarios**:

1. **Given** 私有工作区已配置 Kimi Provider、引用 Kimi 的 Agent 已填写账号实际可用的模型名、终端仅提供 Kimi 凭证，**When** 维护者运行 Kimi 专属真实验收，**Then** 系统向 Kimi 官方接口发出一次普通请求，收到非空文本回复，无工具调用，验收通过，并写出包含实际 Provider、实际模型、耗时、token 用量与时间的证据。
2. **Given** 同上但未配置 DeepSeek，**When** 运行 Kimi 专属验收，**Then** 结果不因 DeepSeek 缺失而失败，也不出现“缺少 deepseek”的提示。
3. **Given** Kimi 凭证缺失或 Agent 模型名仍为占位值，**When** 运行 Kimi 专属验收，**Then** 在发出任何网络请求前明确失败并指出缺失项，不跳过、不切换到假服务、不判为通过。
4. **Given** 凭证无效或账号不可用该模型，**When** 运行验收，**Then** 失败并记录安全错误码与脱敏后的说明，审计/证据中不出现凭证原文，且不自动重试。

---

### User Story 2 - Kimi 真实多轮工具回填验收 (Priority: P2)

维护者在 US1 通过后，用 Kimi 运行一次需要调用工具的真实任务（例如查询受控天气夹具后给出建议），验证 Kimi 能提出工具调用、OryxOS 自行执行工具一次并回填结果、Kimi 基于结果给出最终回复。

**Why this priority**: Agent 的核心价值依赖工具调用。Kimi 能否正确完成工具调用与结果回填的多轮配对，只有真实模型能证明；但它依赖 US1 的连通结论。

**Independent Test**: 仅提供 Kimi 凭证，运行 Kimi 专属工具回填验收，检查工具夹具被调用恰好一次、会话中 assistant 工具调用与工具结果按调用 ID 配对、最终回复引用了夹具数据。

**Acceptance Scenarios**:

1. **Given** US1 已通过、工具验收 Agent 引用 Kimi 且声明了所需工具，**When** 运行 Kimi 工具回填验收，**Then** Kimi 恰好提出 1 个 `http_get` 工具调用（验收指令要求只调用一次；0 个或多于 1 个均判失败），该调用只被执行一次，结果按调用 ID 回填，最终回复包含夹具提供的城市与温度。
2. **Given** Kimi 返回了推理类附加字段，**When** 多轮继续请求，**Then** 后续请求仍被 Kimi 接受（协议要求回传的字段按协议回传），证据中不记录推理内容原文。
3. **Given** DeepSeek 未配置，**When** 运行本验收，**Then** 结果不受影响。
4. **Given** Kimi 未提出任何工具调用、直接给出最终回复，**When** 本次运行结束，**Then** 判为失败并在证据中记录“未发生工具调用”，不在同次运行内自动重跑；维护者手动重跑时产生新的运行标识与证据。

---

### User Story 3 - 普通用户按文档在自己的工作区启用 Kimi (Priority: P3)

普通使用者拿到发行版后，按照用户文档与示例配置，在自己的工作区中启用 Kimi：在运行配置里加入 Kimi Provider 段、通过环境变量或受控本地秘密文件提供密钥、在任一 Agent 的 frontmatter 中把 Provider 改为 Kimi 并填写模型名，重启后该 Agent 即通过 Kimi 回复。

**Why this priority**: 让 Kimi 对仓库外使用者可发现、可复制；但不阻塞维护者完成真实验收。

**Independent Test**: 在全新临时工作区按文档步骤操作（Kimi 端点替换为本地可控假服务），用 CLI 对话一次，确认请求到达假服务且请求路径、鉴权头和模型名符合 Kimi 配置；状态查询只显示 Provider 名称与可用状态，不显示密钥。

**Acceptance Scenarios**:

1. **Given** 新工作区由 `init` 生成，**When** 用户查看运行配置，**Then** 可见注释状态的 Kimi 段；未去掉注释时状态查询不出现 kimi，也无“不可用”提示。
2. **Given** 用户示例配置或 `init` 配置中提供了 Kimi Provider 段，**When** 用户复制该段、设置 `KIMI_API_KEY` 并把某 Agent 的 Provider 改为 kimi，**Then** 重启后该 Agent 的模型请求发往 Kimi 配置的端点。
3. **Given** 工作区同时配置 DeepSeek 与 Kimi，**When** 两个 Agent 分别引用不同 Provider，**Then** 每个 Agent 的请求只到达其引用的 Provider，审计中实际 Provider 与配置一致。
4. **Given** Kimi 段配置错误（缺密钥、端点非 HTTPS 等），**When** 启动运行时，**Then** 只有引用 Kimi 的 Agent 不可用，其他 Agent 照常工作，状态与错误中不回显秘密。

---

### Edge Cases

- 密钥同时存在于环境变量和工作区秘密文件时，按现有凭证解析优先级取值，规格不引入新的优先级规则。
- 维护者把密钥误写进 Agent 正文、命令行参数或仓库文件：文档必须明确禁止；构建与证据输出不得回显密钥。
- Kimi 账号余额不足、限流（429）或服务端 5xx：作为真实验收失败记录安全错误码，不自动重试、不判为通过。
- 本机配置了系统代理或网络需代理才能访问 Kimi：现有传输不读取系统代理，验收失败时证据须能区分网络不可达与鉴权失败。
- 使用者把 Kimi 推理强度配成 `medium`（本地配置校验允许、但 Kimi 拒收）或配置 `thinking.disabled`（K3 系列拒收）：作为真实请求失败记录安全错误码，不自动修正参数；文档事先说明。
- Kimi 返回的实际模型名与请求的模型名不同（别名/版本映射）：证据同时记录请求模型与实际模型，不视为失败。
- 同时运行 DeepSeek 与 Kimi 双家验收（spec 001 原入口）时，原有“两家都必须就绪”的严格语义保持不变。
- 证据文件中出现的模型回复可能包含提示词复述，证据写出前必须经过现有脱敏处理。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 继续以现有 OpenAI 兼容协议调用 Kimi，不为 Kimi 新增协议类型或独立适配实现；Kimi 与其他 Provider 一样通过 Provider 名称显式映射。
- **FR-002**: 系统 MUST 提供一个**仅针对 Kimi** 的真实普通请求验收入口，其前置检查只要求 Kimi Provider 配置、Kimi 凭证与引用 Kimi 的验收 Agent；DeepSeek 是否配置不影响其结果。
- **FR-003**: 系统 MUST 提供一个**仅针对 Kimi** 的真实多轮工具回填验收入口，前置要求与 FR-002 同理，并额外要求 Kimi 普通请求验收已有通过证据或在同次运行中先行通过。模型未提出工具调用时该次运行 MUST 判失败，系统 MUST NOT 自动重跑。
- **FR-004**: 两个 Kimi 专属验收入口 MUST 在缺少任一前置条件时于网络请求前失败并指出缺失项；MUST NOT 跳过、降级为假服务或判为通过。
- **FR-005**: 真实验收 MUST 校验 Kimi 端点为 HTTPS 且主机为中国站官方域名 `api.moonshot.cn`，请求路径为 `/v1/chat/completions`；示例配置默认使用该端点。国际站（`api.moonshot.ai`）密钥与中国站互不通用，不在本特性验收范围内。
- **FR-006**: 真实验收 MUST 写出证据：执行标识、时间、Provider、请求模型、实际模型、请求内容、回复（脱敏后）、token 用量（不可得时标注未知）、耗时、成功/失败及安全错误码；MUST NOT 记录凭证与推理内容原文。原始证据 MUST 只写入本机不受版本控制的开发证据目录；仓库内 MUST 另有一份经维护者核对的 Kimi 真实验收摘要（运行标识、时间、请求/实际模型、结果、token、耗时、失败码，不含回复全文），作为 FR-008 勾选依据。
- **FR-007**: 原有 DeepSeek + Kimi 双家真实验收入口 MUST 保持“两家都必须就绪”的严格语义，不因本特性放宽。
- **FR-008**: spec 001 的 T027（真实普通请求）与 T042（真实工具回填）MUST 各拆分为 Kimi 子项与 DeepSeek 子项。Kimi 子项凭本特性的 Kimi 专属验收证据单独勾选；DeepSeek 子项保持未完成，直到取得 DeepSeek 真实证据；父任务及依赖它们的 T028 等任务仍须两个子项都完成才能勾选。spec 001 的验收口径（两家都须真实通过）不变，Provider 运行规则不变。
- **FR-009**: 面向用户的示例运行配置 MUST 提供可直接复制、不含秘密的 Kimi Provider 段（端点、路径、凭证占位、连接超时及 Kimi 适用选项），推理强度默认 `low`；MUST 标注：Kimi 固定 temperature、不应配置该项；K3 系列始终开启思考、不应配置 `thinking` 项；Kimi 推理强度只接受 `low` / `high`（不设置时为官方默认 `max`，耗时可能超出单次预算）。
- **FR-009a**: Kimi 真实验收工作区模板 MUST 使用推理强度 `low`，使单次请求在 60 秒预算内完成的概率最大。
- **FR-009b**: `init` 生成的新工作区运行配置 MUST 包含一段注释状态的 Kimi Provider 示例（与 FR-009 内容一致、不含秘密），默认生效的 Provider 列表保持为空；`init` 对已存在的配置文件保持幂等、不覆盖。去掉注释并提供凭证后重启即可使用。
- **FR-010**: 用户文档 MUST 说明启用 Kimi 的步骤：配置 Provider 段、通过环境变量或受控本地秘密文件提供密钥、在 Agent frontmatter 中引用 kimi 并填写账号可用的模型名、重启生效；并说明不得把密钥写入命令行参数、Agent 正文或仓库文件。文档 MUST 以 `kimi-k3.1-pro` 作为推荐模型名，并注明以账号控制台实际可见的模型为准；真实验收模板中的模型名 MUST 保持 `REPLACE_` 占位，未替换时按 FR-004 失败。
- **FR-011**: 构建与测试入口 MUST 只在显式真实模式且选择 Kimi 相关验收分组时向子进程传递 Kimi 凭证；普通构建继续清除凭证。
- **FR-012**: 只有 Kimi 配置错误时，系统 MUST 只令引用 Kimi 的 Agent 不可用，其余 Agent 不受影响；状态查询与错误信息 MUST NOT 回显密钥。
- **FR-013**: 每次 Kimi 模型调用 MUST 与其他 Provider 一样进入现有审计，记录实际 Provider、模型、token 与耗时。

### Key Entities

- **Kimi Provider 定义**：运行配置中名为 `kimi` 的 Provider 段；包含协议、端点、请求路径、凭证引用（非明文）、连接超时与协议选项。重启加载。
- **Kimi 验收 Agent**：私有工作区中引用 kimi 的 Agent 定义；普通请求验收 Agent 不声明工具，工具回填验收 Agent 声明受控工具。模型名由维护者填写账号实际可用值。
- **Kimi 真实验收证据**：一次真实运行的不含秘密的原始记录，按运行标识归档在本机，不入库。
- **Kimi 真实验收摘要**：仓库内经维护者核对的摘要，引用一个或多个运行标识；是 spec 001 中 T027/T042 Kimi 子项的勾选依据（FR-008）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 维护者在只持有 Kimi 一把密钥的条件下，按文档在 15 分钟内完成私有工作区配置并跑通 Kimi 真实普通请求验收，验收结果为通过。
- **SC-002**: Kimi 真实工具回填验收中，模型提出的工具调用数为 1 且执行次数为 1，工具调用与结果配对率 100%，最终回复包含夹具提供的城市与温度。
- **SC-003**: 所有 Kimi 验收证据、审计记录、构建输出与状态查询中，凭证原文出现次数为 0（按密钥字面值全文检索验证）。
- **SC-004**: 缺少 Kimi 任一前置条件时，100% 的验收运行在发出网络请求前失败并给出可读的缺失项说明。
- **SC-005**: 新使用者仅凭用户文档与示例配置即可让一个 Agent 改用 Kimi 回复，无需阅读源码或开发者模板。
- **SC-006**: 本特性交付后，全部已有本地测试仍全部通过且数量不减少；原双家真实验收入口的判定语义不变（仅有 Kimi 凭证时它仍以“缺少 deepseek”失败）。

## Assumptions

- Kimi 继续兼容 OpenAI Chat Completions 协议（含工具调用字段）；若真实联调发现不兼容点，作为缺陷在后续 plan 中单独处理，不在本规格预设新协议。
- 用户文档推荐 `kimi-k3.1-pro`（2026-11-24 官方示例模型），但模型清单会变动；真实验收模板不预填模型名，由维护者按账号权限填写。
- 真实联调需要本机可直接 HTTPS 访问 Kimi 官方接口；验收不经过代理。
- 三个整合 Demo（天气、科技日报、GitHub 日报）的示例 Agent 不因本特性默认切换为 Kimi，仍由使用者自行选择 Provider。
- 不引入 Provider fallback、自动重试或多 Provider 竞速；这些仍不在当前阶段范围。
- 维护者在对话中提供过一次密钥明文；该密钥只应通过本机环境变量或权限为 600 的工作区秘密文件注入，任何仓库文件、证据或日志都不得包含它。
