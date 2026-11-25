# Tasks: Notify 通知模块边界对齐与验收 harness（第 19 节）

**Input**: Design documents from `/specs/009-notify-boundary-refactor/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, quickstart.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

---

## Phase 1: Setup & Baseline Snapshot (S0)

- [x] T001 记录 `oryxos-tool` 离线依赖树核查结果、当前 `us4-tools` 分组清单（21 项）与质量基线现状，写入 `specs/009-notify-boundary-refactor/evidence/baseline-snapshot.md`

---

## Phase 2: User Story 1 - 渠道中立抽象与通用 Webhook 适配器 harness (Priority: P1)

**Goal**: 落地“先定接口、后挂实现”的渠道中立抽象（`NotifyTarget`、`NotifyChannelAdapter`），让 `WebhookNotifyAdapter` 实现该接口并保持既有重载兼容，同时落地 `WebhookNotifyAdapterTest`（4 项）钉死动态 URL、正文安全编码、5xx/拒绝不静默不重试与断连返回 UNKNOWN。

**Independent Test**: `./tools/dev/mvnw -B -ntp -pl oryxos-tool test -Dtest=WebhookNotifyAdapterTest,NotifyContractTest`

- [x] T002 [P] [US1] 在 `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyTarget.java` 新增渠道中立记录 `NotifyTarget(String channelType, Map<String, Object> config)`（防御性拷贝且 `toString()` 屏蔽 `config` 防止泄露）
- [x] T003 [P] [US1] 在 `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyChannelAdapter.java` 新增出站通知抽象接口（`authorize(NotifyTarget)` 与 `send(NotifyTarget, String, ExecutionContext)`，注明 24 节 Sandbox 对接点）
- [x] T004 [US1] 更新 `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotificationTargetResolver.java`，为内部 `Target` 新增 `toNotifyTarget()` 转换为 `NotifyTarget("webhook", ...)`，不破坏既有构造与 `resolve` 契约
- [x] T005 [US1] 更新 `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapter.java`，使其 `implements NotifyChannelAdapter`，支持从 `NotifyTarget.config()` 读取 `url`/可选 `headers`/`payload`/`acknowledgement`，并保留既有 `NotificationTargetResolver.Target` 重载方法委托至新接口以保证调用方零改动兼容
- [x] T006 [US1] 在 `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/WebhookNotifyAdapterTest.java` 新增 4 项测试（`@Tag("us4-tools")`，英文方法名 + `@DisplayName` 保留课件原文），基于本地 `HttpServer` 覆盖动态 URL + 正文编码、5xx 不静默不重试、业务 ack 拒绝、断连返回 `UNKNOWN` 不重试
- [x] T007 [US1] 运行 `oryxos-tool` 定向测试验证 `WebhookNotifyAdapterTest` 与既有 `NotifyContractTest` 全部通过（红灯当场修）

---

## Phase 3: User Story 2 - 通知工具接口解耦与安全前置校验 harness (Priority: P1)

**Goal**: 让 `NotifyTool` 依赖 `NotifyChannelAdapter` 接口而非具体类，落地 `NotifyToolsTest`（4 项）钉死未配置报错、缺省取默认渠道、`authorize` 严格先于 `send`（顺序断言）、以及经由 `ToolExecutor` 执行时的审计与渠道可替换性。

**Independent Test**: `./tools/dev/mvnw -B -ntp -pl oryxos-tool test -Dtest=NotifyToolsTest,WebhookNotifyAdapterTest,NotifyContractTest`

- [x] T008 [US2] 更新 `oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/notify/NotifyTool.java`，将字段与构造器参数从 `WebhookNotifyAdapter` 改为接口 `NotifyChannelAdapter`，通过 `targets.resolve(...).toNotifyTarget()` 调用 `adapter.authorize` 与 `adapter.send`
- [x] T009 [US2] 在 `oryxos-tool/src/test/java/io/github/oryxlabs/oryxos/tool/notify/NotifyToolsTest.java` 新增 4 项测试（`@Tag("us4-tools")`，英文方法名 + `@DisplayName` 保留课件原文），覆盖 `notify_channels` 未配置报错、`targetName` 缺省取默认渠道、`authorize` 严格先于 `send` 顺序断言（授权失败阻断发送）、以及自定义非 webhook 适配器 + `ToolExecutor` 审计验证
- [x] T010 [US2] 运行 `oryxos-tool` 全部单测验证 T008、T009 及既有工具契约测试全部通过（红灯当场修）

---

## Phase 4: User Story 3 - 分组登记与课件修订建议文档 (Priority: P2)

**Goal**: 将新增 2 个测试类共 8 项登记入 `us4-tools` 分组并同步文档计数，同时沉淀第 19 节课件修订建议文档。

**Independent Test**: `./tools/dev/mvnw -B -ntp test -Dgroups=us4-tools`

- [x] T011 [US3] 更新 `tools/dev/test-groups.json`，在 `us4-tools` 分组追加 `WebhookNotifyAdapterTest`（4 项）与 `NotifyToolsTest`（4 项），总数 21 → 29
- [x] T012 [P] [US3] 更新 `tools/dev/README.md` 与 `specs/001-single-node-agent-runtime/verification.md` 中的 `us4-tools` 分组说明与类清单
- [x] T013 [P] [US3] 编写 `specs/009-notify-boundary-refactor/lesson-19-revision.md`，逐条覆盖 5 项保留偏差（含课件原写法、现行写法、真实代码文件定位与依据）及 2 个验收测试类映射表
- [x] T014 [US3] 运行 `./tools/dev/mvnw -B -ntp test -Dgroups=us4-tools` 验证 29 项全部通过且 `test_report_gate.py` 零偏差

---

## Phase 5: Polish & Full Gate Verification (S5)

- [x] T015 运行 `./tools/dev/mvnw -B -ntp clean verify` 全量构建、静态门禁（Spotless/Checkstyle/P3C/SpotBugs/FindSecBugs/PMD）与前序全部测试回归
- [x] T016 运行 `./tools/dev/quality-baseline --check-shrink origin/main` 验证质量基线未增加，并完成 H4 六条全局不变量自查
- [x] T017 将全量验证输出、交付物存在性核对与 H4 自查结论写入 `specs/009-notify-boundary-refactor/evidence/final-verification.md`

---

## Dependencies & Execution Order

- **Phase 1 (T001)** → 完成后进入 Phase 2
- **Phase 2 (T002, T003 并行 → T004 → T005 → T006 → T007)**
- **Phase 3 (T008 → T009 → T010)** 依赖 T002~T005
- **Phase 4 (T011 → T012, T013 并行 → T014)** 依赖 Phase 2、Phase 3
- **Phase 5 (T015 → T016 → T017)** 依赖全部前序任务完成
