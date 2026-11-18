# US5 第四批范围与一致性审查

2026-11-13。**T090完成，本批停止。** 完整104项任务现为79完成、25未完成，仅将T090由未完成改为完成；T073和T091–T104未勾选，原有十项真实前提/出口缺口不变。

## 任务与依据

| 任务 | 结论 |
| --- | --- |
| T090 / FR-030 / US5-A8 | 十二操作实现并测试；文件权威、create不覆盖、仅停机归档且保留历史、show脱敏/快照差异、轻查询不启动运行时、离线MCP标识、JSON及退出码均有证据 |
| T073 | CLI子集已补，本项整体仍未完成；本地15测试不替代完整浏览器与跨入口整体验收 |
| T091以后 | 未进入管理台、后续整合或正式真实验收；旧课程指南的整体更新仍属于T102，当前操作以quickstart及CLI契约为准 |
| 此前真实缺口 | T027/T028、T042/T044、T051/T057/T058、T070/T071/T072全部保留 |

## speckit-analyze只读检查

检查前运行`check-prerequisites.sh --json --require-spec --require-tasks --include-tasks`；核对spec、plan、tasks、宪章、data-model与契约。分析阶段只读，随后依据用户已授权的“更新任务与证据”维护当前状态。

| ID | 类别/级别 | 发现与处理 |
| --- | --- | --- |
| I1 | 状态漂移/MEDIUM | 当前状态仍写完整CLI未实施、status还写存储未实现；更新当前状态及命令说明，历史批次证据原样保留 |
| I2 | 输出语义/MEDIUM | 只读坏库与执行记录故障原先同映射7；区分3和7，公开执行content/identity/纠错字段与HTTP mapper核对 |
| I3 | 截止边界/MEDIUM | CLI仅在引擎返回Handle后等待可能遗漏接收和最终记录阻塞；入口计时、观察Handle并在到期封闭许可，新增真实60秒与适配边界测试 |
| I4 | 运行快照/LOW | 轻命令需要实际工具注册信息且不能启动MCP；新增绑定活跃进程的脱敏诊断投影，并明确其不是配置或实时健康来源 |

52个要求（41 FR、11 SC）全部有任务映射，104任务ID唯一；T090前置T089/T081已完成。无本批未解决CRITICAL/HIGH冲突、无宪章修改或验收标准降低；requirements清单16/16未修改。需求映射不表示后续真实/浏览器出口已通过。
`.specify/extensions.yml`在实施前、分析与完成前检查均不存在，无扩展钩子需要执行。

## 实现与回归核对

- boot注入core QueryPort轻查询适配，cli不反向依赖boot/storage。channel-cli增加Boot管理的Jackson序列化依赖，core保持无Jackson/Spring。
- SQLite/JPA正式Session、Execution和Audit装配不变；只读列表不建库、不做迁移、不把坏库变成空列表。
- Profile删除持锁移动目录，保留原数据库及历史；正文和Bootstrap按次读取，配置重启加载。无新增恢复CLI开关。
- tool静态描述与生产描述/重试分类共享；实际MCP只读查询不增加协议调用。运行快照验证workspace/PID/启动时间/锁并清理旧文件，缺失时明确不可用。
- chat/serve/gateway共用排他运行锁；serve/gateway仍装配HTTP和调度，chat不自动调度。无参Boot=serve，薄CLI无参/版本兼容。
- JSON stdout与日志stderr分离；公开数据不含私有opaque，UNKNOWN/记录异常与纠正标记不隐藏。真实60秒截止和接收/收尾阻塞均验证。
- 全量166测试/35类及本批15项通过，0失败/错误/跳过；Python6项、打包foundation5组通过。详情与run ID见[本地证据](US5-fourth-batch-local.md)。

## 保留与交付边界

保存初始524个文件SHA256，结束核对无删除已有文件；既有迁移、宪章、requirements清单、前几批和真实验收证据、website与无关配置保持不变。仅channel-cli POM增加序列化依赖，其他父/子POM保持本批开始时状态。
构建缓存、用户目录、临时工作区和日志在项目`.oryxos-dev`；已安装JDK只读复用，不执行官方课程入口、不更改全局或ACSP。没有做全机外部目录快照，不声称逐文件证明未纳入范围的目录状态。
详细保护清单和差异见`.oryxos-dev/evidence/us5-batch4-consistency.json`。git diff --check通过，未暂存、提交或推送。本批停止，不自动推进T091。
