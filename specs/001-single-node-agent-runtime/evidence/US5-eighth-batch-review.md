# US5 第八批：真实场景前置审查

2026-11-13。**T098–T100 前提不足，三个真实场景均未执行，全部保持未勾选。**
维护者本轮明确回复“我暂时还没有”真实验收配置。本批完成前置审查、缺口记录和恢复步骤，
没有新增完成任务；仍为 **87/104 完成、17 未完成**，不进入 T101 及后续，不提交或推送。

## 已核对的事实

第七批 T096/T097 已完成；重新计算其发布摘要中示例/测试源文件的哈希，全部一致。
发行 JAR SHA-256 仍为 `a21dbfa552c75e094a49230c81e850a556d66da090667b9c7a2ff32d0db8032d`。
第七批 185 项/40 类、3 个自动事件、26 份执行记录属于已有本地证据，本批没有重新运行或转记为真实场景结果。

T098/T099/T100 共同依赖 T097、T042、T070；T042、T070 仍未完成，T042 上游 T027 同样缺真实前提。
科技日报当前示例还使用作者配置的 news MCP，须核对真实服务与 T071 支持证据。
默认 md 可以完成科技日报，Mem0/T057 是独立增强服务验收，不额外作为本场景阻塞理由。

配置清点仅限个人 checkout：查文件名及非秘密元数据，不扫描个人家目录、官方实验仓库、ACSP 或其他账户秘密。
当前环境没有 ORYXOS/DeepSeek/Kimi/Mem0/Webhook/新闻/天气/GitHub token 等匹配变量。
非测试候选只有两套 tools/dev 示例及 CLI 初始化模板，均未初始化为真实工作区，也没有本地 secret/MCP 配置。
另有 31 个留存测试/临时目录，未选作真实验收工作区；其中浏览器夹具仍指向回环端点，不以其代替实际服务。
用户随后确认尚未准备配置，不继续搜索其他目录或擅自选择账号、数据源、接收目标。

## 本次真实模式前检

沿用隔离入口，运行已有真实测试的严格前检；未修改测试、参数校验、模式门禁或本地/真实分组。

```bash
cd /Users/jianghaoran/github/OryxOS
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp test -pl oryxos-provider -am \
  -Doryxos.tests.mode=real -Dgroups=real-provider
./tools/dev/mvnw -B -ntp test -pl oryxos-boot -am \
  -Doryxos.tests.mode=real -Dgroups=real-provider-tools,real-notify,real-mcp \
  -Doryxos.real.notify.confirmSend=true -Doryxos.real.mcp.confirmCall=true
```

这些是本次**预期非零退出的前检复现命令**，不是准备完毕的场景启动命令。confirm 参数对应本轮已授权的测试范围，
不能替代具体工作区、目标和服务配置；检查在读取缺失参数处停止，没有发起动作。无需重复索取同一范围的授权。

| 分组/任务 | Run ID | 实际结果 |
| --- | --- | --- |
| real-provider / T027 | 271e27b1-92c5-445c-8d7e-1cd8753decb6 | 2 项失败：缺 oryxos.real.workspace；0 错误、0 跳过 |
| real-provider-tools / T042 | f9193416-dea2-408b-8df8-8151ecdc9c20 | 2 项失败：缺 oryxos.real.workspace；0 错误、0 跳过 |
| real-notify / T070 | 同上 | 1 项失败：缺 oryxos.real.notify.target；尚未进入工作区或通知客户端 |
| real-mcp / T071 | 同上 | 1 项失败：缺 oryxos.real.mcp.server；尚未校验入口或启动服务 |

两次 Maven 均退出 1；合计 6 项在前置断言失败，0 跳过。不是编译错误，不是“全部通过”。
真实模型请求、天气/新闻/GitHub 获取、可信 MCP 启动及真实通知均为 0：依据是前检位于客户端创建/进程启动之前，
不是对 Maven 自身依赖访问的网络抓包结论。这些失败不算 T098–T100 要求的业务失败演示。

[安全摘要](US5-eighth-batch-preflight.json)保留实际方法名、失败原因、run ID 和场景未执行状态。
本次 XML、构建日志与 notify/mcp 前检记录归档在 `.oryxos-dev/evidence/us5-batch8-preflight/`，
第七批完整成功 XML 仍在原 batch7-full-verify 归档，没有覆盖其历史报告。

## 三场景与恢复顺序

| 任务 | 当前缺口 | 后续必须取得的证据 |
| --- | --- | --- |
| [T098 天气](IT01-weather-real.md) | 私有工作区、真实模型前置、维护者选定城市/天气源、测试目标/ack/白名单 | 手动与真实 cron 各成功一次，来源/建议/查询1次/通知1次/接收/审计，以及失败演示 |
| [T099 科技日报](IT02-tech-real.md) | 同上共同前置，加真实新闻来源与可信 news MCP 配置 | 显式保存偏好、跨会话/Agent、按需参考、来源归属、两种触发及接收、来源失败 |
| [T100 GitHub 日报](IT03-github-real.md) | 共同前置、真实工作区绝对脚本路径和获准解释器；尚无真实数据/模型消费/接收 | 三栏目及项目来源、两种触发、脚本失败/拒绝、正文下次生效、接收与审计 |

补齐时只需提供工作区路径与所选城市/来源/服务/目标名称，秘密留在权限 600 的本地 secrets.local.yaml，不贴聊天或仓库。
模型需指定账户可用模型与协议配置；可信 MCP 需固定版本、入口哈希、真实参数；通知需明确 payload/ack 和实际接收端核查方式。
准备方式沿用 [examples/README](../../../examples/README.md)，两家模型前置见 [US1](US1-real-models.md)、[US2](US2-real-tools.md)，
通知与 MCP 见 [通知前提](US4-real-notify.md)、[MCP 前提](US4-configured-mcp.md)。
先完成依赖再进入三个完整场景。UNKNOWN 先人工核查接收情况，不以重跑代替核查；自动触发必须记录 SCHEDULED，不能以手动 /run 替代。

没有新增空的 real-weather/real-tech/real-github 分组，没有用本地夹具代跑，没有勾选 T098–T100。
T027/T028、T042/T044、T051/T057/T058、T070/T071/T072 原缺口全部保留；T073 保持第六批完成状态。
SC-005 要求的六次真实成功当前仍未取得，T101–T104 未进入。

## 一致性与改动保护

本轮基线记录 508 个 Git 可见既有文件；完成前核对无删除，生产代码、示例、测试、分组清单、契约、清单与历史证据保持不变。
仅更新任务/当前进度说明，新增三个真实场景未完成记录、本审查与安全摘要；不调整验收标准。
requirements 清单仍 16/16，104 个任务 ID 与 87/17 状态不变。源码未变，本批不重复执行第七批 185 项本地回归来冒充真实验收。
最终一致性结果见 `.oryxos-dev/evidence/us5-batch8-consistency.json`。git diff --check 通过，无暂存、提交或推送。
前置及结束前均核对 `.specify/extensions.yml` 不存在，无扩展钩子。没有留下本批运行服务。
