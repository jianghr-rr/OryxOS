# US4 真实通知前提与验收记录

**T070已完成；T072仍未完成（待 T071）。** 本地回环Webhook结果只证明适配和失败处理，不证明任何真实接收方收到消息。
本批已完成真实测试 Webhook 联调与接收端人工核对；敏感 Webhook URL 与 API Key 仅存放在 `.oryxos-dev/real-tools-workspace/secrets.local.yaml`（`0600`），未写入仓库文件。

`RealNotificationIT`已编译、登记`real-notify`，只调用实际NotifyTool与统一ToolExecutor，写结构化日志。
这是独立工具联调入口，不需要模型响应，不代表生产chat已可用。无模拟fallback、无skip、无自动重试。

## 运行前必须具备

1. 本个人项目下已init的独立私有工作区；`runtime.yaml`、有效Agent定义，以及该Agent的`tools: [notify]`。
   现有Agent解析仍需可用的Provider配置；本工具入口不会调用Provider。
2. 维护者明确选定的测试接收目标名称、实际Webhook URL、接收方要求的payload和ack：
   `http_2xx`或`json_equals`（明确pointer与expected）。Agent的`notify_channels`必须声明该名称。
3. runtime的`tools.http.allowed_targets`显式允许实际scheme/host/port；可访问目标的网络。
4. URL与可选headers的secret引用写入私有`secrets.local.yaml`并`chmod 600`。
   此real入口只读取该文件，构建不转发任意父环境秘密；不得把秘密写在`-D`参数中。
5. 明确允许向这个测试目标发送一条带`ORYXOS-US4-<UUID>`标记的消息，并能在接收端核对该标记。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
ORYXOS_REPO=/Users/jianghaoran/github/OryxOS
ORYXOS_REAL_WS="$ORYXOS_REPO/.oryxos-dev/real-tools-workspace"
# 先init该私有目录，按上述条件配置Agent、HTTP规则、秘密与目标；下列名称替换为已配置项。
"$ORYXOS_REPO/tools/dev/mvnw" -B -ntp -pl oryxos-boot -am test \
  -Doryxos.tests.mode=real -Dgroups=real-notify \
  -Doryxos.real.workspace="$ORYXOS_REAL_WS" -Doryxos.real.agent=real-tools \
  -Doryxos.real.notify.target=explicit-test-target -Doryxos.real.notify.confirmSend=true
```

成功自动证据为`ACKNOWLEDGED_REVIEW_PENDING`，保存标记、执行ID、调用ID、attempt与outcome；
不保存Webhook URL/header。位置`.oryxos-dev/evidence/us4-real/<runId>/notify.json`。
维护者核对接收端确实出现该标记后记录人工结果，才能关闭T070；这仍不代表最终用户已读。
若UNKNOWN，不直接重跑，先核查实际接收情况，避免人工造成重复投递。

本次严格前检命令未提供配置及confirmSend/confirmCall：
`./tools/dev/mvnw -B -ntp -pl oryxos-boot -am test -Doryxos.tests.mode=real -Dgroups=real-notify,real-mcp`。
runId `29c49352-3f0b-46ae-b8d0-a764dcf08eb1`，两项均在明确确认参数缺失处失败，0跳过、0通知发送、0真实MCP启动。
日志`.oryxos-dev/us4-real-preflight.log`，XML另存`.oryxos-dev/evidence/us4-real-preflight/`。

## T070 真实联调与人工核对结果（2026-11-18）

- **私有工作区**：`.oryxos-dev/real-tools-workspace`（`runtime.yaml` 白名单限制为 `https://open.feishu.cn:443/open-apis/bot/v2/hook/`；`agents/real-tools/AGENT.md` 配置 `explicit-test-target` 与 `/code == 0` JSON 确认；敏感凭证置于 `0600` 的 `secrets.local.yaml`）。
- **执行命令**：
  `./tools/dev/mvnw -B -ntp -pl oryxos-boot -am test -Doryxos.tests.mode=real -Dgroups=real-notify -Doryxos.real.workspace="/Users/jianghaoran/github/OryxOS/.oryxos-dev/real-tools-workspace" -Doryxos.real.agent=real-tools -Doryxos.real.notify.target=explicit-test-target -Doryxos.real.notify.confirmSend=true`
- **自动化结果**：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`（runId `de09701a-a086-4a91-a0c3-f9a2b27204b8`，证据 `.oryxos-dev/evidence/us4-real/de09701a-a086-4a91-a0c3-f9a2b27204b8/notify.json`）。
  - `marker`: `ORYXOS-US4-a2484c3f-ee36-425f-ad5f-525d8fd96307`
  - `executionId`: `ee0cf85d-1201-4aea-bbcb-847eae1d1784`
  - `toolCallId`: `d47db6d3-4d05-4ace-a4cc-7af3dc44f564`
  - `outcome`: `SUCCESS`，`attempts`: `1`，`status`: `ACKNOWLEDGED_REVIEW_PENDING`
- **人工核对**：维护者于 2026-11-18 确认测试接收群已实际收到上述 `ORYXOS-US4-a2484c3f-ee36-425f-ad5f-525d8fd96307` 消息；据此勾选关闭 `T070`。
