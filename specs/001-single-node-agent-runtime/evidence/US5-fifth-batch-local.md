# US5 第五批本地证据：管理台

2026-11-13。仅T091/T092，止于管理台查询与运行控制。页面使用原生HTML、CSS、ES modules，位于oryxos-web的static/admin/，固定同源/api/v1；无Node构建链、无website改动。

## 测试先行与回归

先新增AdminPageContractTest两项并登记us5-admin，可编译运行后均因/admin/返回404失败（2失败、0错误/跳过）。原始证据：`.oryxos-dev/evidence/us5-batch5-red.log`及`us5-batch5-red/`。随后实现页面、静态入口与运行控制，并用正式HTTP/SQLite和浏览器验证。

- 全量`tools/dev/mvnw -B -ntp clean verify`：**168个唯一测试、36类，0失败/错误/跳过**，含原有实际60秒HTTP/CLI截止及存储/调度/重启回归。187个local登记项含19个跨组重叠；run ID `fb4dab10-9da2-4ef8-b9ad-84a1e6ba9cd5`。36份XML、build.log、summary.json在`.oryxos-dev/evidence/us5-batch5-full-verify/`。
- 全量后修正跳到主内容时保留当前视图，并同步serve/gateway帮助提示。最终`verify -Dgroups=us5-admin`再次通过2项，run ID `ccffd5b7-6f12-44d6-9c36-50063207c698`；最终浏览器12组复跑通过。没有把两个定向用例计入新的全量总数。
- AdminPageContractTest验证实际后端提供HTML/全部JS/CSS、/admin重定向、正常/记录暂停工作区均能看页面、暂停health=503、模型0调用、普通服务测试端点404。
- `python3 -m unittest discover -s tools/dev -p 'test_*.py'`：6项通过；打包foundation黑盒5组通过，`us5-batch5-foundation.json`。
- 打包实际启动再次验证/admin/、最新app.js、真实profiles API，测试端点404；完整发行status及serve/gateway帮助正确，轻查询不宣称健康，模型0调用。见`us5-batch5-packaged-admin.json`。
- fat JAR和8个内嵌项目JAR检查：7个admin资源与最终源码逐字节相同，没有AdminBrowserProbe、测试页、PersistentFixture、临时存储或secrets.local。见`us5-batch5-release-inspection.json`。

## 浏览器与真实后端

使用Chrome访问`http://127.0.0.1:18080/admin/`，后端为正式Boot、SessionManager、AgentService、SQLite Session/Execution/Audit、RecoveryPort和调度服务。**远端模型与HTTP接收器为回环夹具，不是真实模型、Mem0、通知或可信MCP验收。** local Agent说明和测试页均标注“本地验证服务”。fixture只存在于测试classpath/profile，不进入正式发行。

两次完整浏览器验证分别使用`.oryxos-dev/tmp/us5-batch5-browser3`与`us5-batch5-browser4`，每次新建工作区；最终复跑在browser4。测试页在iframe内通过实际表单、按钮、导航及同源请求驱动真实页面，没有用内存存储或静态假数据替代业务后端。初期修正过夹具自身的3个Agent计数、筛选器定位和reload等待；最终断言仍要求真实分页、身份保留和单次写入。

| 浏览器流程 | 预期与实际 |
| --- | --- |
| 身份与Agent | 无用户标识禁运行；保存browser-review后实际请求带相同X-Oryx-User-Id；无效Agent禁用；刷新保留身份，不暗称登录 |
| 共享记忆 | CORE与ARCHIVAL各一条来自实际save_memory；标注md/工作区共享；`<img src=x onerror=alert(1)>`仅作为文字，无img节点 |
| 会话分页 | 初始26会话按20+6读取，无重复/丢失；按browser-review筛选3条；长历史按20+4得到24条，API一致；显示HTTP身份与创建时模型 |
| 空/加载/错误 | 无匹配显示空态；受控实际HTTP查询失败显示LOCAL_TEST_QUERY_FAILURE；延迟时显示加载；离开视图后迟到请求不覆盖；跳至主要内容保留当前视图 |
| 普通手动任务 | 一次POST、一次模型调用；显示执行ID/COMPLETE，查询真实模型审计actual-fixture-model；模型输出只作文本 |
| 刷新与重新打开 | 只GET，模型和写请求计数不增加；未自动提交或重试 |
| 已纠正错误 | SUCCEEDED且hasToolErrors=true/hasUnresolvedToolErrors=false；bad-params的原ERROR/RESOLVED、fixed-params及双向关联仍可见；实际接收1次 |
| UNKNOWN | 接收器已接收但断开响应，页面提示UNKNOWN核查，不显示全部成功；实际接收1次，刷新不重发 |
| 定时控制 | 启用/停用各一次PUT，与API的enabled一致；停用任务立即执行一次POST，不改enabled；触发历史DISPATCHED可查 |
| 记录故障与恢复 | 接收1次后SQLite trigger拒绝工具结果写入；PAUSED_RECORDING、INCOMPLETE、detailsComplete=false可见，新执行禁用；只填说明不能恢复；修复存储后勾选并明确提交，READY且无重放 |
| 键盘操作 | 实际浏览器Enter激活导航、运行按钮和执行详情；原生dialog聚焦任务输入，Escape关闭且未提交。屏幕状态有文字，不只颜色 |

最终12组测试全部PASS（上表将相关流程合并），浏览器请求共8次业务写入：4次Agent POST、2次启停PUT、1次schedule POST、1次recover POST。最终模型计数23（含初始化历史/记忆），回环副作用总计3（纠正、UNKNOWN、记录故障各1）；恢复不增加计数。
最终API逐条与只读SQLite核对：31会话、19执行、23条模型审计一致。特殊执行：

| 执行 ID | 状态与证据 |
| --- | --- |
| 11521084-a29c-4945-92d2-9278a39f1922 | SUCCEEDED/COMPLETE，保留bad-params与fixed-params纠正链 |
| 3bb24802-d990-488a-b134-76b5891c2fab | PARTIAL/COMPLETE，SIDE_EFFECT_UNCERTAIN，工具UNKNOWN；COMPLETE只表示记录完整，不表示动作全部成功 |
| 236be8be-17bb-41c9-be93-dcfa9afa65fe | PARTIAL_RECORDING_FAILURE/INCOMPLETE；恢复READY后仍保留缺口 |

脱敏结构化记录：[浏览器与API/SQLite对照](US5-fifth-batch-browser.json)。截图来自首次完整通过的browser3工作区，故与最终复跑UUID不同：[共享记忆](US5-fifth-batch-memory.jpg)、[已纠正错误详情](US5-fifth-batch-corrected.jpg)。均为合成测试内容，不含真实凭证、私有模型数据或真实通知地址。

## 实现边界与操作命令

每次写操作先GET核对当前READY或相同incident；提交期间全局禁重复操作，POST/PUT不自动重试。刷新/轮询只GET，网络结果不确定时提示先查记录。恢复需要勾选及说明；服务端重新核查存储，不用暂停时storageWritable=false的旧快照永久禁用人工恢复。配置仍由文件维护，无Agent/Provider/通知/cron编辑。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp clean verify
# 已配置的正式工作区：
./tools/dev/oryxos --root /absolute/path/to/your-workspace serve
# http://127.0.0.1:8080/admin/

# 独立本地验证服务（每次新建工作区，Ctrl-C停止）：
./tools/dev/admin-preview --port 18080
# http://127.0.0.1:18080/admin/
# 新夹具自动验证页：http://127.0.0.1:18080/__admin_test__/index.html
```

本批启动的测试服务已停止。亲自操作的输入、核查顺序及仅测试repair命令见[quickstart](../quickstart.md#us5-第五批管理台验证)。普通chat和页面运行真实任务需要可用Provider endpoint/path、凭证、Agent provider/model配置；无工具、默认md的普通chat不要求Mem0/通知/MCP。当前页面身份输入为可见ASCII，非ASCII历史可查询，但本批未定义Unicode HTTP头编码方案。

## 未完成项

T073及T093–T095整体验收未关闭；T096以后整合Demo、长跑、性能、新使用者和交付任务未进入。此前T027/T028、T042/T044、T051/T057/T058、T070/T071/T072全部保持未完成。
没有用本地夹具替代真实模型、Mem0语义、实际通知或可信已配置MCP验收。本批停止于T092；不暂存、提交或推送。
