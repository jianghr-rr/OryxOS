# US3 阶段审查与一致性检查

本地实现完成；**US3整体未验收，T058保持未完成**。本批只推进T045–T058，未进入US4/US5，未提交或推送。
完成T045–T050、T052–T056（11项）；T051/T057/T058与原有T027/T028/T042/T044未完成。
104项中已完成51项、待完成53项（7项前提/出口缺口，46项后续任务未实施）。

## speckit-analyze 只读检查

依技能运行 `check-prerequisites.sh --json --require-spec --require-tasks --include-tasks`，定位当前feature。
审查spec/plan/tasks、相关data-model/configuration/runtime/verification与宪章；分析过程只读。
文档修复在分析结束后依据用户既有授权单独完成，未改变产品行为、任务依赖或宪章。

| ID | 类别/级别 | 初次位置/问题 | 修复后复查 |
| --- | --- | --- | --- |
| I1 | 状态不一致 / MEDIUM | spec:9、plan:9/15/92/201、tasks:9/17、quickstart:3/56及CLAUDE/tools README仍显示US2、US3未实施或Memory端口缺失 | 统一写本地完成/真实验收缺口；默认chat只缺正式Session/Execution存储；历史US2证据保持历史含义 |
| I2 | 测试追踪遗漏 / MEDIUM | verification分组表漏列Mem0ProtocolContractTest，当前分组未列real-mem0 | 补列实际类与real入口；test-groups.json登记20个US3本地方法和1个真实方法；全部与源码对应 |
| I3 | 依赖说明遗漏 / LOW | plan生产DAG说明未区分新加的memory→storage测试依赖 | 说明仅RealMem0IT使用test scope；生产memory主源码仍仅通过core MemoryRecordStore访问，DAG无环 |

修复后未发现本批范围内CRITICAL/HIGH问题；无宪章冲突。未删除真实验收门禁，也不把缺凭证解释为通过。
本次文档检查不替代代码和行为验证，后者见下一节及[US3-local](US3-local.md)。

| 要求/场景 | 对应任务 | 验证状态 |
| --- | --- | --- |
| FR-010–012、US3-A1/A2/A5、RV-02/03 | T045–T047、T054、T056；正式存储T075/T093 | 身份/快照/完整turn/归档本地通过；进程持久恢复待US5 |
| FR-013–016、US3-A3/A4 | T046/T048–T050/T054–T056 | 真实MD/SQLite、本地CORE/归档及并发通过 |
| FR-017、US3-A6 | T049、T051–T057 | Mem0协议/账本本地通过；镜像/语义/服务重启待真实验收 |
| FR-018、US3-A7 | T049/T050、T075/T093 | 记忆账本关闭重开通过，正式Session跨进程证据未交付 |
| FR-019/020、FR-028/029/033、RV-01 | T051–T055及既有ToolExecutor | 单一入口、原文外发范围、截止/UNKNOWN/无重试本地通过 |
| SC-001、SC-004 | T056–T058、后续整合任务 | 本地可执行证据已给，真实门禁未关闭 |

全工件引用核对：41个FR、11个SC均在104项任务中有引用；任务ID唯一，无US3孤立任务。
US3六个当前场景有本地覆盖，A3/A6的真实Mem0部分仍待T057；第七场景按规格明确留T093。
未发现需改变已确认产品行为、宪章或新增范围的冲突。

## 代码审查与行为验证

- Session策略仅在既有SessionManager/HistorySelector中；acquire/archive/lease关闭共用同步边界。
  归档后不可续聊；ONE_SHOT等迟到工作与最终记录退出；归档异常使执行记录INCOMPLETE并关闭准入，调用仍正常结束。
- memory生产代码不导入JPA/storage；V002和两个实体/适配器归storage，每次新事务读取。
  已确认来源与远端映射同事务提交，workspace/backend/scope决定读取范围，sourceAgent仅供追踪。
- MD格式损坏不当空文件；锁/临时文件/fsync/原子替换防丢写；越界symlink拒绝。
  Unicode完整折叠仅用于字面检索；保存原文、CORE全量、归档code-point边界符合契约。
- Mem0每个POST/GET都重新经过同一执行许可；SDK不自动重试或跟随重定向。
  POST只发送单次显式保存内容；CORE不走远端；空结果、缺ID、归属异常、超时和回读错误不报成功。
  已发POST后回读401仍UNKNOWN；未确认的本地来源不进入上下文/召回。
- real测试核对个人Compose标签、源码commit、镜像ID和服务模型配置；真实重启需显式开关。
  远端投影保存供人工对照，自动比较返回原文不能冒充语义真实性证明。
- 临时Session/Execution Store继续仅供测试，EmptyMemoryFacade已移出装配；主JAR不包含测试替身。
  默认chat保持NOT_READY，未提前实现US5存储/管理入口。

最终本地 `clean verify`：81项/23类全部通过，0失败/错误/跳过；5组真实JVM/CLI基础黑盒回归通过。
记录失败/截止/ONE_SHOT、MD腐败保护与凭证边界均有实际行为回归，不只检查方法存在。
真实Mem0前检因缺工作区配置失败，0跳过、0模型/服务调用；Docker预检失败，未构建或启动容器。

## 环境与工件保护

US3开始前376个已有文件hash基线用于核对改动；改动集中在本批实现、测试、工具入口与相关阶段文档。
宪章、requirements checklist、父POM、V001及其他无关文件保留；没有改写此前US1/US2历史证据。
官方实验/全局保护快照2416项与ACSP根配置11项hash复查均无变化，证据
`.oryxos-dev/evidence/us3-environment-comparison.json`；这不是对未纳入快照目录的全量审计声明。
源码下载/依赖解析/构建缓存仅落个人项目`.oryxos-dev`，服务使用独立Compose名称/卷，不启用全局配置或ACSP工具链。
`.specify/extensions.yml`不存在，无before/after hook；任务状态只勾本批完成项，清单标记只读。

未完成事项和具体前提见[US3-real-mem0](US3-real-mem0.md)。本批到此停止。
