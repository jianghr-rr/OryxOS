# Kimi 专用分组负向前置验证（T012、T019）

- 时间：2026-11-24
- 工作区：从 `tools/dev/examples/real-provider-kimi` 复制到 `.oryxos-dev/real-provider-kimi.XXXXXX`（本地，不入库）
- 所有命令均带 `-Doryxos.tests.mode=real -Doryxos.real.workspace=<ws>`，经 `./tools/dev/mvnw -B -ntp` 运行
- 未使用任何真实密钥；夹具值 `fixture-not-a-real-key` 仅存于当次命令环境，检索证据目录与两个模块的 surefire 报告命中 0 次
- 每条失败都发生在前置检查中（耗时 0.05–0.06 s），没有启动运行时，也没有发出模型或工具的网络请求

## T012 普通请求分组 `real-provider-kimi`

| 场景 | 结果 | 失败信息 |
| --- | --- | --- |
| ① 不设 `KIMI_API_KEY` | 1/1 失败 | `Missing/invalid provider configuration or secret reference: kimi` |
| ② `KIMI_API_KEY=fixture-not-a-real-key`，模型仍为 `REPLACE_` 占位 | 1/1 失败 | `Configure an account-enabled model in real-kimi/AGENT.md` |
| ③ 失败输出是否提及 deepseek | 两次构建日志中 `deepseek`（不区分大小写）命中 0 次 | — |

## T019 工具回填分组 `real-provider-tools-kimi`

| 场景 | 结果 | 失败信息 |
| --- | --- | --- |
| ① 夹具密钥，`real-tools-kimi` 模型保留 `REPLACE_` | 1/1 失败 | `Configure account-enabled tool/reasoning models` |
| ② 模型改为 `fixture-not-a-real-model`，HTTP 白名单端口改为 18474 | 1/1 错误 | `HTTP target is not authorized by scheme, host and port` |

两次构建日志中 `deepseek` 均命中 0 次。Kimi 专用分组不读取、不要求 DeepSeek 配置。
