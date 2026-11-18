# US5 第七批：三个示例与可控全链路

2026-11-13。范围 T096–T097；未进入 T098–T104，未提交或推送。

**T096、T097 已完成。** 本地定向 `kernel-local` 9 项通过；正式发行 JAR 下最终全量 185 项/40 类通过，
零失败、错误、跳过。104 项任务现为 87 完成、17 未完成。
本批使用真实发行入口、SQLite、MCP stdio 子进程、Python 脚本及回环 Webhook。
模型和业务数据源是确定性测试夹具，接收端是本机测试服务器，不代表真实模型推理质量或实际通知送达。

## 交付与边界

- T096：`examples/agents/` 的 daily-weather、daily-tech-digest、github-daily；独立组稿参考、可信 Python 脚本、runtime/MCP 模板及配置说明。业务留在这些文件，没有写入 ReAct 或其他运行内核。
- T097：`KernelLocalIT`、`KernelFixture`、`kernel/scenarios.json`、本地 MCP 夹具；登记 `kernel-local`，纳入默认 local 必跑清单。
- 所有示例 cron 默认关闭，Provider/model、城市/查询来源、测试通知目标、白名单、Python 绝对路径和新闻 MCP 配置由作者明确设置。
- 默认记忆为 md，Session/Execution/Audit 为正式 SQLite；没有内存端口替身、跳过测试或放宽运行权限。
- GitHub 三栏目为今日/本月新建仓库及本月更新的 AI topic 仓库的总星数搜索快照；不是 GitHub Trending 官方榜或星数增量。保留查询和项目链接；不完整搜索响应直接失败。

## 测试准备与修复记录

1. 先登记并执行 `examplesHaveTheRequiredBusinessResources`：缺少 runtime 示例导致明确断言失败。日志 `.oryxos-dev/evidence/batch7-red.log`；未知组预检失败单独在 batch7-red-gate.log，不把它当行为红灯。
2. 三条链路完整测试先于示例创建执行：7 项中 1 个缺文件断言失败、6 个缺示例目录错误；无跳过、非编译错误。见 batch7-full-red.log。
3. 示例首次接入后，GitHub 脚本相对路径被既有白名单拒绝。示例改为必须配置的绝对脚本路径；未修改 ProcessPolicy 或放宽断言。
4. UNKNOWN 夹具最初返回 JSON 业务拒绝，运行时正确给出 ERROR。改为实际接收后关闭连接、丢失响应；维持 UNKNOWN 断言，三场景均必须接收一次且不重发。
5. 补齐实际目录写入失败的记忆检查，以及脚本不向替代数据源转发凭证的检查。定向 package 9 项全部通过，run `f9b7346e-6439-4d87-926b-dd34f78748a4`；最后全量另核对 CLI 保存偏好的完整审计。

## 验收矩阵

| 场景 | 实际入口与资源 | 断言及可核查记录 |
| --- | --- | --- |
| 三场景手动 + 自动 | 天气从独立 CLI chat；科技/GitHub 从 HTTP；各启用 Spring cron 后真实自动触发一次，再关闭 | 6 个不同会话、6 次正常通知；天气共 2 次 GET、新闻共 2 次 MCP/source、GitHub 共 6 次源请求（每次脚本 3 栏目）。调度事件为 SCHEDULED/DISPATCHED，非手动 /run 冒充自动 |
| 共享偏好 | 先独立 CLI 显式 save_memory CORE，再启动 serve | “关注 AI 和芯片”跨进程、用户、会话、Agent 到实际 Provider 请求，日报通知正文含偏好；GET /memory 与 SQLite 执行/工具记录相符 |
| 按需参考与 MCP | 文件内容不预装入 system；read_file 后才调用真实 stdio MCP 子进程 | 工具 Schema/调用由真实 MCP SDK 发现；请求含 query/date，子进程 PID 不同，不继承 Provider 密钥 |
| 天气 | 正常一次 GET + notify | 上海、今天、12°C 雨、外套雨具及来源保留；正常一次查询一次通知 |
| 脚本 | 正式 shell 启动真实 Python，stdout 被模型夹具读取 | 日期/时区/三个查询口径、项目来源/星数核对；脚本失败非零退出、无假数据；修改 Agent 正文下一次生效 |
| 三类数据源失败 | HTTP 400、MCP isError、脚本 HTTP 错误 | 每个失败源实际收到一次请求，notify 为 0；PARTIAL + 未解决工具错误，可查 source/script 失败 |
| 三场景通知拒绝 | 回环接收方明确非零业务码 | 每场景一次请求，ERROR/PARTIAL，刷新记录不重发 |
| 三场景通知 UNKNOWN | 回环接收后不返回响应 | 每场景一次请求，UNKNOWN/PARTIAL、SIDE_EFFECT_UNCERTAIN、提示核查；之后不再调用模型或工具，无重放 |
| 缺参考、空新闻、不完整搜索 | 删除真实参考、新闻空数组、GitHub incomplete_results=true | 不生成虚构正常日报；缺参考不查新闻、不完整脚本失败，均无通知 |
| HTTP/脚本拒绝 | 未列入白名单的 localhost 目标、移除脚本白名单并放置触发哨兵脚本 | DENIED、HTTP 请求 0、脚本哨兵未创建、通知 0 |
| 记忆保存失败 | 实际 memory 目录不可写 | MEMORY_WRITE_FAILED/PARTIAL，CORE 仍空，文字不声称记住，无通知 |
| 脚本凭证边界 | 显式给脚本注入测试 token，目标为回环替代源 | 脚本非零退出、源请求 0、通知 0、输出不含 canary |

每个执行按 ID 获取 `/executions/{id}` 和 `/sessions/{id}`：核对工具顺序、结果、错误、Provider/model、
工作区时区、模型调用和完整 tool-call ID 配对；直查 SQLite 对应 execution_id 的 llm_calls/tool_invocations 数量。
模型收到的正文、参考、记忆与日期由夹具断言，但“真实模型能遵守这些指令”仍是未完成真实验收。

## 最终回归与脱敏记录

- 最终 run ID：`2c53a7bf-7814-4cf3-a40b-87ecfdda03ee`；185 个唯一测试，275 个分组登记引用包含跨组重叠。Python 报告门禁和构建隔离测试另有 6 项通过。
- [机器可读摘要](US5-seventh-batch-summary.json)记录用例、源文件哈希和 JAR SHA-256；发行 JAR 为 `a21dbfa552c75e094a49230c81e850a556d66da090667b9c7a2ff32d0db8032d`，与第六批相同，生产代码未改。
- [执行轨迹](US5-kernel-traces.json)包含 26 个选定执行详情、26 份配对历史、3 个真实自动调度事件和副作用计数；临时本机目录已脱敏，不包含模型/脚本测试密钥或私有 reasoning canary。
- 原始本次 XML、构建日志及进程 JSONL 保存在 `.oryxos-dev/evidence/batch7-full-verify/`；定向 package 归档在 `.oryxos-dev/evidence/batch7-package/`。历史失败日志保留，不用旧 XML 代替本次报告。
- HTTP/OpenAPI、真实 60 秒截止、CLI 发行入口、调度、故障与跨进程恢复均在全量内复跑；实际 19 操作/44 schema 对照零差异。浏览器人工证据沿用第六批，本批页面和生产后端未改，未声称重做浏览器验收。
- [一致性审查](US5-seventh-batch-review.md)记录基线保护、任务追踪和未完成项。构建及测试服务均已结束。

## 可重复命令

```bash
cd /Users/jianghaoran/github/OryxOS
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp package -Dgroups=kernel-local
./tools/dev/mvnw -B -ntp verify \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
python3 tools/dev/test_report_gate.py
python3 tools/dev/test_build_environment.py
```

先 package 后使用 JAR 参数；不要在同一命令加 clean 删掉待测 JAR。隔离缓存/临时工作区都位于 `.oryxos-dev/`。
测试自动启动并清理自己的服务，不要求真实凭证。选择真实配置及亲自操作见 [examples/README](../../../examples/README.md)。

## 保留的未完成项

T027/T028、T042/T044、T051/T057/T058、T070/T071/T072 的真实模型、增强记忆、通知和可信 MCP 前提及出口不变。
T098–T100 三个真实业务场景、T101 长跑、T102 新用户及 T103–T104 收尾未进入。本批本地六次成功不计入真实 SC-005 次数，
不关闭整体 SC-001/SC-003/SC-005。T073 的第六批完成结论保留，未重新降低其要求。
