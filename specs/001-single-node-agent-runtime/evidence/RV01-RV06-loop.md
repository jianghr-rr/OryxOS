# US2 未知结果、纠正与截止证据

2026-11-12，L1可控组件与L2回环IO；命令与runId见[US2-local](US2-local.md)。`deadline-boundary`登记FailurePolicyContractTest 6项及DeadlineRaceTest 6项，均在全量回归中通过。

| 约束 | 实际断言 |
| --- | --- |
| RV-01 UNKNOWN立即终止 | 模型脚本预置下一轮以新call ID重发；实际模型1次、副作用工具1次，下一轮未消费；同轮余项NOT_EXECUTED，无自动重试；工作区未因单独UNKNOWN暂停 |
| RV-06 可证明纠正 | 仅单一同名、可解析INVALID_ARGUMENTS、无副作用且只改原错误字段的相邻链可解决；旧ERROR和Session工具结果不改，增加correctionOf/resolvedBy关联；成功保留hasToolErrors与纠正提示 |
| 无法关联 | 改变原本有效target、权限拒绝、未知工具、多失败、不可解析参数均保留未解决状态，模型称完成仍PARTIAL |
| 安全重试 | 暂态失败后成功保留失败attempt及RESOLVED事实；503最多4次实际GET，退避250/500/1000ms；401不重试，UNKNOWN不重试 |
| STARTED提交竞争 | 审计STARTED期间推进可控时钟到60秒，之后实际工具调用0，记录NOT_EXECUTED；permit关闭后内部子请求拒绝 |
| 返回截止与忙碌 | 工具已进入后阻塞；时钟到60秒，Handle返回TIMED_OUT/DEADLINE且recordingStatus=UNKNOWN，在途计数>0，同Session仍SESSION_BUSY；释放工具后记录迟到SUCCESS，余项NOT_EXECUTED，模型仍1次，终态不回成功 |
| 记录故障 | STARTED保存失败时0工具IO，工作区PAUSED_RECORDING，其他已存在执行也不能取得新动作许可；首次Session存储失败同样暂停；初始Execution写入失败为FAILED/INCOMPLETE，0模型/工具IO |
| 两种事实共存 | 截止后记录失败按既定状态图转PARTIAL_RECORDING_FAILURE，保留stopReason=DEADLINE与recordingStatus=INCOMPLETE；已返回的TIMED_OUT快照不变。覆盖记录故障先于截止回调及回调先触发两种顺序，不声称动作撤销 |
| RV-02/RV-03基础 | 20个完整turn选择、排除当前、不删历史、可配置数量；空Session同样校验身份和创建模型快照；同三元组允许多个Session |
| RV-06时间 | 固定UTC 2026-11-12 16:00:01，提示词为Asia/Shanghai 2026-11-13，不读取宿主默认时区 |

这些证据不等于真实HTTP 504/60秒、所有记录故障注入、数据库重启恢复或长跑验收。后续US5仍按原任务验证。实际副作用无法由超时撤销；返回截止和在途收尾分别记录。
