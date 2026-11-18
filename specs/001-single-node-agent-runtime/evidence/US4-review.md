# US4 阶段审查与一致性检查

**本地完成，US4整体未验收。** 本批T059–T069共11项完成；T070/T071缺真实配置，T072依赖真实结果仍未完成。
T027/T028/T042/T044/T051/T057/T058未改变。104项共62项完成、42项未完成，其中10项为既有/本批真实前提与出口缺口，32项为US5及整合任务。
未进入US5，未提交或推送。

## speckit-analyze 只读检查

依技能运行`check-prerequisites.sh --json --require-spec --require-tasks --include-tasks`，读取当前spec/plan/tasks、
相关configuration/runtime/data-model/verification与宪章。分析过程只读；以下文档维护在分析结束后依据用户既有授权执行。

| ID | 类别/级别 | 初次发现 | 修复与受影响部分复查 |
| --- | --- | --- | --- |
| I1 | 状态不一致/MEDIUM | spec:9、plan:9/15/92/202、tasks:9/17、quickstart:3/56、CLAUDE仍显示US4未实施或本批US3 | 更新本地完成/真实缺口；历史US1–US3证据保留，不改验收定义；默认chat仍NOT_READY |
| I2 | 追踪遗漏/MEDIUM | verification:42漏列McpContractTest；真实入口缺具体配置与命令 | 补齐21个US4方法、2个real方法及文档链接；源码标签、manifest、XML逐项对应 |
| I3 | 配置说明遗漏/LOW | configuration已有default_target/header/env要求，但字段层级、header引用示例与最小环境不够具体 | 明确Agent顶层default_target、可选headers、shell.env及保留变量；没有新增产品行为 |

修复后没有本批CRITICAL/HIGH问题，无宪章冲突；没有把缺配置解释为通过，也未削减真实验收门禁。
无需改变已确认产品行为、宪章或任务依赖。下一动作是按具体前提补真实证据；本批不自动执行下一故事。

| 要求/场景 | 有任务 | 对应任务与状态 |
| --- | --- | --- |
| FR-019–023、US4-A1/A2/A6 | 是 | T059–T062/T068/T069本地通过；九工具成功/拒绝、真实脚本输出 |
| FR-024、US4-A3 | 是 | T064/T065/T069本地通过；可信已配置服务T071待完成 |
| FR-025、US4-A4 | 是 | T066/T068/T069通过；原生注解示例统一入口 |
| FR-026、US4-A5 | 是 | T063/T069本地通过；真实接收T070待完成 |
| FR-027、US4-A7、RV-01 | 是 | T067/T069及既有T043；有界重试/UNKNOWN立即停止/独立任务可用 |
| FR-028/029、SC-001/003 | 是 | T059/T061/T063/T064/T069本地；T070–T072及最终整合仍保留 |

全工件统计：41个FR、11个SC，104个唯一任务ID；要求均在任务中有引用，覆盖100%；无未映射US4任务。
本批未解决的需求歧义0、重复需求0、宪章冲突0、CRITICAL 0。需求覆盖不等于所有验收已经通过。
requirements清单16/16，整个过程只读未改标记。文档检查本身不代替以下代码和行为验证。

## 代码与行为核对

- 保留九模块骨架；boot合流九基线工具、原生示例与MCP，core只定义契约，memory不依赖tool/storage生产实现。
  全部模型工具调用仍经ToolExecutor，Spring只生成Schema，没有executeToolCalls或第二条循环。
- FileAccessPolicy拒绝控制区/秘密/数据库/AGENT.md、路径越界及symlink；读/list有上限，写同目录原子替换。
  Shell使用直接argv、解释器脚本路径约束、最小环境和有界流；超时报告UNKNOWN并尝试终止进程树。
- HTTP关闭自动retry/redirect/cookie/auth缓存，每跳重新授权及检查执行许可；POST/通知未知不重放。
  私有headers不跨origin；通知只替换完整$CONTENT字符串并检查目标声明、ack。
- MCP保持SDK协议/session实现，受控transport负责限长、环境、脱敏和进程清理。
  离线Schema预检在SDK DTO映射前执行，避免根$ref被DTO忽略；远程引用接收器计数0，敏感tool名/说明/Schema拒绝。
  显式jsonSchemaValidator适配项目锁定NetworkNT版本；SDK默认实现的缺失API不再触发。
- MCP每次物理tools/call检查当前执行许可，剩余deadline限制同步等待；计数区分未发/已发。
  截止定时器和退出清理同步，避免迟到interrupt误伤下一任务；关闭结果缓存，重复close保留失败。
  断连后server禁用，不重连；初始化失败仅影响引用该server的Agent。
- 工具分类经完整注册表检查；实际503 GET最多4次、POST只一次，退避序列和每次审计可查。
  接收丢包只有一条POST，当前任务不再请求模型/工具；另一个独立任务成功。
- 真实入口缺前提直接失败，不skip；通知自动ack后仍需人工接收核对，可信MCP需版本/入口哈希/实际发现调用/退出证据。

最终全量102测试/27类通过、0失败/错误/跳过；US4新增21项。另有隔离5项、报告门禁1项、F0真实JVM黑盒5组通过。
详见[US4-local](US4-local.md)。真实前检两项按缺显式配置确认失败，未执行外部动作；
[通知前提](US4-real-notify.md)与[可信MCP前提](US4-configured-mcp.md)保留具体后续操作。

## 环境与交付边界

US4开始前418个已有文件hash用于区分本批改动；变更仅涉及本批实现、验证、工具清单与关联文档。
父POM、宪章、requirements清单、数据库迁移、此前历史证据与无关改动保留。
官方实验/全局保护2416项、ACSP根配置11项hash复查无变化；这不扩张为未纳入快照目录的全量审计。
证据`.oryxos-dev/evidence/us4-environment-comparison.json`。Maven/JDK不改变全局配置；新增源码格式化工具仅缓存于个人`.oryxos-dev`，只格式化本批新Java文件。
`.specify/extensions.yml`不存在，无before/after hooks；staged为空。

受信任脚本/MCP内部权限不是OS级强隔离，进程终止也不能撤销远端动作。
US1–US4只提供结构化日志审计；正式Session/Execution持久化、业务HTTP/管理台/调度及最终整合仍由US5完成。
本批到此停止。
