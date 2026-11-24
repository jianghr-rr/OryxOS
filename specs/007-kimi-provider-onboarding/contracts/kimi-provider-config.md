# Contract: Kimi Provider 配置块

**Feature**: [../spec.md](../spec.md) | 对应 FR-001、FR-005、FR-009、FR-009a、FR-009b、FR-010、FR-012

## 1 规范配置块（取消注释后的生效形态）

```yaml
providers:
  kimi:
    protocol: openai_chat_completions
    endpoint: https://api.moonshot.cn
    completions_path: /v1/chat/completions
    api_key: "${KIMI_API_KEY}"
    connect_timeout_seconds: 5
    options:
      reasoning_effort: low
```

约束：

- 字段与值必须与上表逐字一致地出现在三处：`examples/runtime.example.yaml`（注释）、`oryxos-cli/src/main/resources/templates/runtime.yaml`（注释）、`tools/dev/examples/real-provider-kimi/runtime.yaml`（生效）。本地测试校验三处取消注释后解析结果相同。
- `api_key` 只能是 `${KIMI_API_KEY}` 引用；任何文件不得出现密钥字面值。
- 不得出现 `temperature`、`thinking`。

## 2 注释块形态（init 模板与用户示例）

- 每行以 `# ` 前缀注释；块前有一行说明：中国站 `platform.kimi.com` 密钥、推荐模型 `kimi-k3.1-pro`（以控制台可见为准）、只接受 `low`/`high`、不设置时为 `max` 可能超时、不要配置 temperature/thinking。
- 取消注释规则：删除块内每行开头的 `# `，并把它放到 `providers:` 下（init 模板中需将 `providers: {}` 改为 `providers:`）。文档必须写明这一步。
- `init` 生成后 `oryxos provider list` 不出现 `kimi`、不产生不可用诊断；对已存在的 `runtime.yaml` 不覆盖（沿用 `WorkspaceInitializer.createMissing`）。

## 3 Agent 引用

```yaml
provider: {name: kimi, model: kimi-k3.1-pro}
```

`model` 为推荐值；维护者应以 Kimi 控制台实际可见的模型为准。真实验收模板保持 `REPLACE_` 占位。

## 4 失败语义（沿用现有，不新增）

| 情况 | 结果 |
| --- | --- |
| `KIMI_API_KEY` 缺失 / secrets 文件权限不符 | `kimi` 为 `UNAVAILABLE`；只影响引用它的 Agent；诊断不含秘密 |
| 端点/路径格式非法 | 同上 |
| `reasoning_effort: medium` | 本地校验通过；Kimi 拒收 → 真实请求失败，记录安全错误码，不自动修正 |
| `thinking`（K3 系列） | 同上 |
