# US5 第六批续作一致性审查

2026-11-13。**T094、T095及T073已完成；T093再次回归通过。104项任务现为85完成、19未完成。** 本批止于T095，未提交或推送。初次第六批的失败审查原样另存[初次审查](US5-sixth-batch-initial-review.md)，185项差异及本轮新增的19处操作ID差异均保留。

## 逐项结论

| 任务 | 签收依据 |
| --- | --- |
| T093 | 3项独立发行JAR/HTTP/CLI/SQLite跨进程测试再次通过；正式notify接收一次后的记录/fault双故障、旧run保留、修复/重启仍暂停、确认不重放不变。 |
| T094 | 真实/v3/api-docs为19操作/44schema，逐字段语义、状态集合、参数、请求体必填和operationId对照通过；原实际60秒、错误/分页/身份/调度/重载测试全部通过。 |
| T095 | 发行JAR十二操作、全部退出码、serve/chat互斥且HTTP可用、移出定义保留历史通过；Chrome13组及人工键盘恢复通过，API与DB一致，新截图已保存。 |
| T073 | 先有缺能力行为失败基线，后续19操作/44schema、存储/故障/重启和全部本地出口复跑通过，故关闭。 |
| 未完成范围 | T027/T028、T042/T044、T051/T057/T058、T070/T071/T072，以及T096–T104，全部保持未勾选。 |

最终全量176个唯一测试/39类，0失败/错误/跳过；run ID da2432a0-7d17-49db-8b9e-332df150152b。266个登记引用含跨组重叠，不能当作唯一测试数量。Python6项、发行宿主5项也通过。
[汇总与命令](US5-sixth-batch-continuation.md)、[机器可读摘要](US5-sixth-summary.json)、[HTTP](US5-http.md)、[CLI与浏览器](US5-cli-admin.md)。

## 发现与处理

| ID | 结论 |
| --- | --- |
| A1 发布schema缺失/约束缺失 | 已解决。控制器响应元数据、MVC请求绑定及实际DTO record/Swagger约束生成文档；泛型ApiResponse复用，内联工具请求不增加额外组件。运行时不读取静态设计JSON，既定契约哈希未改。 |
| A2 OpenAPI版本 | 已解决。固定3.0.3与3.0 nullable序列化；所有44schema的类型、required、nullable、enum、边界、数组/引用通过原严格比较。 |
| A3 操作ID未被原断言覆盖 | 复查发现后先新增严格断言，取得19处可运行失败，再由真实路由生成稳定ID。新增响应状态集合及请求体必填检查。最后重新构建JAR并全量复跑；没有把中间绿色结果当最终证明。 |
| A4 CLI/browser旧证据不足 | 新增发行JAR黑盒3项及本批浏览器记录。实际SQLite故障验证启停/恢复失败不先显示成功，恢复失败消耗确认，无自动重试。第五批截图未重命名充当本批。 |
| A5 文档状态漂移 | 当前tasks/spec/plan/quickstart/verification/CLI契约说明/CLAUDE/tools/dev README同步；前五批与第六批初次失败记录保留其历史含义。T094原计划HttpDeadlineIT路径同步为实际HttpApiContractTest/PublishedOpenApiTest及运行端metadata目录。 |

OpenAPI修复没有改变引擎、存储、调度、CLI业务逻辑或管理台生产静态文件；最后一次变更仅为运行文档操作ID生成及加强断言。浏览器证据仍对应同一生产页面/业务后端，最终JAR全量再次通过。

## 保留与边界

本轮开始记录475个Git可见文件SHA256；无原文件删除。308个保护项（宪章/技能/清单、原HTTP设计契约、data-model/research、website、全部POM、引擎/Provider/Memory/Tool/Storage/CLI源码与既有测试、管理台生产静态资源、tools/dev隔离实现）哈希不变。其余变更限运行端OpenAPI元数据、验收测试/测试夹具、登记与当前进度证据；完整列表见项目 .oryxos-dev/evidence/us5-batch6-cont-consistency.json。
104任务ID唯一，52个FR/SC均有任务映射，登记测试类/方法均存在；requirements清单仍16/16且未修改。证据JSON通过秘密/私有模型字段canary检查。

最终JAR SHA256：a21dbfa552c75e094a49230c81e850a556d66da090667b9c7a2ff32d0db8032d，**与最终全量测试前使用的JAR逐字节一致**。包检查确认有正式admin资源，没有测试宿主/测试页/故障辅助端点/设计契约JSON/本地秘密文件。原始XML/日志/实际文档均按运行归档。
构建与验收沿用tools/dev，JDK仅只读复用；没有改官方实验仓库、全局环境或ACSP配置。自建浏览器测试服务及测试子进程已停止；没有全机快照，不夸大保护核验范围。

本地模型和通知接收器是明确标记的协议夹具，真实模型、Mem0语义、通知接收与可信MCP缺口不被模拟验证替代。T096之后的三场景、真实业务、长跑和新使用者验收均未进入。
git diff --check通过；无暂存、提交或推送。.specify/extensions.yml前置及完成前均不存在，无扩展钩子。
