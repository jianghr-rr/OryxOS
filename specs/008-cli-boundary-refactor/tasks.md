# Tasks: CLI 与会话层边界对齐（第 18 节）

**Input**: Design documents from `/specs/008-cli-boundary-refactor/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, quickstart.md

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

---

## Phase 1: Setup & Baseline Snapshot (S0)

- [x] T001 记录当前质量基线总数、前序 `us5-cli` 分组清单（15 项）与主代码零改动范围，写入 `specs/008-cli-boundary-refactor/evidence/baseline-snapshot.md`

---

## Phase 2: User Story 1 - 会话层验收 harness 补齐 (Priority: P1)

**Goal**: 按第 18 节课件“验收 harness”表落地 `SessionManagerTest` 与 `SessionRepositoryTest`，钉死身份隔离、多轮复用、唯一 ID 生成、手工建表存取、结构化消息回读完整与重启恢复。

**Independent Test**: `./tools/dev/mvnw -B -ntp -pl oryxos-core,oryxos-storage test -Dtest=SessionManagerTest,SessionRepositoryTest`

- [x] T002 [P] [US1] 在 `oryxos-core/src/test/java/io/github/oryxlabs/oryxos/core/session/SessionManagerTest.java` 新增 4 项测试（`@Tag("us5-cli")`，英文方法名 + `@DisplayName` 保留课件原文），覆盖同会话多轮复用、channel/user/Agent 任一不同拒绝、内部唯一 ID 生成、忙碌/归档边界
- [x] T003 [P] [US1] 在 `oryxos-storage/src/test/java/io/github/oryxlabs/oryxos/storage/SessionRepositoryTest.java` 新增 3 项测试（`@Tag("us5-cli")`，英文方法名 + `@DisplayName` 保留课件原文），覆盖手工迁移脚本建表存取、多轮结构化消息（含工具调用/结果）JSON 序列化回读完整、关闭并重建存储上下文模拟重启历史保留
- [x] T004 [US1] 运行 `oryxos-core` 与 `oryxos-storage` 定向测试验证 T002、T003 全部通过（红灯当场修）

---

## Phase 3: User Story 2 - 分组登记与 CLI 回归对齐 (Priority: P1)

**Goal**: 将新增 2 个测试类共 7 项登记进 `us5-cli` 分组，同步文档计数，确保 12 个 CLI 命令与前序全量测试零回归。

**Independent Test**: `./tools/dev/mvnw -B -ntp test -Dgroups=us5-cli`

- [x] T005 [US2] 更新 `tools/dev/test-groups.json`，在 `us5-cli` 分组追加 `SessionManagerTest`（4 项）与 `SessionRepositoryTest`（3 项），总数 15 → 22
- [x] T006 [P] [US2] 更新 `tools/dev/README.md` 与 `specs/001-single-node-agent-runtime/verification.md` 中的 `us5-cli` 分组说明与类清单
- [x] T007 [US2] 运行 `./tools/dev/mvnw -B -ntp test -Dgroups=us5-cli` 验证 22 项全部通过且 `test_report_gate.py` 零偏差

---

## Phase 4: User Story 3 - 课件修订建议文档 (Priority: P2)

**Goal**: 沉淀第 18 节课件与现行生产实现之间的全部保留偏差与测试映射，供后续维护与学员对照。

- [x] T008 [US3] 编写 `specs/008-cli-boundary-refactor/lesson-18-revision.md`，逐条覆盖 6 项保留偏差（含课件原写法、现行写法、真实代码文件定位与依据）及 2 个验收测试类映射表

---

## Phase 5: Polish & Full Gate Verification (S4)

- [x] T009 运行 `./tools/dev/mvnw -B -ntp clean verify` 全量构建、静态门禁（Spotless/Checkstyle/P3C/SpotBugs/FindSecBugs/PMD）与前序全部测试回归
- [x] T010 运行 `./tools/dev/quality-baseline --check-shrink origin/main` 验证质量基线未增加，并完成 H4 六条全局不变量自查
- [x] T011 将全量验证输出、存在性核对与 H4 自查结论写入 `specs/008-cli-boundary-refactor/evidence/final-verification.md`

---

## Dependencies & Execution Order

- **Phase 1 (T001)** → 完成后进入 Phase 2
- **Phase 2 (T002, T003 并行)** → **T004**
- **Phase 3 (T005 → T006, T007)** 依赖 Phase 2
- **Phase 4 (T008)** 可在 Phase 2 完成后推进
- **Phase 5 (T009 → T010 → T011)** 依赖全部前序任务完成
