# 故意破坏验证（SC-005，T035）

每次只改一个文件后运行 `./tools/dev/mvnw -B -ntp -pl oryxos-provider -am test -Dgroups=us1-contract`。还原方式与任务原文不同：
工作区里本次重构尚未提交，`git checkout -- <file>` 会把重构一起回滚。所以改为先备份到 `/tmp/brk/`、破坏后复制回来，
并用 `cmp` 逐字节确认三个文件已复原。

| # | 破坏 | 对应专项类 | 结果 | 失败信息（摘录） | 连带失败 |
| --- | --- | --- | --- | --- | --- |
| ① | `ProviderService` 查表改为 `providers.values().iterator().next()` | `ProviderRoutingTest` | 3/3 失败 ✓ | `expected: <PROVIDER_UNAVAILABLE> but was: <CONFIGURATION_ERROR>`，适配层报 `provider routing mismatch` | `ProviderSmokeTest`（同样报 `provider routing mismatch`，在预期之内） |
| ② | `SpringAiProvider` 增加字段 `org.springframework.ai.chat.model.ChatModel chatModel` | `ProviderArchitectureGuardTest` | 1/1 失败 ✓ | `Provider 不得接入框架自动工具执行（宪章 II）；只允许 org.springframework.ai.openai.api.，命中位置：[SpringAiProvider.java:13 org.springframework.ai.chat.model.ChatModel, SpringAiProvider.java:13 ChatModel]` | 无（类注释中的 `ChatModel` 字样没有误报） |
| ③ | `AuditedProvider` 失败分支删除 FAILED 写入 | `ProviderAuditTest` | `failedCallsAreAuditedWithClassifiedCodeBeforeRethrow` 失败 ✓ | `each failed call writes STARTED then FAILED ==> expected: <4> but was: <2>` | 无 |

关于 ②：任务原文的破坏方式是“只加一行 `import ...ChatModel;`”。实测这一步在 `spotless:check` 阶段就因“未使用导入”使构建失败，
测试没有运行，所以证明不了守护测试有效（见 `/tmp/brk/b2.log` 第一次运行）。改成实际引用该类型的字段后，格式检查通过，
由守护测试拦截。两道门禁叠加：仅有导入会被格式检查拦下，实际使用会被守护测试拦下。
