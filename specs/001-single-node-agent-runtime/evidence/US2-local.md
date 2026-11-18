# US2 本地实现与验证

日期：2026-11-12。范围：T029–T041、T043。用户仅授权本批US2；不进入US3/US4，不提交或推送。

## 交付行为

- AgentService是CLI及后续入口的公共执行端口；顺序ReAct保留assistant/tool-call ID配对，直答停止、迭代耗尽停止新增动作。
- SessionManager负责完整身份与创建Provider/model快照、非排队占用、完整turn历史裁剪；本轮历史只注入一次。配置快照不热改，正文与Bootstrap每轮重读，工作区时间使用Asia/Shanghai。
- ToolRegistry进行离线Schema校验；Agent工具子集与资源授权均通过后，ToolExecutor预记STARTED、重新检查许可、顺序执行并记录成功/失败/拒绝。
- 最小http_get限定scheme/host/port、域名标签和每次重定向，最多3跳，返回体最多64KiB，连接不超过5秒，读取受配置与剩余截止约束。底层自动重试关闭；显式安全暂态最多额外3次，间隔250/500/1000ms。
- UNKNOWN立即终止本次执行，同轮余项补NOT_EXECUTED；受控参数纠正保留旧ERROR并增加关联。不可证明的纠正、拒绝、多失败保持PARTIAL。
- 手动预算60秒从入口计时；截止返回时关闭动作许可，在途未退出保持会话忙碌；迟到只补事实，记录失败暂停工作区。

默认发行缺正式SessionStore/ExecutionStore/MemoryPort，chat返回RUNTIME_NOT_READY（退出7）。测试使用真实SessionManager及测试源码中的临时Store/空长期记忆；不包含生产降级、不声称数据库会话或审计已完成。工具审计为结构化日志。

## 可复现命令与结果

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp clean verify
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp test -Dgroups=us2-loop
/Users/jianghaoran/github/OryxOS/tools/dev/mvnw -B -ntp test -Dgroups=deadline-boundary
python3 /Users/jianghaoran/github/OryxOS/tools/dev/verify-foundation.py
python3 /Users/jianghaoran/github/OryxOS/tools/dev/test_report_gate.py
python3 /Users/jianghaoran/github/OryxOS/tools/dev/test_build_environment.py
```

| 证据 | 结果 |
| --- | --- |
| 实现前循环基线 `.oryxos-dev/us2-red.log` | 7个行为断言失败；编译通过、0跳过，没有用编译失败当红灯 |
| 边界补测 `.oryxos-dev/us2-boundary-red.log` | 17个用例中1个失败：Session创建存储故障未暂停工作区；修复后通过 |
| 迟到保存失败回归 `.oryxos-dev/us2-late-record-red.log` | 12项中1项发现记录失败先于截止回调时丢失DEADLINE；修复并补查回调先后两种顺序后全量通过 |
| `test -Dgroups=us2-loop` | 28项必跑通过；runId `18e72eea-9ee7-4822-a538-e5be1e1d7b17` |
| `clean verify` | 九模块通过；19类、61个实际用例，0失败/错误/跳过；最终runId `ca613d11-d23e-4a53-80dd-0c4b044c9375`（最终日志`.oryxos-dev/us2-final-verify.log`） |
| deadline-boundary | 两类12项，已包含在上述实际执行中；同一类同时属于us2-loop，不重复执行 |
| F0发行黑盒回归 | 5组通过：轻命令、幂等init/根定位、进程互斥、无参数serve、坏DB诊断与锁释放 |
| 报告门禁/环境隔离 | Python分别1项、4项通过；普通构建无凭证，real仅传两个明确环境变量 |
| 发行内容 | fat JAR中无TransientSessionStore、TransientExecutionStore、EmptyMemoryFacade、EarlyRuntimeConfiguration或真实测试类 |

全量门禁输出“73 required cases”是分组成员计数（deadline-boundary的12项与us2-loop重叠）；Surefire XML实际执行61项，非73次运行。原始日志和本次XML位于忽略目录`.oryxos-dev/`与各模块`target/surefire-reports/`，后续命令会刷新报告。

## 六个故事场景

| 场景 | 实测入口与观察 |
| --- | --- |
| US2-A1 天气多轮 | `EarlyCliLoopIntegrationTest`调用生产`OryxOsApplication.execute`→Picocli→Boot→CLI适配→AgentService；实际Spring AI适配连接回环模型，实际http_get请求回环天气。首turn两次模型+一次天气，续聊再一次模型；1 Session、2 Execution、6条模型日志事件+2条工具日志事件 |
| US2-A2 直答停止 | `ReActContractTest.directReplyStopsWithoutToolIo`：1模型、0工具 |
| US2-A3 顺序工具 | first后second，call ID和结果配对，历史及本轮消息无重复 |
| US2-A4 失败与纠正 | 参数、未知工具、资源拒绝均有失败事实；纠正链、有效目标变更、多失败和不可解析参数按规则处理 |
| US2-A5 上限 | 最后一轮提议补NOT_EXECUTED，无新增工具IO，终态ITERATION_LIMIT并说明未完成 |
| US2-A6 上下文 | 下一轮读取变更后的正文/Bootstrap；frontmatter变更不替换快照；UTC主机跨午夜仍按上海时间；缺失/超限/秘密别名在模型前拒绝 |

可选参考与脚本在本阶段通过受控工具输出夹具验证“按需引入/只引入输出”，没有实际执行Shell或宣称真实文件工具通过；真实文件/Shell/MCP/通知留US4。实际HTTP 60秒响应、持久化恢复、长期记忆及性能验收留后续任务。

真实DeepSeek/Kimi工具循环未执行成功；见[前提与缺口](US2-real-tools.md)，不以本地结果替代。
