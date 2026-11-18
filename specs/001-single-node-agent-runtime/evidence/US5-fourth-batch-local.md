# US5 第四批本地证据

2026-11-13。本批仅T090，先准备测试再实现；不进入T091及后续管理台。所有构建/运行经tools/dev隔离入口，生产Session/Execution/Audit仍为SQLite/JPA。

## 测试先行

CliOperationsTest六项先编译运行，六项因缺少命令/JSON行为失败，无编译错误或跳过。原始日志`.oryxos-dev/evidence/us5-batch4-red.log`，XML保留在`us5-batch4-red/`。随后增加正式装配测试并实现；新增边界测试覆盖实际60秒、接收/最终记录阻塞及实际MCP注册快照。
测试编写中纠正了空Provider模板的断言：列表验证CURRENT_FILES和空items，不凭空要求已配置Provider。原有status测试的过时NOT_IMPLEMENTED预期改为NOT_CHECKED并拒绝“生产存储待实现”提示；其余幂等、秘密、坏库等要求保留。

## 本地验证矩阵

| 范围 | 测试/证据 | 已确认行为 |
| --- | --- | --- |
| Profile文件操作 | CliOperationsTest 3项 | create不覆盖；停机独占锁下移入agent-archive；不删除数据库；运行中删除返回4；路径穿越、符号链接拒绝且外部文件不变 |
| 轻命令及脱敏 | CliOperationsTest 3项 | 所有12操作帮助可见；status/provider/profile为文件查询；JSON安全参数错误；正文URL/Authorization脱敏；不启动launcher或创建库 |
| 离线工具/坏库 | CliRuntimeContractTest | 九个内置加native echo的静态描述；MCP=NOT_DISCOVERED；无数据库与损坏库返回3，原字节不变 |
| 实际启动快照 | CliRuntimeContractTest | tool按已加载权限过滤；配置修改显示RESTART_REQUIRED；重启后反映新配置；停机显示NOT_RUNNING；运行中chat/delete拒绝 |
| SQLite对话与历史 | CliRuntimeContractTest | 两次正式chat落库；JSON含HTTP相同identity/content/状态；游标分页；移出Agent后仍可按旧名称读历史；不暴露opaque |
| 退出码 | 两个测试类 | 0成功，2参数/配置，3不存在/只读存储不可用，4冲突，5任务失败，6截止，7记录故障/暂停；无内存装配绕过RUNTIME_NOT_READY |
| 独立JVM | actualProcessJsonOutputIsSeparateFromBootLogsAndLightQueriesStayOffline | 实际main进程chat stdout只有JSON；Spring日志在stderr；随后tool list不启动Spring/模型/MCP |
| 实际60秒 | actualSixtySecondCliDeadlineReportsUnknownAndNeverDispatchesLateTool | 真实回环模型等待，59–65秒内返回6/EXECUTION_TIMEOUT及执行ID；模型迟到提议不执行工具，模型1次、外部副作用0次 |
| 记录失败/公开投影 | actualRecordFailureIsExitSevenAndPublicOutputRetainsUncertaintyAndCorrectionFlags | 真实SQLite触发器阻断STARTED记录，模型0次、记录INCOMPLETE、退出7；另用受控Handle验证SUCCEEDED+UNKNOWN仍退出6、保留纠错标记及人类提示；公开执行字段与HTTP mapper相等 |
| 接收/收尾截止 | callerDeadlineIncludesBlockedAdmissionAndFinalRecord | 用受控时钟与阻塞Handle确认预算覆盖start和最终记录；响应先封闭许可，保留已知SUCCEEDED/UNKNOWN；该适配边界测试不替代上述真实60秒测试 |
| 实际MCP注册表 | mcpToolsComeFromActualRegistrationWithoutRediscoveryOrStaleSnapshots | 本地stdio服务实际发现两页；按profile仅列授权MCP工具；轻查询没有新增协议调用；异workspace投影拒绝，关闭后删除投影；不作为可信已配置MCP验收 |
| serve/gateway/无参数 | 既有ScheduleIntegrationTest、打包foundation黑盒 | 两模式仍装配同一HTTP/调度/SQLite并共用工作区锁；chat不启自动调度；薄入口仍独立版本模式 |

启动投影只含脱敏工具描述、Agent配置/正文摘要，绑定工作区和活跃进程；RUNNING_REGISTRY_SNAPSHOT不表示实时连通性。文件权威不变，查询不打开模型/MCP客户端。离线available=false表示没有建立运行可用性，不伪造已发现服务。
JSON按HTTP相同含义输出code/message/data/timestamp/traceId，chat每轮一行；未查询的完整审计明细不伪造空列表，按执行ID通过现有HTTP查详情。人类输出保留recording、inFlight及已纠正/未解决工具错误；UNKNOWN/暂停提示先查记录，人工恢复仍走HTTP。

## 回归与产物

- 最终`tools/dev/mvnw -B -ntp clean verify`：**166个唯一测试、35类，0失败/错误/跳过**。九个子模块成功；185个local登记项含19个跨组重复，15项本批用例均执行。
- Run ID：`02de1518-f858-4e2a-bff3-27f75380411e`。原始35份XML、build.log、summary.json保留于`.oryxos-dev/evidence/us5-batch4-full-verify/`。
- `python3 -m unittest discover -s tools/dev -p 'test_*.py'`：6项通过。
- `verify-foundation.py`：5组打包真实JVM黑盒通过。结果`us5-batch4-foundation.json`与日志同目录。
- `us5-batch4-packaged-cli.json`：完整发行的12项帮助和10次轻操作检查，JSON信封、重复create=4、缺库session=3、全过程不创建数据库。
- `us5-batch4-release-inspection.json`：检查fat JAR及8个内嵌项目JAR，CLI类齐全，无测试临时存储/夹具/秘密文件，无admin新增。
- `us5-batch4-consistency.json`记录任务状态、保护文件、改动清单、清单/覆盖/链接、构建run ID。初始524个已有文件hash保留在`us5-batch4-before.json`。

## 可复现命令

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp clean test -Dgroups=us5-cli
./tools/dev/mvnw -B -ntp clean verify
python3 tools/dev/verify-foundation.py
```

实际十二操作与chat配置步骤见[quickstart](../quickstart.md#us5-第四批-cli-验证)。普通chat已可用；仍需有效Provider endpoint/path、真实账号凭证、Agent的provider/model配置，凭证放环境或0600 secrets.local.yaml。默认md、无工具chat不依赖Mem0/通知/MCP。

## 明确保留的缺口

T073保持未勾选；T091/T092管理台、T093–T095完整跨入口与浏览器出口、T096以后整合/三场景/长跑/新使用者/最终交付均未完成。既有T027/T028、T042/T044、T051/T057/T058、T070/T071/T072保持未完成。
本批的回环模型、接收方、stdio MCP以及受控Handle不是公网真实模型、Mem0语义、实际通知接收或可信配置MCP的替代证据。
本批停止于T090；没有暂存、提交或推送。
