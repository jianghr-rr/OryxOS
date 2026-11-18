# US2 批次核对与剩余门禁

2026-11-12。按speckit-implement完成本批本地工作，并按仓库要求执行speckit-analyze只读检查、代码边界审查与行为验证。没有修改已确认产品行为或宪章。

## 状态

- T029–T041、T043完成：本批14项；全清单共40/104完成。
- T027、T028保持未完成；T042仅具备真实测试入口，缺私有工作区/凭证/模型前提，前检失败；T044依赖T042，未勾选。
- T045以后全部未实施。本批结束即停止，不提交或推送。

## 一致性检查

只读命令：`.specify/scripts/bash/check-prerequisites.sh --json --require-spec --require-tasks --include-tasks`。
读取spec/plan/tasks/constitution和受影响契约，不在analyze阶段修改文件；按用户已有授权另行修正文档状态，随后复查。

| ID | 级别 | 发现与处理 | 复查 |
| --- | --- | --- | --- |
| I1 | MEDIUM | spec/plan/tasks/quickstart仍写US2未实施；同步为US2本地完成、真实出口待完成 | 已消除当前状态冲突；历史F0/US1证据保留 |
| I2 | MEDIUM | CLI契约及状态日志仍称执行链未实现；区分已实现引擎与默认正式端口未齐 | CLI输出NOT_READY，API仍NOT_IMPLEMENTED；默认发行不注入测试Store |
| I3 | LOW | HTTP工具底层关闭重试的实现选择缺研究记录 | research R10、plan补记与实际依赖/行为一致 |
| E1 | 已知验收缺口 | T027/T042缺真实证据，T028/T044依赖它们 | 四项保持未勾选；不阻碍独立本地实现，不构成已完成整体出口 |

需求覆盖：41项FR+11项SC=52项，均有任务映射（100%）；104项任务均有依据；新增歧义0、重复要求0、CRITICAL 0。全范围覆盖表示有计划任务，不表示全部实现。

| 要求/场景 | 实现与证据 |
| --- | --- |
| FR-006–009、US2-A1–A6 | T029–T041；US2-local六场景；参考/脚本实IO留US4 |
| FR-010/011、RV-02/03基础 | T034/T035/T037；SessionRuntimeContractTest与CLI续聊 |
| FR-019/020/023/027 | T031–T033/T038；ToolSafetyContractTest、FailurePolicyContractTest |
| FR-033/038–040、RV-01/06 | T032/T037–T039/T043；DeadlineRaceTest；US5持久化/真实HTTP仍待验 |
| FR-004、SC-002 | T027/T042仍未完成，未用回环模型替代 |

没有宪章对齐问题或无映射任务。没有需要改变产品决定的修复。

## 代码与行为审查

检查Provider/Tool唯一调用路径、先授权后IO、STARTED后重新检查许可、重试/重定向次数、UNKNOWN终止、历史完整性、迟到状态和记录失败。边界测试发现Session首次创建存储失败未暂停工作区，已修复并由失败基线转绿；同时把工具映射构造移到取得会话占用之前，避免构造失败遗留占用，截止状态检查与更新同步化，避免成功收尾与超时标记竞争。末轮审查还发现迟到保存失败状态与data-model既定转换不符，且故障先于截止回调时会丢失DEADLINE；已按原状态图修复为PARTIAL_RECORDING_FAILURE并保留截止原因，补查回调先后两种顺序和初始执行写入失败后全量复跑通过。

全量clean verify九模块通过，19类61个实际用例0失败/错误/跳过；US2组28项通过。F0黑盒5组及报告/环境隔离检查通过。临时Store/早期配置未打入发行JAR。详细命令、runId及边界见[US2-local](US2-local.md)、[RV01-RV06-loop](RV01-RV06-loop.md)。

宪章、requirements checklist、工程骨架及无关改动对照本轮基线保持；本轮修改的已有文件均为US2接入、测试入口或对应文档。官方实验仓库受跟踪文件/全局配置2416项、ACSP根配置11项与保护快照哈希一致。个人Maven/临时目录留本checkout的`.oryxos-dev`；JDK只读复用，未调用课程或ACSP构建入口。

`.specify/extensions.yml`不存在，没有前后置hooks；不会由技能自动触发提交。

## 后续边界

本地T041/T043已满足US3/US4独立开发前置，但本批不启动。下一批若继续US3，应按T045–T058完善会话生命周期、长期记忆门面及md/sqlite/Mem0三后端；真实Mem0缺前提仍不能关闭验收。US4工具扩展和US5正式存储/HTTP/恢复保持原任务范围。
