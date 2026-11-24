# Contract: Kimi 专属真实验收入口

**Feature**: [../spec.md](../spec.md) | 对应 FR-002、FR-003、FR-004、FR-006、FR-007、FR-008、FR-011

## 1 命令

```bash
# 推荐：一次运行两个 Kimi 分组；reactor 先跑 provider，失败即停，工具回填不会执行
"$ORYXOS_REPO/tools/dev/mvnw" -B -ntp -pl oryxos-boot -am verify \
  -Doryxos.tests.mode=real \
  -Dgroups=real-provider-kimi,real-provider-tools-kimi \
  -Doryxos.real.workspace="$ORYXOS_KIMI_WS"
```

也可单独运行 `-pl oryxos-provider -am ... -Dgroups=real-provider-kimi`（仅普通请求）。

## 2 输入

| 输入 | 来源 | 缺失时 |
| --- | --- | --- |
| `-Doryxos.tests.mode=real` | 命令行 | 失败：Enable real mode explicitly |
| `-Doryxos.real.workspace` 绝对路径 | 命令行 | 失败：Missing/relative workspace |
| `runtime.yaml` 含可用 `kimi` | 工作区 | 失败：Missing runtime provider: kimi / invalid configuration |
| `KIMI_API_KEY` | 终端环境或工作区 600 权限 `secrets.local.yaml` | 同上（kimi 不可用） |
| endpoint 主机 `api.moonshot.cn`、HTTPS、路径 `/v1/chat/completions` | 工作区 | 失败：Real acceptance cannot use a loopback/fake endpoint |
| `real-kimi` / `real-tools-kimi` 模型非 `REPLACE_` | 工作区 Agent | 失败：Configure an account-enabled model |
| 回环夹具 `http://127.0.0.1:18473` 在白名单、端口空闲 | 工作区 / 本机 | 失败（仅工具回填） |

DeepSeek 相关配置与凭证**不是**输入；存在与否不影响结果（FR-002）。全部前置检查在任何网络请求前完成（FR-004）。

## 3 判定

普通请求（`RealKimiProviderIT#kimiOrdinaryRequest`）通过当且仅当：一次调用、无自动重试；`actualProvider == kimi`；回复正文非空；无工具调用。

工具回填（`RealKimiToolLoopIT#kimiToolResultRoundTrip`）通过当且仅当：执行状态 `SUCCEEDED`；模型调用 ≥ 2；模型提出且仅提出 1 个 `http_get` 调用；回环夹具恰好被访问 1 次；后续请求中存在 `toolCallId` 与该调用 id 配对、内容含夹具事实的 TOOL 消息；带工具调用的回复含 provider opaque（reasoning）且在后续请求中原样回传；工具审计 SUCCESS 恰 1 条；最终 assistant 回复文本同时包含城市（`Beijing` 或 `北京`）与温度 `23`。

失败原因写入证据 `failureReason`（见 [data-model.md §4](../data-model.md)）。未调用工具 → `NO_TOOL_CALL`，不在同次运行内重跑（Clarifications Q5）。

## 4 输出

- 本机证据：`.oryxos-dev/evidence/us1-real/<runId>/kimi.json`、`.oryxos-dev/evidence/us2-real/<runId>/kimi.json`（字段见 data-model §4）；控制台输出 `.oryxos-dev/evidence/kimi-real-<runId>.log`（`2>&1 | tee` 保存）。
- 密钥检索范围（SC-003）：上述证据与日志、`oryxos-provider/target/surefire-reports`、`oryxos-boot/target/surefire-reports`、`specs/`。
- 构建门禁打印 `Test evidence PASS: N required cases; run <runId>`，N 为所选 Kimi 分组登记的方法数（1 或 2）。
- 维护者据此更新 `specs/007-kimi-provider-onboarding/evidence/kimi-real.md`，再勾选 spec 001 `T027-K` / `T042-K`。

## 5 凭证转发（`tools/dev/build.py`）

| 所选分组包含 | 转发 |
| --- | --- |
| `real-provider` 或 `real-provider-tools` | `DEEPSEEK_API_KEY`、`KIMI_API_KEY` |
| 仅 `real-provider-kimi` / `real-provider-tools-kimi` | 仅 `KIMI_API_KEY` |
| 其他 / local 模式 | 不转发任何模型凭证 |

## 6 不变量

- 原 `real-provider`、`real-provider-tools` 分组、类名、方法名、双家前置检查不变（FR-007）。
- 新旧入口共用同一断言实现（research R5），判定口径一致。
