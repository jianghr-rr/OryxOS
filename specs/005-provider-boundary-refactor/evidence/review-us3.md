# US3 故事级复查（宪章 VII，T036）

| 检查项 | 依据 | 结论 | 证据 |
| --- | --- | --- | --- |
| 原 14 个方法名一个不少 | FR-008，US3 场景 1 | 通过 | `split-verification.md`：`comm` 无输出 |
| 断言未被削弱 | FR-008 | 通过 | 26 个方法体与 HEAD 逐字节一致。之后只有两处改动：`ProviderToolSchemaTest` 把 `ToolWireNameMapper` 调用换成等价的 `ToolNameMap.Entry`（US2），以及 `ProviderAuditTest`、`ProviderTestSupport` 中旧类名的替换 |
| 标签与登记一致 | FR-008 | 通过 | `us1-contract` 登记 20，provider+core 反应堆报告 18（boot 2 个被按模块过滤）；kernel 三组 6 个均来自 `ProviderTransportTest` |
| 失败审计专项测试 | P3–P5，US3 场景 2 | 通过 | `failedCallsAreAuditedWithClassifiedCodeBeforeRethrow`：503 与超时两种情况各有 STARTED 与 FAILED 两条记录，callId 一致，错误码与抛出的一致，审计中不含密钥或私有地址 |
| 不接入框架自动工具执行的结构守护 | P6，宪章 II，US3 场景 3 | 通过 | `ProviderArchitectureGuardTest`；破坏 ② 被拦截 |
| 三类破坏各自命中专项类 | SC-005，US3 场景 4 | 通过 | `breakage.md` |

偏差：

- T035 ② 的破坏方式从“只加 import”改为“加入引用该类型的字段”，原因见 `breakage.md`。
- T035 的还原方式从 `git checkout` 改为从备份复制，以保留尚未提交的重构。
- `ProviderTestSupport` 在 Foundational 之后新增了两个夹具扩展：`Receiver.onRequest` 钩子和带 `AuditSink` 参数的 `registry` 重载。原有方法没有变化。
