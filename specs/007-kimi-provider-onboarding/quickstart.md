# Quickstart: Kimi Provider 独立接入与真实验收

**Feature**: [spec.md](./spec.md) | 契约见 [contracts/](./contracts/)，结构见 [data-model.md](./data-model.md)

所有命令在仓库根目录、已 `export ORYXOS_JAVA_HOME=<JDK21>` 的终端执行；`ORYXOS_REPO=$(pwd)`。

## A 本地验证（无需 Kimi 账号，必跑）

```bash
./tools/dev/mvnw -B -ntp -pl oryxos-boot -am verify -Dgroups=kimi-onboarding
python3 tools/dev/test_build_environment.py
./tools/dev/mvnw -B -ntp clean verify
```

预期：

| 检查 | 预期结果 |
| --- | --- |
| `kimi-onboarding` | 全部用例通过：init 后 `provider list` 无 kimi；取消注释 + 回环端点后 CLI 对话一次，假服务收到 `POST /v1/chat/completions`、Bearer 夹具密钥、指定模型、`reasoning_effort: low`、无 temperature/thinking；双 Provider 不串用；输出不含夹具密钥；三处配置块解析一致 |
| `test_build_environment.py` | Kimi 分组只转发 `KIMI_API_KEY`；原双家分组仍转发两把；local 模式不转发 |
| `clean verify` | 全部既有本地分组通过、数量不减少（SC-006）；质量门禁无新增违规 |

## B Kimi 真实验收（维护者，需真实凭证）

前提：中国站 `platform.kimi.com` 有效密钥与余额；直连 `https://api.moonshot.cn`；本机 18473 端口空闲。

```bash
ORYXOS_KIMI_WS=$(mktemp -d "$ORYXOS_REPO/.oryxos-dev/real-provider-kimi.XXXXXX")
cp -R "$ORYXOS_REPO/tools/dev/examples/real-provider-kimi/." "$ORYXOS_KIMI_WS/"
# 编辑 agents/real-kimi/AGENT.md 与 agents/real-tools-kimi/AGENT.md 的 REPLACE_ 模型名（推荐 kimi-k3.1-pro，以控制台为准）
read -rs KIMI_API_KEY && export KIMI_API_KEY   # 不要写进 -D 参数、shell 历史或仓库文件
"$ORYXOS_REPO/tools/dev/mvnw" -B -ntp -pl oryxos-boot -am verify \
  -Doryxos.tests.mode=real -Dgroups=real-provider-kimi,real-provider-tools-kimi \
  -Doryxos.real.workspace="$ORYXOS_KIMI_WS" 2>&1 | tee "$ORYXOS_REPO/.oryxos-dev/evidence/kimi-real-$(date +%Y%m%d%H%M%S).log"
```

预期：`Test evidence PASS: 2 required cases; run <runId>`；`.oryxos-dev/evidence/us1-real/<runId>/kimi.json`、`us2-real/<runId>/kimi.json` 的 `status` 均为 `SUCCEEDED`，后者 `toolHttpRequests=1`、`reasoningReplayed=true`、`finalReplyHasFacts=true`。

负向检查（不发网络请求即失败）：`unset KIMI_API_KEY` 后重跑 → 指出 kimi 不可用；保留 `REPLACE_` 模型名 → 指出需配置模型。

双家入口语义不变：同一工作区运行 `-Dgroups=real-provider` → 以 `Missing runtime provider: deepseek` 失败。

## C 证据归档与 spec 001 勾选

1. 全文检索密钥字面值：`rg -c "<密钥前若干位>" .oryxos-dev/evidence oryxos-provider/target/surefire-reports oryxos-boot/target/surefire-reports specs` 结果为空（SC-003）。
2. 填写 `specs/007-kimi-provider-onboarding/evidence/kimi-real.md`（字段见 data-model §5）。
3. 勾选 spec 001 `T027-K`、`T042-K`；`T027`、`T042` 父项与 `T027-D`、`T042-D` 保持未勾选。

## D 普通用户启用 Kimi（文档路径，SC-005）

```bash
./tools/dev/oryxos --root /tmp/oryxos-kimi init
# 编辑 /tmp/oryxos-kimi/runtime.yaml：把 providers: {} 改为 providers:，取消 Kimi 注释块
# 编辑某个 Agent 的 frontmatter：provider: {name: kimi, model: kimi-k3.1-pro}
read -rs KIMI_API_KEY && export KIMI_API_KEY   # 或在 /tmp/oryxos-kimi/secrets.local.yaml 写 KIMI_API_KEY: <key> 并 chmod 600
./tools/dev/oryxos --root /tmp/oryxos-kimi provider list   # kimi: configured=true，connectivity=NOT_PROBED，不显示密钥
./tools/dev/oryxos --root /tmp/oryxos-kimi chat
```
