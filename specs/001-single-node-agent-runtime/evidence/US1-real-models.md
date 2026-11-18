# US1 真实模型联调：前提未满足

日期：2026-11-12。**T027未完成；T028不得关闭，US1整体验收未通过。**

已交付`RealProviderIT`和`real-provider`分组，各供应商一个普通请求用例。
两家均先检查配置/Agent/密钥引用及真实HTTPS目标；没有skip、假响应、自动fallback或自动重试。
默认local构建排除此组；必须同时指定real模式与real-provider组。

## 实测前检

当前进程`DEEPSEEK_API_KEY`、`KIMI_API_KEY`均未设置（仅检查是否存在，未读出或打印秘密），
默认项目工作区未配置，也没有本次专用`.oryxos-dev/real-provider-workspace/runtime.yaml`。
没有在其他项目、全局账户或官方实验仓库搜索密钥。

实际运行：

```bash
ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home \
tools/dev/mvnw -B -ntp -pl oryxos-provider -am test \
  -Doryxos.tests.mode=real -Dgroups=real-provider \
  -Doryxos.real.workspace=/Users/jianghaoran/github/OryxOS/.oryxos-dev/real-provider-workspace
```

退出1，2项前提断言失败、0错误、0跳过，原因`Missing real workspace runtime.yaml`。
runId=`2162a0cb-c3cc-435d-aa99-e9dcc0a28794`；日志`.oryxos-dev/us1-real-preflight.log`，
安全摘要`.oryxos-dev/evidence/us1-real-preflight.json`。
前检在创建ProviderRegistry/发送请求之前终止，因此本次真实模型请求为0；无真实响应、模型版本或用量结果可报告。
这证明缺前提会失败，不证明外部模型连通。

## 待提供的具体前提

| 项目 | 必需配置 |
| --- | --- |
| DeepSeek | 有效密钥/额度、账号可用model；`https://api.deepseek.com` + `/chat/completions`，thinking.type=disabled |
| Kimi | 有效密钥/额度、账号可用model；`https://api.moonshot.cn` + `/v1/chat/completions`，reasoning_effort=low，不传thinking/temperature |
| 工作区 | runtime.yaml显式注册两个名字；agents/real-deepseek/AGENT.md和agents/real-kimi/AGENT.md使用对应Provider及实际model，tools=[] |
| 秘密注入 | 当前终端DEEPSEEK_API_KEY/KIMI_API_KEY，或工作区secrets.local.yaml同名项且权限0600；不写到-D参数/示例/提交文件 |
| 网络 | 到上述两个配置目标的直接HTTPS访问；本适配器不读取系统代理，不跟随重定向 |

不含秘密的模板与完整命令见[tools/dev说明](../../../tools/dev/README.md#us1-provider-验证)。
模板中的REPLACE_WITH_ACCOUNT_ENABLED模型是必须替换的配置占位，不是有效模型示例。
补齐后重跑同一入口，核对两家真实来源、内容、记录与实际模型；新证据自动输出到
`.oryxos-dev/evidence/us1-real/<runId>/deepseek.json`及`kimi.json`。
失败尝试也写安全结果（通过前检后才生成），不记录opaque或密钥。
需要两家真实成功才可勾选T027并完成T028；T042的工具回填和SC-002完整验收仍属于US2及后续。
