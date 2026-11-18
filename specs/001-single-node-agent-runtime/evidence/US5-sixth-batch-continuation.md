# US5 第六批续作验收记录

2026-11-13。本次按用户要求修复T094运行端OpenAPI，随后完成T095。**T073、T093–T095本地出口已全部通过；本批止于T095。** 104项任务现为85完成、19未完成，没有提交或推送。

## 结果与边界

| 检查 | 结果 | 本次run ID |
| --- | --- | --- |
| 发布OpenAPI专项及新JAR构建 | 2通过，19操作/44schema零差异 | 385824a4-38d9-4ab6-9882-e6f800c685d7 |
| us5-http，新增进程用例使用发行JAR | 71通过，0失败/错误/跳过 | 3005a5c4-59f1-4a17-bc50-4d2dc5b6ff18 |
| us5-cli-process，实际发行JAR | 3通过，十二操作及0/2/3/4/5/6/7退出码 | ef3e4398-b871-422b-a713-1bbcc890b801 |
| 最终全量verify，进程用例显式使用发行JAR | **176测试/39类全部通过**，0失败/错误/跳过 | da2432a0-7d17-49db-8b9e-332df150152b |
| Chrome实际管理台/正式后端 | 13组PASS，另加键盘和明确人工恢复 | US5-sixth-browser.json |
| Python隔离入口/报告门禁 | 6通过 | 本次unittest |
| 发行宿主基础黑盒 | 5组通过 | us5-batch6-cont-foundation.json |

全量门禁的266个required cases是登记引用，含跨组重复，不冒充266个唯一测试。verify前由个人入口清理旧XML，39份报告run ID一致。全量不是clean verify：本轮此前已clean重新编译并package新JAR，再执行实际发行验收；verify中同路径的现成包供子进程使用，最后一次OpenAPI专项package完成后，生产源码没有再改。最终包的检查SHA256为 a21dbfa552c75e094a49230c81e850a556d66da090667b9c7a2ff32d0db8032d。

公开证据：[结果摘要](US5-sixth-summary.json)、[HTTP](US5-http.md)、[CLI/浏览器及复现步骤](US5-cli-admin.md)、[跨进程恢复](US5-storage-recovery.md)。各次完整XML/build.log/summary已分别归档到项目 .oryxos-dev/evidence/us5-batch6-cont-http/、us5-batch6-cont-cli/、us5-batch6-cont-final/。

## T094修复

实际MVC操作的响应类型/状态和参数约束由控制器元数据提供；实际ContractDtos record字段、泛型ApiResponse及Swagger约束生成schema。没有在运行时读取或复制静态契约，没有另写同构DTO、静态44schema对象或静态/v3/api-docs响应。OpenAPI固定3.0.3，保持既定nullable语义。
原PublishedOpenApiTest逐项断言保留，新增精确19/44计数、版本、operationId、完整响应状态集合、请求体必填及新增record字段自动派生检查。最后一致性检查发现的19处操作ID差异先取得新行为失败，再从实际路由生成ID；新增红基线另存US5-openapi-operation-id-red.json。初次185项差异的JSON原样保留，本轮修复前也再次复现；当前文档和零差异另存为US5-openapi-fixed*.json。

## T095签收

真实发行CLI的十二操作、全部退出码、serve时chat拒绝而HTTP可用、停机移出定义不删历史和gateway重启读取原记录均通过。新增发行CLI实际60秒等待，模型迟到也不派发工具；HTTP原实际60秒断言未改。
Chrome使用本地测试宿主的正式HTTP/SQLite与真实页面，回环模型/接收器明确标记。13组含调度启停落库失败及恢复确认落库失败，验证无乐观成功、无自动重试、失败后重新确认。人工键盘验证了Enter/Escape/Space、故障缺口、修复不解锁、明确恢复和不重放；保存4张新截图和API/DB核对JSON。
最终32会话、20执行、24模型审计、7工具尝试、3已确认故障、1调度事件，接口与SQLite一致；模型24/接收4，恢复不增加次数。UNKNOWN与INCOMPLETE保持真实，已纠正的原始ERROR及关联未删。

## T073逐项收口

| 原完成条件 | 证据 |
| --- | --- |
| 先建可运行黑盒与缺能力失败基线 | 第一批存储、第三批HTTP、第五批静态服务及第六批发布OpenAPI的历史失败记录均保留；没有用编译失败/skip冒充 |
| 逐一19操作与44schema黑盒 | HttpApiContractTest行为/序列化，以及PublishedOpenApiTest实际运行文档零差异，均在本次综合/全量执行 |
| 实际端口、SQLite、进程重启 | CrossProcessAcceptanceIT独立发行JAR3项，本次综合/全量再次通过 |
| 配置重载、故障资料不全与人工恢复 | HTTP/调度重载测试、正式notify后双记录故障、浏览器不完整资料/失败恢复/明确确认及无重放 |
| 各本地阶段出口全部复跑通过 | T093、T094、T095及全量176项均通过，故此关闭T073 |

这只关闭US5至T095的本地出口。T027/T028、T042/T044、T051/T057/T058、T070/T071/T072真实前提/出口缺口保留；T096–T104的示例、完整三场景/真实业务/长跑/新用户等未进入。本地受控模型、Mem0协议夹具、通知接收器、MCP测试进程均不替代真实联调。

## 亲自运行

~~~bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp package -Dgroups=us5-published-contract
./tools/dev/mvnw -B -ntp test -Dgroups=us5-http \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
./tools/dev/mvnw -B -ntp test -Dgroups=us5-cli-process \
  -Doryxos.acceptance.jar="$PWD/oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar"
./tools/dev/mvnw -B -ntp verify
python3 -m unittest discover -s tools/dev -p 'test_*.py'
python3 tools/dev/verify-foundation.py
./tools/dev/admin-preview --port 18080
~~~

最后一个命令启动明确标记的本地浏览器测试宿主，页面 http://127.0.0.1:18080/admin/；新工作区自动验证页 /__admin_test__/index.html。正式服务使用 ./tools/dev/oryxos --root <已配置工作区> serve，默认页面 http://127.0.0.1:8080/admin/。普通chat已可用，需要Provider endpoint/协议/密钥引用、Agent provider/model及权限；与同根serve/gateway互斥。详细人工步骤、故障修复测试入口和配置边界见[CLI/管理台记录](US5-cli-admin.md)。
本次所有自建测试服务和子进程已停止。
