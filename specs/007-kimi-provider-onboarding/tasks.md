---

description: "Task list for 007 Kimi Provider 独立接入与真实验收"
---

# Tasks: Kimi Provider 独立接入与真实验收

**Input**: Design documents from `specs/007-kimi-provider-onboarding/`

**Prerequisites**: [plan.md](./plan.md)、[spec.md](./spec.md)、[research.md](./research.md)、[data-model.md](./data-model.md)、[contracts/](./contracts/)、[quickstart.md](./quickstart.md)

**Tests**: 本特性的主要交付物就是验收测试（真实验收入口与本地 `kimi-onboarding` 分组），测试任务按故事列出；US3 先写测试、确认失败后再改模板。

**Organization**: 按用户故事分组；US1（P1）为 MVP。生产代码（`oryxos-*/src/main`）不改。

**约束（所有任务）**：

- 任何文件、命令参数、证据、日志都不得出现 Kimi 密钥字面值；只使用 `${KIMI_API_KEY}` 引用。
- `tools/dev/test-groups.json`、`tools/dev/README.md`、`specs/001-single-node-agent-runtime/verification.md` 已有未提交改动（006），只做增量追加，不覆盖、不重排。
- 构建一律经 `./tools/dev/mvnw`（需 `export ORYXOS_JAVA_HOME=<JDK21>`）。
- 原 `real-provider`、`real-provider-tools` 分组、类名、方法名、双家前置检查不得改变（FR-007）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: US1 / US2 / US3

---

## Phase 1: Setup

**Purpose**: 记录改动前基线，避免把既有失败算到本特性头上

- [X] T001 记录基线：运行 `./tools/dev/mvnw -B -ntp clean verify`（全部本地分组）与 `python3 tools/dev/test_build_environment.py`，把门禁打印的 `Test evidence PASS: N required cases` 中的 N（全量本地用例总数，供 T031 比较）、Python 用例数与 `git status --short tools/dev specs/001-single-node-agent-runtime` 输出写入 `specs/007-kimi-provider-onboarding/evidence/baseline.md`；若基线已失败，先停下报告，不继续后续任务

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 抽取共用断言、补齐证据字段、收紧凭证转发——US1、US2 都依赖

**⚠️ CRITICAL**: 完成前不得开始 US1/US2

- [X] T002 抽取普通请求验收支持类 `oryxos-provider/src/test/java/io/github/oryxlabs/oryxos/provider/RealProviderAcceptance.java`（包级可见，非测试类、无 `@Test`/`@Tag`）：方法 `prepare(List<String> providers)`（有序列表，按给定顺序逐个检查，首个缺失项决定失败信息；不得用 `Set`）承接原 `RealProviderIT#prerequisites` 全部断言（real 模式、绝对工作区、`runtime.yaml` 存在、每个 Provider 存在且可用、HTTPS、主机 `deepseek→api.deepseek.com`/`kimi→api.moonshot.cn`、Agent `real-<name>` 引用该 Provider、模型不以 `REPLACE_` 开头、工具为空），并**新增** kimi 的 `completionsPath` 必须等于 `/v1/chat/completions`；方法 `ordinary(String name)` 承接原 `ordinary` 逻辑，证据新增 `completionsPath` 与 `durationMs`（`System.nanoTime` 包围 `registry.call`），其余字段与路径 `.oryxos-dev/evidence/us1-real/<runId>/<name>.json` 不变；不得记录凭证与 `reasoning_content`
- [X] T003 将 `oryxos-provider/src/test/java/io/github/oryxlabs/oryxos/provider/RealProviderIT.java` 改为薄壳：保留类名、`@Tag("real-integration")`、`@Tag("real-provider")`、方法名 `deepseekOrdinaryRequest`、`kimiOrdinaryRequest`；`@BeforeEach` 调 `prepare(List.of("deepseek","kimi"))`（顺序固定为 deepseek→kimi，与原实现一致，使缺 deepseek 时报 `Missing runtime provider: deepseek`），测试方法委托 `ordinary(...)`（依赖 T002）
- [X] T004 [P] 抽取工具回填验收支持类 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/RealToolLoopAcceptance.java`：承接原 `RealProviderToolLoopIT` 的前置检查（参数为 `List<String>` 有序列表，不得用 `Set`；Agent `real-tools-<name>`、工具恰为 `[http_get]`、回环 URL `http://127.0.0.1:18473/weather` 通过 HTTP 白名单）、回环夹具、`CaptureConfiguration`/`CapturedProvider`、全部断言，并**新增**最终回复断言：执行结果的最终 assistant 回复文本同时包含城市（`Beijing` 或 `北京`）与温度 `23`；证据新增 `requestedModel`（Agent 配置的模型名，经 Redactor）、`finalReplyHasFacts`（布尔）、`durationMs`、`inputTokens`/`outputTokens`（各次 `reply.usage()` 求和，任一为 null 则整体 null）与 `failureReason`，取值固定为 `NO_TOOL_CALL`（提议调用数 0）、`MULTIPLE_TOOL_CALLS`（>1）、`TOOL_NOT_EXECUTED_ONCE`（夹具命中≠1 或工具审计 SUCCESS≠1）、`PAIRING_MISSING`（后续请求无配对 TOOL 消息）、`REASONING_NOT_REPLAYED`（无 opaque 或未回传）、`EXECUTION_FAILED`（执行状态非 SUCCEEDED）、`FINAL_REPLY_MISSING_FACT`（最终回复缺城市或温度）、`PROVIDER_ERROR`（OryxError），按断言顺序在失败前写入；证据路径 `.oryxos-dev/evidence/us2-real/<runId>/<name>.json` 不变；同次运行不自动重跑
- [X] T005 将 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/RealProviderToolLoopIT.java` 改为薄壳：保留类名、两个 Tag、方法名 `deepseekToolResultRoundTrip`、`kimiToolResultRoundTrip`，前置检查 `List.of("deepseek", "kimi")`，委托 T004 支持类（依赖 T004）
- [X] T006 [P] 修改 `tools/dev/build.py`：把凭证转发改为显式映射 `{'real-provider': ('DEEPSEEK_API_KEY','KIMI_API_KEY'), 'real-provider-tools': ('DEEPSEEK_API_KEY','KIMI_API_KEY'), 'real-provider-kimi': ('KIMI_API_KEY',), 'real-provider-tools-kimi': ('KIMI_API_KEY',)}`，仅 `mode == 'real'` 时取所选分组映射的并集并只转发环境中存在的键；Mem0、NVD 逻辑不变
- [X] T007 修改 `tools/dev/test_build_environment.py`：新增用例 `test_real_kimi_groups_forward_only_kimi_key`（`-Dgroups=real-provider-kimi` 与 `-Dgroups=real-provider-tools-kimi` 各一次：含 `KIMI_API_KEY`，不含 `DEEPSEEK_API_KEY`/`ACSP_TOKEN`/`MEM0_API_KEY`/`MEM0_MODEL_API_KEY`）与 `test_local_mode_with_kimi_group_name_forwards_nothing`；原用例保持通过；运行 `python3 tools/dev/test_build_environment.py`（依赖 T006；新分组须已在 `test-groups.json` 登记才能通过 `build.py` 的分组校验，因此本任务与 T011、T018 的登记同批完成后再运行）
- [X] T008 回归验证基础改造：运行 `./tools/dev/mvnw -B -ntp -pl oryxos-boot -am test-compile` 通过；在一个只含 kimi 的临时工作区运行 `-Doryxos.tests.mode=real -Dgroups=real-provider` 与 `-Dgroups=real-provider-tools`，确认均在发网络请求前以 deepseek 缺失失败（FR-007、SC-006）；结果记入 `specs/007-kimi-provider-onboarding/evidence/baseline.md`

**Checkpoint**: 原双家入口行为不变；共用断言就绪

---

## Phase 3: User Story 1 - 仅凭 Kimi 凭证完成真实普通请求验收 (Priority: P1) 🎯 MVP

**Goal**: 只有 `KIMI_API_KEY` 时可运行并通过 Kimi 普通请求真实验收（FR-002、FR-004、FR-005、FR-006）

**Independent Test**: 只设 `KIMI_API_KEY` 运行 `-Dgroups=real-provider-kimi`，得到 `Test evidence PASS: 1 required cases`，`us1-real/<runId>/kimi.json` 为 `SUCCEEDED`；缺凭证或占位模型时在网络请求前失败

- [X] T009 [P] [US1] 新建 Kimi 单家验收工作区模板 `tools/dev/examples/real-provider-kimi/runtime.yaml`：`schema_version: 1`、`timezone: Asia/Shanghai`、仅一个 Provider，内容与 [contracts/kimi-provider-config.md §1](./contracts/kimi-provider-config.md) 逐字一致（`protocol: openai_chat_completions`、`endpoint: https://api.moonshot.cn`、`completions_path: /v1/chat/completions`、`api_key: "${KIMI_API_KEY}"`、`connect_timeout_seconds: 5`、`options: {reasoning_effort: low}`，不得出现 `temperature`/`thinking`）；以及 `tools/dev/examples/real-provider-kimi/agents/real-kimi/AGENT.md`（`schema_version: 1`、`name: real-kimi`、`provider: {name: kimi, model: REPLACE_WITH_ACCOUNT_ENABLED_KIMI_MODEL}`、`tools: []`，正文同现有 `real-provider/agents/real-kimi`）
- [X] T010 [US1] 新建 `oryxos-provider/src/test/java/io/github/oryxlabs/oryxos/provider/RealKimiProviderIT.java`：`@Tag("real-integration")`、`@Tag("real-provider-kimi")`（不得带 `real-provider` Tag），`@BeforeEach` 调 `prepare(List.of("kimi"))`，唯一测试方法 `kimiOrdinaryRequest` 委托 `ordinary("kimi")`；类注释写明“Kimi-only; DeepSeek configuration is not an input”（依赖 T002）
- [X] T011 [US1] 在 `tools/dev/test-groups.json` 追加分组 `"real-provider-kimi": {"mode": "real", "tests": [{"module": "oryxos-provider", "class": "io.github.oryxlabs.oryxos.provider.RealKimiProviderIT", "methods": ["kimiOrdinaryRequest"]}]}`，保留文件内既有未提交内容
- [X] T012 [US1] 负向前置验证（不需要真实凭证）：复制 T009 模板到 `.oryxos-dev/real-provider-kimi.XXXXXX`，① 不设 `KIMI_API_KEY` 运行 `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Doryxos.tests.mode=real -Dgroups=real-provider-kimi -Doryxos.real.workspace=<ws>`，失败信息指向 kimi 配置/凭证；② 设夹具值但保留 `REPLACE_` 模型，失败信息指向模型；③ 确认失败信息中不含“deepseek”；结果写入 `specs/007-kimi-provider-onboarding/evidence/kimi-preflight.md`（依赖 T007、T010、T011）
- [X] T013 [US1] 真实普通请求验收（**维护者持有有效中国站密钥时执行**；无凭证则保持未勾选并在 `evidence/kimi-real.md` 标“未执行”）：按 [quickstart.md B](./quickstart.md) 在私有工作区填写账号可用模型（推荐 `kimi-k3.1-pro`），`read -rs KIMI_API_KEY && export KIMI_API_KEY` 后运行 `-Dgroups=real-provider-kimi`；预期 `Test evidence PASS: 1 required cases`
- [X] T014 [US1] 新建 `specs/007-kimi-provider-onboarding/evidence/kimi-real.md` 并填写普通请求摘要：runId、时间、请求模型、实际模型、结果、`inputTokens`/`outputTokens`（未知写“未知”）、`durationMs`、失败码（如有）、核对人确认“无密钥、无回复全文”；先用 `rg -c` 以密钥前若干位检索 `.oryxos-dev/evidence`、`oryxos-provider/target/surefire-reports`、`oryxos-boot/target/surefire-reports`、T013 构建控制台输出（运行时以 `2>&1 | tee .oryxos-dev/evidence/kimi-real-<runId>.log` 保存）与 `specs`，结果均为空（SC-003）（依赖 T013）
- [X] T015 [US1] 拆分 spec 001 T027：在 `specs/001-single-node-agent-runtime/tasks.md` 第 242 行附近把 T027 改为父项并新增子项 `T027-K`（Kimi，分组 `real-provider-kimi`，依据 `specs/007-kimi-provider-onboarding/evidence/kimi-real.md`）与 `T027-D`（DeepSeek，分组 `real-provider`），父项注明“两个子项都完成才可勾选；T028 前置仍指父项”；仅当 T014 摘要为通过时勾选 `T027-K`；在 `specs/001-single-node-agent-runtime/verification.md` 分组表追加 `| real-provider-kimi | RealKimiProviderIT | T027-K |`；在 `specs/001-single-node-agent-runtime/evidence/US1-real-models.md` 末尾追加指向 007 摘要的链接

**Checkpoint**: US1 可独立交付——Kimi 单家普通请求入口可用，spec 001 记账就绪

---

## Phase 4: User Story 2 - Kimi 真实多轮工具回填验收 (Priority: P2)

**Goal**: 只有 Kimi 凭证时验证工具调用恰一次、ID 配对、reasoning 回传（FR-003、FR-004、FR-006）

**Independent Test**: 只设 `KIMI_API_KEY` 运行 `-pl oryxos-boot -am -Dgroups=real-provider-kimi,real-provider-tools-kimi`，`Test evidence PASS: 2 required cases`；模型未调用工具时 `failureReason=NO_TOOL_CALL` 且不重跑

- [X] T016 [US2] 扩展 Kimi 模板：在 `tools/dev/examples/real-provider-kimi/runtime.yaml` 追加 `tools.http`（`allowed_targets: [{scheme: http, host: 127.0.0.1, port: 18473}]`、`connect_timeout_seconds: 5`、`read_timeout_seconds: 10`、`max_response_bytes: 65536`）；新建 `tools/dev/examples/real-provider-kimi/agents/real-tools-kimi/AGENT.md`（`name: real-tools-kimi`、`provider: {name: kimi, model: REPLACE_ACCOUNT_ENABLED_REASONING_TOOL_MODEL}`、`tools: [http_get]`、`settings: {max_iterations: 10}`，正文同现有 `real-provider-tools/agents/real-tools-kimi`）（依赖 T009）
- [X] T017 [US2] 新建 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/RealKimiToolLoopIT.java`：`@Tag("real-integration")`、`@Tag("real-provider-tools-kimi")`（不得带 `real-provider-tools` Tag），前置检查 `List.of("kimi")`，唯一方法 `kimiToolResultRoundTrip` 委托 T004 支持类（依赖 T004）
- [X] T018 [US2] 在 `tools/dev/test-groups.json` 追加分组 `"real-provider-tools-kimi": {"mode": "real", "tests": [{"module": "oryxos-boot", "class": "io.github.oryxlabs.oryxos.boot.RealKimiToolLoopIT", "methods": ["kimiToolResultRoundTrip"]}]}`
- [X] T019 [US2] 负向前置验证（不需要真实凭证；以夹具值 `export KIMI_API_KEY=fixture-not-a-real-key` 使 kimi 配置可用，前置失败发生在任何网络请求前）：① 用 T016 模板（保留 `REPLACE_`）运行 `-pl oryxos-boot -am -Doryxos.tests.mode=real -Dgroups=real-provider-tools-kimi`，确认失败且指向模型；② 把 `real-tools-kimi` 的模型改为非占位假名 `fixture-not-a-real-model`（前置检查先查模型、后查白名单，不改模型则走不到白名单检查），同时把 HTTP 白名单端口改为 18474，运行同一命令，确认失败且指向白名单；结果追加到 `specs/007-kimi-provider-onboarding/evidence/kimi-preflight.md`（依赖 T017、T018）
- [X] T020 [US2] 真实工具回填验收（**维护者执行**；依赖 T013 通过）：推荐一次运行 `-pl oryxos-boot -am -Dgroups=real-provider-kimi,real-provider-tools-kimi`（reactor 先跑 provider，失败即停）；预期 `Test evidence PASS: 2 required cases`，`us2-real/<runId>/kimi.json` 中 `toolHttpRequests=1`、`reasoningReplayed=true`、`finalReplyHasFacts=true`、`status=SUCCEEDED`；控制台输出以 `2>&1 | tee .oryxos-dev/evidence/kimi-real-<runId>.log` 保存；若为 `NO_TOOL_CALL` 等失败，如实记录，可手动重跑（新 runId），不改断言
- [X] T021 [US2] 在 `specs/007-kimi-provider-onboarding/evidence/kimi-real.md` 追加工具回填摘要（runId 及所依据的普通请求通过 runId、`modelCalls`、`toolHttpRequests`、`failureReason`、`finalReplyHasFacts`、`requestedModel`/`actualModels`、token、`durationMs`），再按 T014 的同一范围（证据目录、两个模块的 `target/surefire-reports`、保存的控制台日志、`specs`）做一次密钥字面值检索（依赖 T020）
- [X] T022 [US2] 拆分 spec 001 T042：在 `specs/001-single-node-agent-runtime/tasks.md` 第 350 行附近新增 `T042-K`（分组 `real-provider-tools-kimi`，依据 007 摘要且须引用一个 `T027-K` 通过 runId）与 `T042-D`，父项注明两子项都完成才可勾选，T044、T098–T100 前置仍指父项；仅当 T021 通过时勾选 `T042-K`；`verification.md` 分组表追加 `| real-provider-tools-kimi | RealKimiToolLoopIT | T042-K |`；`evidence/US2-real-tools.md` 末尾追加 007 摘要链接

**Checkpoint**: US1 + US2 可独立运行

---

## Phase 5: User Story 3 - 普通用户按文档在自己的工作区启用 Kimi (Priority: P3)

**Goal**: init 模板与用户示例提供注释状态 Kimi 块；文档可独立指导启用；从 CLI 入口本地验证（FR-009、FR-009a、FR-009b、FR-010、FR-012、FR-013）

**Independent Test**: `./tools/dev/mvnw -B -ntp -pl oryxos-boot -am test -Dgroups=kimi-onboarding` 全部通过

### Tests for User Story 3（先写，确认失败）

- [X] T023 [US3] 新建 `oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/KimiOnboardingLocalIT.java`（`@Tag("kimi-onboarding")`），用例：
  1. `initTemplateListsNoKimiProvider`：用 `oryxos-cli` 的 `templates/runtime.yaml` 经真实 init 流程初始化临时工作区，`oryxos provider list --json`（经 `RuntimeProcess.cli`）的 items 不含 `kimi`，且无不可用诊断；
  2. `commentedKimiBlocksAreIdentical`：分别从 `oryxos-cli/src/main/resources/templates/runtime.yaml`、`examples/runtime.example.yaml` 取出 Kimi 注释块并去掉每行 `# ` 前缀，与 `tools/dev/examples/real-provider-kimi/runtime.yaml` 的 `providers.kimi` 一起经 `RuntimeConfigLoader` 解析（环境注入夹具 `KIMI_API_KEY`），三者 `ProviderDefinition` 的 protocol/endpoint/completionsPath/apiKeyReference/connectTimeoutSeconds/options 全等且 `available()`；块内不得出现 `temperature`、`thinking`；
  3. `uncommentedKimiChatReachesKimiPathOnly`：init 后按文档取消注释、把 `https://api.moonshot.cn` 替换为回环假服务 `http://127.0.0.1:<port>`，默认 Agent 改为 `provider: {name: kimi, model: kimi-k3.1-pro}`，夹具密钥 `CANARY-kimi-fixture` 写入工作区 `secrets.local.yaml`（`KIMI_API_KEY: CANARY-kimi-fixture`，权限 0600；`RuntimeProcess` 会清空子进程环境变量，不能经环境注入），`RuntimeProcess.cli` 运行 `chat` 发送一条消息；假服务断言恰 1 次 `POST /v1/chat/completions`、`Authorization: Bearer CANARY-kimi-fixture`、请求体 `model=kimi-k3.1-pro`、`reasoning_effort=low`、无 `temperature`/`thinking` 键；
  4. `twoProvidersNeverCrossRoute`：同一工作区另配 `deepseek` 指向第二个回环假服务、第二个 Agent 引用它，各对话一次，每个假服务只收到自己 Agent 的请求，SQLite `llm_calls` 中实际 Provider 与配置一致（FR-013）；
  5. `misconfiguredKimiIsolatedAndRedacted`：从 `secrets.local.yaml` 删去 `KIMI_API_KEY`（保留 deepseek 夹具键）后 `provider list` 中 kimi `configured=false`，引用 deepseek 的 Agent 仍可对话；所有 stdout/stderr 与 `oryxos.db` 文本中 `CANARY-kimi-fixture` 出现 0 次（FR-012、SC-003）。
  运行 `-Dgroups=kimi-onboarding`，确认用例 2、3 因模板尚无 Kimi 块而失败（记录到 `evidence/baseline.md`）
- [X] T024 [US3] 在 `tools/dev/test-groups.json` 追加本地分组 `"kimi-onboarding": {"mode": "local", "tests": [{"module": "oryxos-boot", "class": "io.github.oryxlabs.oryxos.boot.KimiOnboardingLocalIT", "methods": ["initTemplateListsNoKimiProvider", "commentedKimiBlocksAreIdentical", "uncommentedKimiChatReachesKimiPathOnly", "twoProvidersNeverCrossRoute", "misconfiguredKimiIsolatedAndRedacted"]}]}`（依赖 T023）

### Implementation for User Story 3

- [X] T025 [P] [US3] 修改 `oryxos-cli/src/main/resources/templates/runtime.yaml`：保留生效的 `providers: {}`，在其后加入注释状态 Kimi 块（每行 `# ` 前缀，内容与 contracts §1 一致），块前说明行写明：中国站 `platform.kimi.com` 密钥；取消注释时把 `providers: {}` 改为 `providers:`；推荐模型 `kimi-k3.1-pro`（以控制台可见为准）；`reasoning_effort` 只用 `low`/`high`，不设置时为官方默认 `max` 可能超时；不要配置 temperature/thinking；密钥经环境变量或 600 权限 `secrets.local.yaml`
- [X] T026 [P] [US3] 修改 `examples/runtime.example.yaml`：在 `providers.example` 段之后加入同一注释状态 Kimi 块与说明行（取消注释时缩进到 `providers:` 下，与 `example` 同级）；不得改变任何生效行（`KernelFixture` 依赖 `https://model.example.invalid` 替换）
- [X] T027 [US3] 运行 `./tools/dev/mvnw -B -ntp -pl oryxos-boot -am test -Dgroups=kimi-onboarding` 全部 5 例通过；再运行 `-Dgroups=kernel-local` 确认 9 项内核测试仍通过（依赖 T024–T026）
- [X] T028 [P] [US3] 修改 `docs/CliGuide.md`：新增“启用 Kimi”小节——取消注释步骤、`KIMI_API_KEY` 环境变量或 `secrets.local.yaml`（`KIMI_API_KEY: <key>`，`chmod 600`）、Agent frontmatter `provider: {name: kimi, model: kimi-k3.1-pro}`（以控制台为准）、重启生效、`oryxos provider list` 只显示 `configured`/`NOT_PROBED` 不显示密钥、选项限制（low/high、勿配 temperature/thinking、medium 会被 Kimi 拒）、禁止把密钥写进 `-D` 参数/Agent 正文/仓库；同步第 62 行与第 90 行“真实联调前提/缺口”措辞为“Kimi 可单家验收（`real-provider-kimi`、`real-provider-tools-kimi`），DeepSeek 子项仍待凭证”
- [X] T029 [P] [US3] 修改 `examples/README.md` 第 43–45 行附近：说明可改用 `runtime.example.yaml` 中注释的 Kimi 块，并链接 `docs/CliGuide.md` 的“启用 Kimi”小节

**Checkpoint**: 三个故事均可独立验证

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T030 [P] 修改 `tools/dev/README.md` 真实普通请求一节（约第 112–140 行）之后：新增“仅 Kimi 的真实验收”小段，给出 `tools/dev/examples/real-provider-kimi` 复制命令、`read -rs` 注入方式、两个 Kimi 分组的合并运行命令、凭证转发规则（Kimi 分组只转发 `KIMI_API_KEY`）、证据路径与 007 摘要位置；原双家说明保持不变，仅追加
- [X] T031 运行 `./tools/dev/mvnw spotless:apply` 后 `./tools/dev/mvnw -B -ntp clean verify`：全部本地分组通过且用例数不少于 T001 基线 + 5（`kimi-onboarding`），质量门禁无新增违规、`config/quality/baseline.json` 不新增条目；运行 `python3 tools/dev/test_build_environment.py` 通过
- [X] T032 安全检查：以 Kimi 密钥前若干位（含维护者在对话中泄露过的那把）执行 `rg -c --hidden --glob '!.git' --glob '!node_modules' <前缀> .`，结果为空；检查 `git diff` 中无 `sk-` 开头的字面值
- [X] T033 更新 `specs/007-kimi-provider-onboarding/evidence/final-verification.md`：列出 T001、T008、T012、T019、T027、T031、T032 的命令与结果，T013/T020 真实验收的执行状态（通过 / 失败及 failureReason / 未执行），以及仍未完成的 spec 001 `T027-D`、`T042-D`
- [X] T034 运行 `/speckit-analyze` 检查 spec / plan / tasks 一致性，处理发现的问题后再宣告完成

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (T001)** → **Foundational (T002–T008)** → US1 / US2 / US3
- **例外**：T007 的运行需要 T011、T018 已登记新分组（`build.py` 拒绝未登记分组）；可先写完 T006/T007 代码，待 T011、T018 完成后再运行 T007 并勾选
- **US1（T009–T015）**：依赖 T002、T006；T013–T015 依赖维护者真实凭证
- **US2（T016–T022）**：依赖 T004、T009；T020 依赖 T013 通过（FR-003）
- **US3（T023–T029）**：只依赖 Setup，可与 US1/US2 并行（不触及真实验收类）；`test-groups.json` 的追加（T011、T018、T024）须串行编辑同一文件
- **Polish（T030–T034）**：依赖所需故事完成；T031 须在全部代码任务之后

### Within Each Story

- US3：T023 测试先写并确认失败 → T024 登记 → T025/T026 改模板 → T027 转绿
- US1/US2：支持类（Foundational）→ 薄测试类 → 分组登记 → 负向前置 → 真实运行 → 摘要 → spec 001 记账

### Parallel Opportunities

- T004 与 T002 并行（不同模块）；T006 与 T002/T004 并行
- T009（模板）与 T010（测试类）并行
- T025、T026、T028、T029 互相并行（不同文件）
- US3 整个阶段可与 US1 的 T009–T012 并行

---

## Parallel Example: User Story 3

```bash
# 测试登记完成后，模板与文档可同时修改：
Task: "T025 init 模板注释 Kimi 块 oryxos-cli/src/main/resources/templates/runtime.yaml"
Task: "T026 用户示例注释 Kimi 块 examples/runtime.example.yaml"
Task: "T028 docs/CliGuide.md 启用 Kimi 小节"
Task: "T029 examples/README.md 链接"
```

## Parallel Example: Foundational

```bash
Task: "T002 RealProviderAcceptance（oryxos-provider 测试）"
Task: "T004 RealToolLoopAcceptance（oryxos-boot 测试）"
Task: "T006 tools/dev/build.py 凭证映射"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. T001 基线 → T002、T003、T006 → T009–T012（本地可完成，证明入口与前置失败语义）
2. **STOP**：维护者准备新密钥（旧密钥已在对话中泄露，应作废）后执行 T013–T015
3. 拿到 `T027-K` 通过即 MVP 达成

### Incremental Delivery

1. MVP（US1）→ US2 工具回填（T016–T022）→ US3 用户体验（T023–T029）→ Polish
2. 无真实凭证时：T001–T012、T016–T019、T023–T034（除 T013/T014/T020/T021 及其勾选动作）均可完成；真实项在 `final-verification.md` 如实标“未执行”

### 完成判定

- 本地：T031 全绿、T032 无密钥。
- 真实：`kimi-real.md` 记录 US1、US2 通过 → spec 001 `T027-K`、`T042-K` 勾选；`T027-D`、`T042-D` 与父项保持未勾选。
