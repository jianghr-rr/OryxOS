# US2 真实工具回填：未完成

T042保持未勾选；T027、T028及依赖真实工具证据的T044也未勾选。2026-11-12仅运行真实模式前提检查，未获得真实模型结果。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp test \
  -Doryxos.tests.mode=real -Dgroups=real-provider-tools
```

实际：2个测试均因缺少`-Doryxos.real.workspace`失败，0跳过；日志`.oryxos-dev/us2-real-preflight.log`。失败发生于创建HTTP工具夹具或启动Provider之前。环境中的DEEPSEEK_API_KEY、KIMI_API_KEY均未设置；没有选择账号模型或私有工作区，不扫描个人秘密文件。

具体前提：

1. 两家有效密钥、额度，以及账号支持工具调用和reasoning_content的模型。密钥放环境变量或私有工作区`secrets.local.yaml`（权限600），不要写入仓库或`-D`参数。
2. 独立初始化的私有工作区，runtime.yaml包含两家HTTPS端点及正确选项；agents/real-tools-deepseek/AGENT.md、real-tools-kimi/AGENT.md指定实际模型与`tools: [http_get]`。示例中的REPLACE占位必须替换。
3. 可直连`api.deepseek.com`与`api.moonshot.cn`；允许本机工具访问`http://127.0.0.1:18473/weather`，18473端口空闲。该HTTP夹具只提供已知天气数据，不替代模型。
4. 先完成T027两家真实普通请求，再分别取得T042多轮工具证据。US1与US2使用不同Agent目录，不覆盖原有Agent。

准备和执行命令：

```bash
ORYXOS_REPO=/Users/jianghaoran/github/OryxOS
ORYXOS_REAL_WS=$(mktemp -d "$ORYXOS_REPO/.oryxos-dev/real-tools.XXXXXX")
"$ORYXOS_REPO/tools/dev/oryxos" --root "$ORYXOS_REAL_WS" init
cp -R "$ORYXOS_REPO/tools/dev/examples/real-provider-tools/." "$ORYXOS_REAL_WS/"
# 编辑该新工作区的两个AGENT.md，填写账号实际模型；配置私有密钥。
"$ORYXOS_REPO/tools/dev/mvnw" -B -ntp test \
  -Doryxos.tests.mode=real -Dgroups=real-provider-tools \
  -Doryxos.real.workspace="$ORYXOS_REAL_WS"
```

入口`RealProviderToolLoopIT`只使用真实ProviderRegistry，显式us2-real测试装配仍为临时会话/执行存储。每家要求≥2次模型请求、恰好1次已知天气工具HTTP请求、真实call ID/wire名配对及reasoning_content跨轮保留。模型不返回reasoning_content也不能直接关闭该验收，应选支持并启用此能力的配置后重验。

本地协议序列化测试已验证wire回传；真实测试在中立请求边界核对模型实际返回的私有数据及工具消息，结合下一次真实模型接受来验证。证据仅写模型标识、ID、计数及reasoning存在/回传布尔值，不写思维正文或密钥。
成功或执行失败的安全摘要位于`.oryxos-dev/evidence/us2-real/<runId>/`；前检失败没有伪造执行证据。当前该目录没有成功结果可作为T042验收依据。

## Kimi 专用验收（spec 007）

Kimi 已可通过专用分组 `real-provider-tools-kimi` 单独验收，对应 T042-K（须引用一个 T027-K 通过的 runId）；DeepSeek 仍走双入口，对应 T042-D。摘要与执行状态见 [007 Kimi 真实验收摘要](../../007-kimi-provider-onboarding/evidence/kimi-real.md)。
