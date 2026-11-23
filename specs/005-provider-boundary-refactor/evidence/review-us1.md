# US1 故事级复查（宪章 VII，T024）

范围：T017–T022 的 diff（`ProviderRequest`、`PromptBuilder`、`AuditedProvider`、`SpringAiProvider`、`ProviderRegistry`）。行为证据见 `red-green.md`。

| 检查项 | 依据 | 结论 | 证据 |
| --- | --- | --- | --- |
| 准入只剩 `ReActLoop` 一处 | FR-001，P1 | 通过 | `rg -n dispatcher oryxos-provider/src/main/java oryxos-core/.../core/provider` 无输出；`ReActLoop` 第 58 行未改；`modelCallHoldsExactlyOneAdmissionWhileInFlight` 实测 1（重构前 3） |
| 请求对象不携带准入能力 | data-model §1，P2 | 通过 | `ProviderRequest` 只剩 9 个组件，`withDispatcher` 与 10 参数构造器已删除 |
| 准入后停止不误记失败 | FR-002、FR-003 | 通过 | `stopAfterAdmission…`：请求到达 1 次，同一 callId 终态 SUCCEEDED，无 `EXECUTION_STOPPED/PAUSED/DEADLINE` 记录（重构前请求 0 次） |
| validate 只在入口执行一次 | FR-004，P8 | 通过 | `rg -n "validate\("` 只命中 `ProviderRegistry`；`SpringAiProvider.call` 只保留路由一致性的防御检查 |
| 非法请求零审计、零网络 | FR-004，P8 | 通过 | `invalidRequestsNeverWriteAuditOrReachNetwork` 重构前后均为绿 |
| `SpringAiProvider` 是唯一的原始异常映射点 | FR-005，P7 | 通过 | `ProviderErrorMapper.failure` 只在 `SpringAiProvider` 中调用；`AuditedProvider` 对已分类的 `OryxError` 原样透传，非 `OryxError` 兜底为 `PROVIDER_PROTOCOL`，与映射器的默认分支一致 |
| 错误码集合未增减 | contracts §3 | 通过 | diff 中没有新的错误码字符串；`ProviderAuditTest`、`ProviderProtocolTest` 保持绿 |
| 计时段名不变 | contracts §4 | 通过 | `provider.transport`、`provider.reply` 保留；`react.provider` 在 `ReActLoop` 中未动；`kernel-provider-timing` 3/3 通过 |

偏差与未覆盖：

- `PromptBuilder` 的 diff 包含 spotless 对链式调用缩进的重排（因为去掉了 `.withDispatcher` 的尾调用），语义不变。
- `SpringAiProvider` 去掉 `call()` 内的重复校验后，单独直接调用它（绕过 `ProviderRegistry`）不再做输入校验。它是包内类，生产代码只通过 `ProviderRegistry` 构造和调用。这一点符合 plan 的“入口一次校验”。
