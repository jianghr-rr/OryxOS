# US5 第六批初次验收记录

> 本文保留初次失败快照；续作已修复并完成T094/T095及T073，当前结果见[续作记录](US5-sixth-batch-continuation.md)。下文“当前”均指初次运行时。

2026-11-13。按依赖推进T093–T095，本批结果为：**T093通过；T094未通过；T095未进入完整验收；T073不关闭。** 未进入T096，没有暂存、提交或推送。

## 执行结果

| 验证 | 实际结果 | run ID |
| --- | --- | --- |
| 独立发行JAR跨进程恢复（us5-process） | 3通过，0失败/错误/跳过 | 1f7d9fb9-8373-4093-8548-dc9fe574dc87 |
| 综合us5-http，显式使用发行JAR | 70项，69通过、1失败，0错误/跳过 | 3c0c4eab-0235-44a0-9c2e-0811e10c473d |
| 全量verify，新增进程用例使用发行JAR | 172项/38类，171通过、1失败，0错误/跳过 | ae40f829-a56c-4b78-b8be-d3bed996b111 |
| Python隔离入口与报告门禁 | 6通过 | 本地unittest |

两次综合/全量命令都以非零退出，失败用例均为PublishedOpenApiTest.generatedOpenApiDescribesAllOperationsAndPublicSchemas；没有报告为构建通过。默认local清单保留该失败用例，不使用skip、改写排除项或缩小断言来获得绿色。
全量使用`verify`，不是`clean verify`；个人入口仍在运行前清理所有相关旧Surefire报告，38份报告run ID一致。已有JAR用于显式分发包测试，没有把旧XML当新结果。
当前local清单计数含重复分组引用，不等于唯一172项；综合组70个必跑用例均执行。14项HTTP行为、真实60秒HTTP/CLI截止、存储/调度/重启与既有CLI/页面静态服务回归通过；发布契约一致性未通过。

## T093签收依据

新增测试先准备，再运行实际独立进程。没有生产运行时代码变更；初期夹具配置/断言问题单独修正，不冒充产品能力缺失的红基线。
正式JAR SHA256为`d5f4dad5be8f11bd2615b16d5660f77d0a3e81a41dca30e4518103c51187c8e5`。它是第五批最终发行产物；本批未改运行时、静态页面、依赖或数据库迁移。所有新的跨进程测试显式读取该包，而不是用内存端口或同JVM的上下文重建。

最终全量中的脱敏记录：[跨进程HTTP证据](US5-sixth-process.json)，含实际PID、会话/执行/故障ID和响应。三个场景汇总：

| 场景 | 执行/会话标识及计数 |
| --- | --- |
| 历史、记忆、审计和调度恢复 | execution=d702107c-f68a-4529-9e81-1e3bae9825cf；activeSession=cdaa0327-06e8-4192-be06-1b32860c0013；emptySession=efcf2ca9-0563-48f7-9713-951fe9f95344。模型总计5、通知0，重启与查询没有新增调用。 |
| 动作前记录失败 | 模型0、通知0，HTTP失败、全局暂停、CLI退出7；修复或重启不自动恢复，明确确认后仍0。 |
| notify后记录/fault资料失败 | execution=171b527b-c761-429e-9c75-9db1dd998b01；模型1、通知接收1、保留旧run标记1；修复、重启、重复确认和再次重启不重放，缺失记录仍INCOMPLETE。 |

通知经正式notify工具及声明的渠道/白名单发送到本地HTTP接收器。这证明本地协议、存储和恢复语义，**不证明真实通知渠道或收件人验收**。模型也是回环协议夹具；真实模型/Mem0/可信MCP缺口未改变。详细断言见[存储恢复验收](US5-storage-recovery.md)。

## T094失败与依赖边界

从运行进程GET `/v3/api-docs`取得真实生成文档：19操作存在，但只有4个输入schema，通用Object返回类型未发布完整业务响应。与既定19操作/44schema契约有185个比较项差异，分类及原始JSON见[HTTP验收](US5-http.md)。这是可重复的验收失败，不以已有实际接口响应正确来关闭发布契约要求。
T094需要补齐运行端OpenAPI元数据并复跑；本批没有复制静态契约假装实际生成匹配。T095前置T094未满足，未启动完整CLI/浏览器重新验收；第五批浏览器记录保持其原批次含义，见[CLI/管理台状态](US5-cli-admin.md)。
T073要求阶段出口全部通过，当前条件不满足，继续未勾选。

## 命令与原始材料

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp test -Dgroups=us5-process
# 以下两项当前应失败，原因是发布OpenAPI不符：
./tools/dev/mvnw -B -ntp test -Dgroups=us5-published-contract
./tools/dev/mvnw -B -ntp test -Dgroups=us5-http
# 全量验证同样保留上述失败：
./tools/dev/mvnw -B -ntp verify
python3 -m unittest discover -s tools/dev -p 'test_*.py'
```

本批实际命令对进程组/综合组/全量额外传入：
`-Doryxos.acceptance.jar=/Users/jianghaoran/github/OryxOS/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar`。
未提供该参数时使用独立main JVM，仍是真实HTTP/SQLite；仅显式参数的运行才计作发行JAR证据。已有JAR和应用运行能力未改变，当前构建验收失败不应被误写为模型/HTTP运行服务完全不可用。

原始XML、build.log、summary.json分别在项目`.oryxos-dev/evidence/us5-batch6-packaged-process/`、`us5-batch6-http/`、`us5-batch6-full-verify/`；逐请求原始记录文件名含最终run ID。
测试工作区、缓存和日志均在项目.oryxos-dev；子进程使用清理后的环境和隔离user.home/tmp。未改官方实验目录、全局环境或ACSP。所有自建测试进程已结束。
