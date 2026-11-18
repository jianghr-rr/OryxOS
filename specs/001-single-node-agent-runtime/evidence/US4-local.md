# US4 本地实现与验证

本批仅T059–T072，完成T059–T069（11项）。T070/T071缺真实目标与可信服务配置，T072依赖真实证据，均未完成。
T027/T028/T042/T044/T051/T057/T058保持未完成；未进入US5，未提交或推送。

## 环境、命令与结果

使用个人checkout `/Users/jianghaoran/github/OryxOS` 和本项目 `tools/dev` 包装器。
JDK21.0.13+9只读复用 `/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home`；
Maven3.9.16、依赖、home/tmp与证据全部隔离到本项目`.oryxos-dev`。没有升级POM锁定版本。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp test -Dgroups=us4-tools
./tools/dev/mvnw -B -ntp clean verify
python3 tools/dev/test_build_environment.py
python3 tools/dev/test_report_gate.py
python3 tools/dev/verify-foundation.py
```

| 检查 | 本次实际结果 |
| --- | --- |
| 最终clean verify | **102测试/27类，0失败、0错误、0跳过**；runId `b53ee452-c3b9-49e2-9868-64c1e8ca0a26`；`.oryxos-dev/us4-final-verify.log` |
| US4用例 | 21项全部纳入全量：7项文件/Shell/原生/子进程环境、4项HTTP/通知、5项MCP、4项Boot/CLI、1项Agent配置 |
| 报告门禁 | 114个必跑登记匹配，其中12个deadline用例跨标签重叠；实际102项各执行一次 |
| 构建隔离与报告有效性 | Python环境检查5项、报告检查1项通过 |
| F0真实JVM黑盒 | 5组通过：轻命令、幂等init/status、进程互斥/释放、无参数serve/根优先级、损坏SQLite降级；`.oryxos-dev/us4-foundation.log` |
| 生产发行 | 主JAR无临时Session/Execution Store、ScriptedModel、RealToolProbe或环境夹具；默认chat仍NOT_READY |
| 真实前检 | 2测试/2失败/0错误/0跳过，明确缺confirmSend/confirmCall；没有通知外发或可信MCP启动，不能计入真实验收 |

全量XML与summary归档 `.oryxos-dev/evidence/us4-full-verify/`，真实前检另存`us4-real-preflight/`；
不能把后续target中的真实失败报告与本地全量混合计数。

T059行为红灯见`.oryxos-dev/us4-red.log`：编译成功，3项用例因缺实现失败（1 failure/2 errors），无skip。
中间发现并修复：SDK默认Schema校验器调用了锁定NetworkNT1.5.9不存在的API，现显式桥接既有离线校验器；
强制关闭夹具原先会随stdin EOF正常退出，现真实忽略SIGTERM并持续运行；CLI夹具资源定位与拒绝退出码预期已修正。
这些中间失败均未算通过，最后全量重新执行。

## 九工具成功／拒绝矩阵

CLI集成从 `OryxOsApplication.execute` 进入真实解析器、Boot、AgentService、ReActLoop和ToolExecutor。
模型是明确的ScriptedModel，Session/Execution Store仅测试装配；MemoryService和MD后端、文件/进程/HTTP/stdio均实际执行。

| 工具 | 实际成功证据 | 拒绝／失败证据 |
| --- | --- | --- |
| read_file | 读取skills/reference.md，内容进入工具历史 | 无工具权限时DENIED；控制文件/越界/symlink被统一文件策略拒绝 |
| write_file | output/fact.txt实际原子替换为saved fact | 无工具权限0写入；控制区/秘密/数据库/AGENT.md/.env拒绝；越界目标字节保留 |
| list_dir | 列出真实output文件 | 无权限DENIED；最多1000项并标记截断 |
| shell | 实际Python脚本输出进入后续模型消息 | -c和未允许脚本拒绝；超时UNKNOWN且核对cleanupConfirmed；不拼接shell命令 |
| http_get | 回环接收器计数1 | 无权限计数0；继承US2目标/重定向边界；503最多4次并有4次ERROR审计 |
| http_post | 回环接收器计数1、JSON实际到达 | 无权限计数0；注入headers在Schema校验拒绝；503只1次；接收断连UNKNOWN且不重试 |
| save_memory | 实际MD显式保存共享事实 | 无权限无记忆文件；凭证/损坏保护继承US3回归 |
| recall_memory | 真实后端读取已保存事实 | 无工具权限DENIED；跨工作区/未确认来源保护继承US3 |
| notify | 实际Webhook接收1条、JSON转义和私有header正确、2xx+业务ack确认 | 未声明目标/多目标无默认先拒绝；2xx业务拒收ERROR；接收断连UNKNOWN且发送1次 |

CLI成功用例还执行Echo和`mcp.first.echo`，共11条SUCCESS；拒绝用例九工具全部DENIED、HTTP计数0，文件/记忆未生成。
通知不确定场景中同轮write_file记录NOT_EXECUTED、不再请求模型；之后独立healthy任务成功，未全局暂停正常任务。

## 扩展、边界与重试

- 文件读取64KiB且标记截断；列表1000项；写同目录临时文件、force、原子替换、目录force；发布结果无法确认返回UNKNOWN。
- 子JVM真实带入外来CANARY和假Provider密钥，再启动Shell/MCP；两类子进程环境均无这两个值。
  仅显式引用进入最小环境；stdout/stderr有界且已注册秘密脱敏。测试使用虚构CANARY，无真实凭证。
- SDK0.18.3实际stdio initialize、两页发现、两个server同名remote工具、实际call和close通过。
  internal/wire/remote名称分离；向MCP只发remoteName。远程`$ref`被拒绝且真实HTTP接收器计数0，敏感工具名也拒绝。
  isError映射ERROR；断连/截止映射UNKNOWN且后续不重连、不重放；强制关闭记录MCP_CLOSE_FAILED，重复close不抹掉失败。
- Boot启动校验只使引用不存在工具/失败server的Agent不可用；健康Agent和无关工具继续可用。
- 原生@Tool只产生Schema，反射调用仍由ToolExecutor授权/校验/审计；重复名称明确失败，不启用Spring自动执行。

| 工具类别 | SAFE_TRANSIENT / NONE |
| --- | --- |
| http_get | 429、5xx、连接失败、读取超时且无副作用时可SAFE_TRANSIENT；401/403/目标拒绝/协议限制为NONE |
| read_file、list_dir、recall_memory、Echo | 描述符允许安全重试，但本实现的本地IO/格式/参数错误无可证明暂时性，返回NONE；仅显式安全暂时错误才可重试 |
| write_file、shell、http_post、save_memory、notify、MCP及其他原生扩展 | 默认NONE，不因暂时错误自动重放；MCP不假设幂等 |

执行器最多1+3次、250/500/1000ms退避，每次attempt留痕并受剩余deadline约束。
这是应用层白名单；允许的可信脚本/MCP仍是本机进程，其内部文件/网络行为不受内置HTTP规则全面管制，不宣称OS强隔离。
US1–US4审计为结构化日志。正式Session/Execution存储、数据库审计、管理台与最终整合属于US5。
