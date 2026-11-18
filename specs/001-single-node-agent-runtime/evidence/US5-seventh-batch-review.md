# US5 第七批一致性审查

2026-11-13。**T096、T097 完成，止于 T097。** 当前 104 项任务中 87 完成、17 未完成。

| 任务 | 结论及依据 |
| --- | --- |
| T096 | 三个 Agent、独立组稿参考、可信 GitHub 获取脚本、runtime/MCP 模板和配置/操作说明已提供。业务未写入 ReAct；通知目标必须显式配置，cron 默认关闭，秘密只用引用。 |
| T097 | 先建立资源缺失红灯，再接入示例。`kernel-local` 9 项通过；实际 CLI/HTTP、自动 cron、SQLite、MCP 子进程/脚本/回环 Webhook 六次正常运行和失败矩阵通过。 |
| T073/T093–T095 | 保留第六批已完成状态；HTTP/OpenAPI、真实 60 秒截止、跨进程恢复和发行 CLI 全量回归通过。页面和后端生产代码未改，浏览器人工证据仍归第六批。 |
| 未完成 | T027/T028、T042/T044、T051/T057/T058、T070/T071/T072；T098–T104。没有以本地模型/数据/通知夹具关闭真实联调、整体验收或长跑任务。 |

最终发行 JAR `verify`：185 个唯一测试/40 类，0 失败、0 错误、0 跳过；275 个登记引用包含跨组重叠。
run ID `2c53a7bf-7814-4cf3-a40b-87ecfdda03ee`。Python 门禁/隔离测试另 6 项通过。
实际 OpenAPI 19 操作/44 schema 零差异，静态契约未改。

可核查记录见 [本批证据](US5-kernel-local.md)、[摘要与哈希](US5-seventh-batch-summary.json)、[26 份执行轨迹和历史](US5-kernel-traces.json)。
正常主矩阵是 6 个独立执行/会话、6 次通知；3 个自动事件均为 SCHEDULED/DISPATCHED。
另外保留 3 个 UNKNOWN 通知，接收端各收到一次，停止模型/工具调用且查询不重放。测试凭证 canary 未进入发布证据。

## 改动保护与范围

本轮开始对 492 个 Git 可见既有文件保存 SHA-256；没有删除文件，484 个逐字节保留。
8 个既有文件只更新任务/进度/验证说明及 test-groups.json：CLAUDE.md、tasks.md、spec.md、plan.md、quickstart.md、verification.md、tools/dev/README.md、tools/dev/test-groups.json。
新增文件仅为 examples、KernelLocalIT/KernelFixture、kernel 测试资源和本批证据。

349 个保护文件未变，包括所有既有生产模块、静态管理台、HTTP 契约、数据模型/研究、清单、.specify/.agents、POM、website 和工具隔离入口。
发行 JAR SHA-256 与第六批完全相同，证明本批业务示例不需要修改运行内核。
104 个任务 ID 唯一，52 个 FR/SC 均有任务映射；登记的测试类/方法存在且本次 XML 门禁通过。
requirements 清单仍 16/16，没有修改 reviewer 的检查标记；过去各批证据原样保留。
一致性原始输出在 `.oryxos-dev/evidence/us5-batch7-consistency.json`。

Git diff --check 通过；没有暂存、提交或推送。仅在个人 checkout 工作，未改官方实验仓库、全局环境或 ACSP。
`.specify/extensions.yml` 前置与完成前均不存在，无扩展钩子。已结束本批测试进程与临时服务。

## 亲自运行

无需真实凭证的回归：`./tools/dev/mvnw -B -ntp test -Dgroups=kernel-local`（先设置 ORYXOS_JAVA_HOME 为已有 JDK 21）。
完整复现、发行 JAR 参数和证据位置见 [本批证据](US5-kernel-local.md)。
真实业务配置与手动/定时操作见 [examples/README](../../../examples/README.md)；这些步骤并非本批已经完成的真实验收。
