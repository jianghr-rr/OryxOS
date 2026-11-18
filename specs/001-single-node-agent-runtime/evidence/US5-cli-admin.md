# T095 CLI 与浏览器整体验收

2026-11-13，第六批续作。T094通过后执行本项：**十二项发行CLI与浏览器整体验收通过**。初次第六批因T094失败未进入；第五批证据保留原批次，本文件采用本次新运行材料。

## 发行 JAR

新增 CliCommandsIT，先准备黑盒测试，再实际启动发行JAR。3项全部通过，0失败/错误/跳过；run ID ef3e4398-b871-422b-a713-1bbcc890b801。

1. 十二操作：init/status/chat/serve/gateway/profile list/create/show/delete/provider list/tool list/session list。init幂等；轻命令无Spring/模型/MCP启动；JSON stdout为一个信封、日志stderr；离线工具明确未发现MCP。
2. 同根serve期间chat退出4、profile delete退出4，HTTP仍可执行；停止后移出Agent定义，Session/Execution/消息/模型审计数量不变，gateway重启仍能查原ID及历史。
3. 退出码0/2/3/4/5/6/7均来自真实进程。新增实际60秒CLI等待，返回6/TIMED_OUT；释放迟到模型响应后模型1次、接收0次、数据库保持TIMED_OUT。配置错误、资源缺失、模型失败和旧run暂停各自有独立结果，未伪造成功。

原始XML/命令信封/退出码在 .oryxos-dev/evidence/us5-batch6-cont-cli/，脱敏精选见[CLI记录](US5-sixth-cli.json)。

## Chrome 与正式后端

tools/dev/admin-preview 启动独立 Java 测试宿主，使用正式 Boot 装配、HTTP服务、持久化引擎和真实SQLite。测试类路径只增加回环模型、接收器与受控故障入口，页面明确显示“本地验证服务”；它不冒充发行包中的真实模型、通知或MCP服务。

新工作区中13组DOM/同源HTTP流程全部PASS：[浏览器结果与请求清单](US5-sixth-browser.json)。覆盖查询/空态/错误/加载/分页/身份、纯文本、写操作一次、刷新仅GET、UNKNOWN、已纠正错误、调度启停/手动历史、不完整故障/人工恢复。新增SQLite scheduled_tasks更新失败、incidents确认更新失败：页面没有乐观成功，后端仍停用/暂停，失败消耗确认，重新明确勾选后才恢复，无自动重试。

随后通过Chrome原生键盘/页面交互核验：

- Enter打开运行框，Escape取消并把焦点还给运行按钮；取消未产生写入。
- 查看本次UNKNOWN与已纠正执行，旧ERROR及bad-params→fixed-params关联仍可见。
- 提交一次“记录故障人工复核”，接收1次后审计更新失败；页面PAUSED_RECORDING、新执行禁用、detailsComplete=false，填说明但未勾选时恢复按钮禁用。
- 只移除本地故障触发器后仍暂停；Space明确勾选、Enter提交恢复，返回READY，原执行保持INCOMPLETE，接收计数没有增长。

本次截图：[UNKNOWN](US5-sixth-unknown.jpg)、[已纠正](US5-sixth-corrected.jpg)、[故障动作与确认入口](US5-sixth-incomplete.jpg)、[人工恢复READY](US5-sixth-recovered.jpg)。截图仅含随机测试ID、公开测试文本；未重用第五批图片。
[页面/API/DB核对](US5-sixth-browser-backend.json)：自动流程结束模型23/接收3；人工流程后模型24/接收4、业务写请求13、32会话/20执行/24模型审计/7工具尝试/3已确认故障/1调度事件；SQL与API数量一致，调度仍停用，恢复没有重放。两次记录失败的工具尝试恢复后仍为UNKNOWN，公共投影保留INCOMPLETE，不补造成功。

测试服务已停止；可用以下命令创建一套新的隔离测试工作区：

~~~bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp test -Dgroups=us5-cli-process \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
./tools/dev/admin-preview --port 18080
~~~

浏览器打开 http://127.0.0.1:18080/admin/。保存用户标识后逐页查询，运行普通内容、“纠正参数验证”、“未知结果验证”；查看执行与审计，刷新检查不重发。需要完整自动复验时，**在新启动且未手动操作的夹具**打开 http://127.0.0.1:18080/__admin_test__/index.html，13组全部结束应为PASS。Ctrl-C停止本地测试服务。

人工故障复验可运行“记录故障验证”，查看故障资料；先调用仅测试宿主提供的修复入口，再在页面填写说明并明确勾选确认：

~~~bash
curl -fsS -X POST http://127.0.0.1:18080/__fixture__/repair
~~~

此入口只移除测试触发器，不恢复执行闸门，不在发行JAR中。

## 普通运行

正式入口仍为 ./tools/dev/oryxos --root <工作区> serve，默认页面 http://127.0.0.1:8080/admin/、API /api/v1、文档 /v3/api-docs。普通chat也已可运行，需有效runtime.yaml的Provider协议/endpoint/密钥引用，以及Agent的provider/model和明确工具权限；凭证放受控本地secrets.local.yaml（权限600）或相应环境引用。没有配置真实模型时不能承诺真实任务成功。
同根serve/gateway与chat互斥，常驻期间从管理台或HTTP发任务。T096–T104和十项真实前提/出口缺口未推进。
