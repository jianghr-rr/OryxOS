# Tasks: Tool 体系边界对齐与验收 harness（第 20 节）

**Input**: Design documents from `/specs/010-tool-boundary-refactor/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, quickstart.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- All test method names MUST be in English with `@DisplayName` preserving the lesson's original Chinese phrasing.

---

## Phase 1: Setup & Baseline Snapshot (S0)

- [x] T001 记录 `oryxos-tool` 离线依赖树核查结果（`httpclient5:5.6.5`、`mcp:0.18.3`、`spring-ai-model:1.1.8`、`json-schema-validator:1.5.9`、`slf4j-api:2.0.18`、`junit-jupiter:5.12.2`）、当前 `us4-tools` 分组基线（29 项）与质量基线现状（444 条），写入 `specs/010-tool-boundary-refactor/evidence/baseline-snapshot.md`

---

## Phase 2: User Story 1 - 统一工具契约完整性与三来源注册过滤 (Priority: P1) 🎯 MVP

**Goal**: 落地课件最值钱回归测试之一 `OryxToolContractTest`（`@ParameterizedTest @MethodSource("allRegisteredTools")` 遍历全部三来源注册工具验证 `name`/`description`/`inputSchemaJson` 非空合法 + 缺 schema 注册即拒），以及 `ToolRegistryTest`（三来源统一注册 + 按 `AgentProfile.tools` 不多不少精确过滤 + 重名拒绝）。

**Independent Test**: `ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home ./tools/dev/mvnw -B -o -pl oryxos-tool test -Dtest=OryxToolContractTest,ToolRegistryTest`

- [x] T002 [P] [US1] 在 `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/OryxToolContractTest.java` 新增参数化契约测试（`@Tag("us4-tools")`）：`@ParameterizedTest` + `@MethodSource("allRegisteredTools")`（`everyRegisteredTool_hasCompleteContract`，`@DisplayName("每个工具的契约三件套都不能缺")`）遍历注册表中全部三来源工具（7 个内置工具含 `notify` + `@Tool` 原生扩展 `echo` + `McpToolAdapter`），断言 `name`/`description`/`inputSchemaJson` 均非空非空白且通过离线 `ToolArgumentValidator` 校验；补充常规测试 `registry_rejectsToolWithMissingOrInvalidInputSchema` 断言漏写或非法 `inputSchemaJson` 在注册时立即抛出异常
- [x] T003 [P] [US1] 在 `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/ToolRegistryTest.java` 新增 3 项测试（`@Tag("us4-tools")`）：`registersAllThreeSourcesAsUnifiedOryxTool`（`BUILTIN`/`NATIVE`/`MCP` 均以 `OryxTool` 身份注册且来源标记准确）、`filtersByProfileToolsExactlyWithoutExtraOrMissing`（按 `AgentProfile.tools` 过滤后子集精确等于声明列表、不多不少，含空列表与含未知工具名边界）、`rejectsDuplicateToolNamesAtRegistration`（重复工具名注册抛 `OryxError`）
- [x] T004 [US1] 运行 `oryxos-tool` 定向测试验证 `OryxToolContractTest` 与 `ToolRegistryTest` 全部通过，并核查 Surefire XML 中 `@ParameterizedTest` 的 `<testcase name="...">` 精确格式（红灯当场修）

---

## Phase 3: User Story 2 - 内置文件、Shell 与 HTTP 工具正常执行与越界拦截 (Priority: P1)

**Goal**: 落地课件命名测试类 `FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`，钉死每类内置工具“白名单内正常跑通 + 白名单外越界拦截且零副作用”双保险。

**Independent Test**: `ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home ./tools/dev/mvnw -B -o -pl oryxos-tool test -Dtest=FileToolsTest,ShellToolsTest,HttpToolsTest`

- [x] T005 [P] [US2] 在 `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/file/FileToolsTest.java` 新增 2 项测试（`@Tag("us4-tools")`）：`fileTools_readWriteAndListWithinAllowedPaths`（`write_file`/`read_file`/`list_dir` 在允许目录内正常跑通）与 `fileTools_rejectOutOfBoundsPathsTraversalAndSymlinks`（`../` 路径穿越、未授权目录、受保护控制文件、指向工作区外的符号链接均在 IO 前被拦下且外部文件零改动）
- [x] T006 [P] [US2] 在 `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/shell/ShellToolsTest.java` 新增 2 项测试（`@Tag("us4-tools")`）：`shell_executesAllowedCommandAndTrustedScript`（允许命令 + 允许目录受控脚本正常返回 `SUCCESS` 与预期 stdout）与 `shell_rejectsDisallowedCommandAndInlineFlag`（白名单外命令、`-c` 内联参数与越界脚本路径均在启动进程前抛出 `OryxError`）
- [x] T007 [P] [US2] 在 `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/http/HttpToolsTest.java` 新增 3 项测试（`@Tag("us4-tools")`）：`httpGet_fetchesResponseFromAllowedTarget`（`@DisplayName("http_get 应能取回响应")`）、`httpGet_blocksDisallowedDomainWithoutNetworkIo`（`@DisplayName("http_get 命中白名单外域名应被拦下")`，含白名单外域名零请求与 302 重定向到白名单外域名被拦截）、`httpPost_sendsJsonToAllowedTargetAndBlocksDisallowedDomain`（`http_post` 白名单内发送 JSON 与白名单外拦截零网络 IO）
- [x] T008 [US2] 运行 `oryxos-tool` 定向测试验证 `FileToolsTest`、`ShellToolsTest`、`HttpToolsTest` 全部通过（红灯当场修）

---

## Phase 4: User Story 3 - MCP 失联启动隔离、脱敏 WARN 告警与工具适配 harness (Priority: P1)

**Goal**: 先立 `McpClientServiceTest` 与 `McpToolAdapterTest` 测试 harness（原样落地课件“某个 MCP server 失联，不能拖垮启动和其他工具”回归测试），再在 `McpClientManager` 启动失败/失联分支补齐脱敏 SLF4J WARN 日志，确保启动不炸、其余工具照常注册、敏感环境变量零泄露。

**Independent Test**: `ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home ./tools/dev/mvnw -B -o -pl oryxos-tool test -Dtest=McpClientServiceTest,McpToolAdapterTest,McpContractTest`

- [x] T009 [P] [US3] 在 `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/mcp/McpClientServiceTest.java` 新增 2 项测试（`@Tag("us4-tools")`）：`disconnectedMcpServer_doesNotBreakStartupOrOtherServers`（`@DisplayName("某个 MCP server 失联，不能拖垮启动和其他工具")`，坏 server `init-fail` 与好 server `normal` 并存时不抛异常、`ToolRegistry` 含好 server 工具且不含坏 server 工具、状态分别准确记录）与 `loadsMcpServersYamlAndResolvesEnvSecrets`（校验 `mcp_servers.yaml` 解析 `transport`/`command`/`args`/`env` `${ENV_VAR}` 引用、拒绝非法 transport 且 `Definition.toString()` 脱敏）
- [x] T010 [P] [US3] 在 `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/mcp/McpToolAdapterTest.java` 新增 2 项测试（`@Tag("us4-tools")`）：`adapter_mapsMetadataAndForwardsArgumentsToRemoteTool`（验证 `name`/`description`/`inputSchemaJson` 完整映射、`execute` 以远端原始工具名原样转发参数并返回 `ToolResult(SUCCESS)`）与 `adapter_rejectsUnauthorizedServerAndWrapsRemoteError`（验证 Agent 未声明 `mcp_servers` 时 `authorize` 抛 `OryxError(TARGET_DENIED)`，远端 `isError=true` 返回 `Outcome.ERROR` 且 `RetryClass.NONE`）
- [x] T011 [US3] 更新 `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/mcp/McpClientManager.java`：引入 `org.slf4j.Logger` / `LoggerFactory`，在构造器单 server 初始化/配置异常 catch 分支输出脱敏 `LOG.warn(...)`（仅记录安全清洗后的 server 标识与失败状态码 `MCP_INITIALIZATION_FAILED` / `MCP_CONFIGURATION_ERROR`，严禁记录环境变量值或未脱敏异常内容，零新增静态/安全门禁告警），保持所有公共方法签名不变
- [x] T012 [US3] 运行 `oryxos-tool` 全部单测验证 `McpClientServiceTest`、`McpToolAdapterTest`、既有 `McpContractTest` 及全部 7 个新测试类均绿（红灯当场修）

---

## Phase 5: User Story 4 - 分组登记与第 20 节课件修订建议文档 (Priority: P2)

**Goal**: 按 Surefire 实测方法名将新增 7 个测试类精确登记入 `us4-tools` 分组、同步验证文档计数，并沉淀第 20 节课件修订建议文档。

**Independent Test**: `ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home ./tools/dev/mvnw -B -o test -Dgroups=us4-tools`

- [x] T013 [US4] 更新 `tools/dev/test-groups.json`，在 `us4-tools` 分组追加 `OryxToolContractTest`、`ToolRegistryTest`、`FileToolsTest`、`ShellToolsTest`、`HttpToolsTest`、`McpClientServiceTest`、`McpToolAdapterTest` 的精确方法清单（含 `@ParameterizedTest` 实测 Surefire 用例名）
- [x] T014 [P] [US4] 更新 `tools/dev/README.md` 与 `specs/001-single-node-agent-runtime/verification.md` 中的 `us4-tools` 分组类清单与用例计数说明
- [x] T015 [P] [US4] 编写 `specs/010-tool-boundary-refactor/lesson-20-revision.md`，逐条覆盖 7 项保留偏差（含课件原写法、现行生产实现、真实代码路径与依据）及 7 个验收测试类与既有契约测试的完整映射表
- [x] T016 [US4] 运行 `./tools/dev/mvnw -B -o test -Dgroups=us4-tools` 验证全部分组用例通过且 `test_report_gate.py` 核对零偏差

---

## Phase 6: Polish & Full Gate Verification (S5)

- [x] T017 运行 `./tools/dev/mvnw -B -o spotless:check` 与 `./tools/dev/mvnw -B -o clean verify` 全量构建、静态门禁（Spotless/Checkstyle/P3C/SpotBugs/FindSecBugs/PMD）与前序第 16~19 节全部测试回归
- [x] T018 运行 `./tools/dev/quality-baseline --check-shrink origin/main` 验证质量基线未增加（<= 444 条），并完成 H4 六条全局不变量与第 20 节交付物存在性逐项自查
- [x] T019 将全量验证输出、7 个 harness 映射核对、交付物存在性核对、H4 自查结论与剩余人工项写入 `specs/010-tool-boundary-refactor/evidence/final-verification.md`

---

## Dependencies & Execution Order

- **Phase 1 (T001)** → 完成后可并行进入 Phase 2、Phase 3、Phase 4
- **Phase 2 (T002, T003 并行 → T004)** — MVP，验证契约三件套与注册表过滤
- **Phase 3 (T005, T006, T007 并行 → T008)** — 内置文件/Shell/HTTP 双保险测试
- **Phase 4 (T009, T010 并行 → T011 → T012)** — 先立 MCP 测试 harness，再补齐 `McpClientManager` 脱敏 WARN 日志并验证全绿
- **Phase 5 (T013 → T014, T015 并行 → T016)** — 依赖 T004、T008、T012 全部完成
- **Phase 6 (T017 → T018 → T019)** — 依赖全部前序任务完成

## Parallel Opportunities

- Phase 2 中 `T002`、`T003` 操作独立测试文件，可并行落地。
- Phase 3 中 `T005`、`T006`、`T007` 分属 `file`/`shell`/`http` 三个独立子包，可并行落地。
- Phase 4 中 `T009`、`T010` 分属独立测试文件，可并行落地，随后落地 `T011`。
- Phase 5 中 `T014`、`T015` 互不冲突，可在 `T013` 后并行完成。

## Implementation Strategy

- **MVP 范围**：Phase 1 + Phase 2（`OryxToolContractTest` + `ToolRegistryTest`），先钉死 `OryxTool` 契约三件套与 `ToolRegistry` 三来源注册/Profile 过滤地基。
- **增量交付**：Phase 3 固化内置工具安全边界，Phase 4 补齐 MCP 失联脱敏 WARN 告警与适配/隔离 harness，Phase 5 收口分组门禁与课件修订建议文档，Phase 6 全量门禁验绿。
