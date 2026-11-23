# US2 故事级复查（宪章 VII，T030）

| 检查项 | 依据 | 结论 | 证据 |
| --- | --- | --- | --- |
| 入口按课件和技术方案命名 | FR-006，US2 场景 1 | 通过 | `ProviderService.java` 由 `git mv` 得到，保留历史；只改了这一个类名 |
| 分层类名未被连带改名 | US2 场景 2 | 通过 | `AuditedProvider`、`SpringAiProvider`、`OpenAiClientFactory` 均保持原名 |
| 死代码删除 | FR-007，US2 场景 3 | 通过 | `ToolWireNameMapper.java` 已删除；主代码中没有它的引用，测试改用 `ToolNameMap` |
| 历史证据未改写 | plan 约束 | 通过 | `git status` 中 `specs/001`~`004` 没有变化 |
| 装配不变 | FR-010 | 通过 | boot 的 `us1-contract`、`f0-foundation` 共 32 个必跑用例通过（见 `rename-verification.md`） |

SC-004：逐行核对 spec 的“课件原则与当前代码对照”表（9 行），全部能定位到重构后的类。

| 课件要求 | 重构后位置 |
| --- | --- |
| 职责划窄 | `SpringAiProvider.call`（一次低层请求） |
| 坑一 显式映射 | `ProviderService` 构造器按 `runtime.yaml` 建表；`ProviderRoutingTest` |
| 坑二 关闭自动工具执行 | 只依赖 `OpenAiApi`；US3 新增 `ProviderArchitectureGuardTest` |
| 坑三 依赖可解析 | 父 POM 中的 Spring AI BOM 1.1.8 |
| 成功与失败都审计 | `AuditedProvider`；`ProviderAuditTest` |
| 核心类名 `ProviderService` | `oryxos-provider/.../ProviderService.java` |
| 一次调用一条路径 | `ReActLoop` 第 58 行唯一准入，`ProviderService.validate` 唯一校验；`ProviderAdmissionTest` |
| 测试按验收点组织 | `ProviderRoutingTest`、`ProviderToolSchemaTest`、`ProviderAuditTest`、`ProviderProtocolTest`、`ProviderTransportTest`、`ProviderAdmissionTest` |
| 只翻译不执行 / 死代码 | 翻译在 `ProviderOptionsMapper`；`ToolWireNameMapper` 已删除 |
