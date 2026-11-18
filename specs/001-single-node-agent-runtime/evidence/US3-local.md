# US3 本地实现与验证

本批范围 T045–T058；完成 T045–T050、T052–T056，共11项。T051只完成源码/依赖/基础镜像锁与服务准备，
应用镜像未构建验证；T057/T058未完成。T027/T028/T042/T044原样保留未完成。不进入US4/US5，不提交或推送。

## 执行环境与结果

个人 checkout：`/Users/jianghaoran/github/OryxOS`。JDK21.0.13+9只读复用
`/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home`；
Maven3.9.16、配置/缓存/home/tmp/证据全部通过本项目 `tools/dev` 隔离。
Spring Boot3.5.16、Spring AI1.1.8、Hibernate6.6.53.Final和SQLite实际版本以POM/本次XML为准，没有升级既定框架。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
cd /Users/jianghaoran/github/OryxOS
./tools/dev/mvnw -B -ntp clean verify
./tools/dev/mvnw -B -ntp test -Dgroups=us3-memory
python3 tools/dev/test_build_environment.py
python3 tools/dev/test_report_gate.py
python3 tools/dev/verify-foundation.py
```

| 检查 | 实际结果与证据 |
| --- | --- |
| 全量 clean verify | **81测试/23类，0失败、0错误、0跳过**；runId `70435b64-ec13-488e-8eaa-89c59ae75224`；`.oryxos-dev/us3-verify-final.log` |
| US3本地组 | 20项纳入上述全量；5项Session、6项MD/门面、6项Mem0协议、3项Boot/SQLite整合 |
| 测试发现门禁 | 93项必跑登记匹配（deadline-boundary与us2-loop重叠12项），实际81项各执行一次；不是93个独立测试 |
| 环境隔离 | `test_build_environment.py` 5项通过；普通构建无凭证继承，real-mem0仅允许MEM0_API_KEY，不传模型服务/ACSP密钥 |
| 报告有效性 | `test_report_gate.py` 1项通过；缺失、跳过或旧runId不能通过 |
| F0黑盒回归 | 5组通过：轻命令、重复init/status、真实HTTP进程与第二进程/chat拒绝、无参数serve及根优先级、损坏SQLite保留原文件与诊断进程；`.oryxos-dev/us3-foundation.log` |
| 发行边界 | 所有主JAR不含TransientSessionStore/TransientExecutionStore/EmptyMemoryFacade/ScriptedModel；memory主源码不引用storage |
| Mem0服务准备 | 源码与锁校验通过，Compose config --quiet通过（仅占位值做语法检查）；Docker daemon不可用，详见US3-real-mem0 |

完整本地XML和summary已归档到 `.oryxos-dev/evidence/us3-full-verify/`，后续real前检会清理选中模块的target报告，不能据混合报告重算本轮全量。
早期 `us3-matrix.log` / `us3-matrix-2.log` 为16/17项阶段测试，最终必跑清单已增至20项。
首次补充路径保护测试因fixture重复创建已有目录报错，已改为幂等创建；该失败不当作实现通过，最终全量重新执行成功。

T045行为红灯基线：`.oryxos-dev/us3-red.log` 中Session归档/快照行为失败，
`.oryxos-dev/us3-memory-red.log` 中MD后端NOT_IMPLEMENTED失败；均编译成功，未使用编译失败或skip替代行为红灯。
之后逐步加入MD、Mem0协议、SQLite/Boot与收尾回归。

## 验收覆盖与实际数据流

| 场景/风险 | 本地证据 | 边界 |
| --- | --- | --- |
| US3-A1、RV-02 | MemoryService只调用一次SessionHistoryPort；复用US2完整turn选择/本turn排除；真实AgentService下一轮看到保存事实 | Provider为可控脚本，SessionStore测试专用 |
| US3-A2、RV-03 | 同组合可多Session，channel/user/Agent与创建模型不符拒绝，拒绝前0新增模型调用；另一Agent/用户新Session读取共享事实而无旧历史 | 不等于身份认证/长期记忆用户隔离 |
| US3-A3 | 真实MD与SQLite原文写后读；20个并发确认追加全部保留；文件重开/JPA工厂关闭重开后仍在 | 真实Mem0服务与正式Session进程重启另验 |
| US3-A4 | CORE超过归档预算仍全量；ARCHIVAL按Unicode code points裁剪且不破坏emoji；NFC+完整case folding匹配Straße/STRASSE和组合音符；%/_为字面字符；CORE不进入recall | 归档裁剪只影响注入，不删除原文 |
| US3-A5 | 无活跃执行才可归档；归档后拒绝追加，保留历史；ONE_SHOT遇截止仍等迟到工具、最终记录结束才归档；归档失败完成调用并暂停执行闸门 | 无正式Session持久库 |
| US3-A6 | MD/SQLite独立数据集；Mem0 CORE仅本地不发请求；POST只含本次显式内容，所有远端ID逐一GET归属回读后才确认 | 假Mem0语义响应是固定fixture，不能证明真实同义检索 |
| RV-01、FR-033 | 空结果/缺ID/外工作区/断连/真实HTTP读取超时/回读401不报成功；UNKNOWN保留来源，后续0重试；许可关闭后0GET | 超时请求可能已生效，不自动重放 |
| FR-028 | API key不进入业务消息/普通日志；已注册凭证不能保存为记忆；普通URL事实可原文保存；symlink越界拒绝，损坏MD原字节保留 | 应用层检查，不宣称操作系统强隔离 |
| US3-A7 | 保留创建时provider/model非空不可改绑契约 | 正式Session跨进程恢复仍由T075/T093验证，未关闭 |

统一入口是ReActLoop → ToolExecutor → save_memory/recall_memory；工具注册在boot，存储实现不调度工具。
save_memory默认ARCHIVAL，未知写入映射到统一UNKNOWN/停止规则。MemoryService的load只注入已确认来源，
不会把PREPARED/FAILED/UNKNOWN当事实，不会自动收集普通对话。

MD采用同工作区锁、同目录临时文件、文件fsync、原子替换、目录fsync；尝试发布后无法确认时返回UNKNOWN。
文件含版本/工作区/条目边界/原文checksum，损坏时报错，不重建或覆盖。
SQLite以V002迁移新增memory_entries与memory_remote_links；每次查询新EntityManager/短事务，无CORE LIMIT。
Mem0远端调用之间不持有JPA事务；本地原文和远端投影状态分开，收据始终说明semanticCoverage=NOT_PROVEN。

默认发行已装配所选真实Memory后端；正式SessionStore/ExecutionStore尚属US5，因此chat仍明确NOT_READY。
测试专用临时Store保留，早期EmptyMemoryFacade已从测试装配移除。没有把临时Store带入生产，也未提前实现US5。
