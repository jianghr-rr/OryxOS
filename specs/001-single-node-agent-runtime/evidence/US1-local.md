# US1 本地实现与验证

日期：2026-11-12。范围T019–T026；均完成。T027/T028另见[真实前提](US1-real-models.md)。
未实现US2及后续；未提交、推送。现有九模块、完整Boot入口、薄CLI版本入口和F0资源生命周期保留。

## 环境与命令

个人仓库`/Users/jianghaoran/github/OryxOS`；隔离缓存/临时目录均在`.oryxos-dev/`。
只读复用既有JDK 21.0.13+9，Wrapper Maven 3.9.16、Boot 3.5.16、Spring AI 1.1.8，未升级版本。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
tools/dev/mvnw -B -ntp clean verify
tools/dev/mvnw -B -ntp test -Dgroups=us1-contract
python3 tools/dev/test_report_gate.py
python3 tools/dev/test_build_environment.py
python3 tools/dev/verify-foundation.py
```

最终完整构建：33个测试、13个类，失败0／错误0／跳过0；报告门禁33个必跑用例通过。
runId=`214945f5-6cf9-4f83-9280-de0c47fb2669`，完成时间2026-11-12T13:15:53Z。
其中F0为18项，US1为15项。独立US1分组也已通过15项
（runId=`adad0522-5f5b-44a3-a8f1-dfa7e3096f5e`）；最后的未知工具回填补充由上述最终完整构建覆盖。
原始日志：`.oryxos-dev/us1-full-final.log`、`.oryxos-dev/us1-final-contract.log`；
Surefire XML位于参与模块`target/surefire-reports/`。后续运行会替换这些本地报告，以本次记录为历史摘要。

## 先失败再实现

T019先准备HTTP接收夹具、6个行为用例和可编译端口骨架。AgentCatalog/ProviderRegistry明确返回NOT_IMPLEMENTED，
测试分别断言正常行为及预期错误码；不是制造编译错误或无条件assertFalse。
运行`-pl oryxos-provider -am test -Dgroups=us1-contract -Dmaven.test.failure.ignore=true`，
只用于让Maven继续执行两个模块的失败基线：6个测试全部断言失败、0错误、0跳过，报告门禁拒绝并退出2。
没有把Maven的failure.ignore输出视为成功。日志`.oryxos-dev/us1-red-all.log`保留实际失败信息。
接入实现后6项全通过，再补充风险覆盖至15项；最终验收命令不使用failure.ignore。

## 五个场景及风险覆盖

| 场景／要求 | 本地证据 | 结果 |
| --- | --- | --- |
| US1-A1；FR-003/004/038/039 | ProviderContractTest普通请求：真实HTTP序列化、配置model、实际响应model、前后callId/executionId/iteration一致；缺usage保留null | 通过；接收器每次1请求，零工具执行路径 |
| US1-A2；RV-04 | ProviderSmokeTest两份AGENT.md分别到两个接收器；配置顺序与调用顺序相反仍不串用 | 通过；两个服务各1次、来源和内容分别核对 |
| US1-A3；FR-002/005 | 缺密钥、未知Provider、非法地址/选项、Kimi禁用参数、重复/v1；Agent无效与有效并存 | 通过；坏服务0请求，有效服务继续成功 |
| US1-A4 | 401、429、503、连接拒绝、断连、重定向、坏JSON、空choices/内容；审计拒收前/后 | 通过；错误可区分，零重试/fallback，原始错误体不泄漏 |
| US1-A5 | 空白任务在网络前拒绝，INVALID_TASK | 通过；接收器0请求 |
| FR-008/020/024 | tool-reasoning夹具往返结构化assistant/tool、call id、原wire名及reasoning_content；跨Provider拒绝续传 | 通过；模型只提出数据，没有框架工具回调 |
| 工具名边界 | 两个MCP server同名remote工具映射不同hash；构造hash别名碰撞拒绝；未知wire不猜内部名，保留UNKNOWN_TOOL及配对回填 | 通过；不声称已接入真实MCP |
| FR-039 | 零预算0请求、慢响应头与慢正文均受剩余时限限制 | 通过；两次慢服务各1请求，分别在800ms测试上界前结束 |
| FR-002；RV-06 | 配置和正文哈希分开；修改正文不改已加载快照；规范字段顺序/注释不改配置哈希；默认10/20、空tools；cron六字段/统一时区 | 通过 |
| FR-028/030 | AgentStatusTest轻命令实际解析、通知只名称、不创建DB；StructuredLogAuditTest验证JSON、未知token=null、秘密/URL移除 | 通过；明确structured_log_not_database |

测试实际观察了响应正文超时时EOF被误归协议错误；已按本次单调时间预算归为PROVIDER_TIMEOUT，并复测通过。
代码复查补齐未知工具提议的可配对数据，避免适配层丢失调用、使后续引擎无法回填UNKNOWN_TOOL；未实现US2执行器。

## 实现边界

ProviderPort只作单次请求；显式注册表从runtime.yaml读取选项和秘密引用，各实例独立，不使用ChatModel/ChatClient或工具回调。
core不依赖Spring AI/JPA。客户端关闭重定向和系统代理，连接/read预算均受remainingDuration约束；失败保留安全类型、不附原始异常cause。
供应商续传只在相同配置Provider/model中重放，不进入toString或审计投影。实际模型版本来自响应，不用配置值冒充。
Spring AI接口与record构造器依据[锁定v1.1.8源码](https://github.com/spring-projects/spring-ai/blob/v1.1.8/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/api/OpenAiApi.java)，并经本地接收器验证实际JSON。

AgentCatalog是启动配置快照；status作为轻查询读取当前磁盘，明确说明它与运行中快照的区别。
正文按轮热读、Session/ReAct、工具真正执行、持久化审计、业务HTTP/管理页属于后续任务，没有提前宣称实现。
Agent对MCP这里只校验引用配置和秘密，不启动服务或验证可用工具。

F0黑盒回归5组通过：help/version轻入口、init幂等与root、两个JVM互斥和chat忙碌、无参数serve及JVM根优先级、
损坏SQLite保留字节且HTTP诊断进程存活。脚本关闭自己启动的进程，记录`.oryxos-dev/evidence/f0-smoke.json`。
Python报告门禁1项通过（含缺IT/跳过/旧报告等负例）；新增构建环境隔离3项通过，普通构建不传密钥，显式real只传两项允许变量。
