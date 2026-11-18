# CLI、管理台与人工恢复契约

这是目标行为设计。当前已有help/version、init/status、正式SQLite装配的chat及serve/gateway运行宿主；普通chat可配置运行并恢复历史。serve/gateway现已启用文件调度，chat不自动调度；启停/立即执行、查询与人工恢复HTTP已完成本地验证；T090十二项CLI及T091/T092同源原生管理台已通过本地回归；第六批续作已通过T093–T095跨进程/HTTP/发行CLI/浏览器出口并关闭T073；T096及后续和既有真实联调缺口保留。
完整发行命令使用boot fat JAR；不要求修改全局alias、PATH、JAVA_HOME或ACSP设置。

## 启动与退出码

完整入口：java -jar oryxos-boot-1.0.0-SNAPSHOT.jar [--root <path>] <command>。
boot无参数等价serve，保留原HTTP启动用途。
OryxOsCli.main独立薄JAR保留无参数／--version输出；完整命令通过boot调用其命令解析器，
不能承诺把加入Picocli后的薄JAR直接当完整分发包。

根解析与配置见[configuration](configuration.md)。
--help、--version及轻命令不创建Spring上下文、不连接模型／MCP、不监听端口。
业务命令返回退出码，只有最外层main决定退出进程。

| 退出码 | 含义 |
| --- | --- |
| 0 | 操作成功；查询成功不表示后台功能已全部实现 |
| 2 | 参数、配置或缺少必要文件错误 |
| 3 | 资源不存在或不可用 |
| 4 | 会话／调度忙碌、身份或生命周期冲突 |
| 5 | 模型／工具任务失败或部分完成 |
| 6 | 执行超时；先查记录再决定重试 |
| 7 | 记录异常或执行暂停，需要维护者处理 |

JSON模式使用继承选项`--json`，查询/文件操作输出一个code/message/data/timestamp/traceId信封；chat每轮一个信封。执行投影采用HTTP的content、identity、recordingStatus和纠错标记；不包含私有continuation。日志写stderr，stdout保留给命令结果。人类可读模式不能省略“结果不确定／记录异常”。

轻命令的文件状态不等于运行健康。`tool list`运行时读取由实际注册表发布的`runtime/cli-view.json`：核对工作区UUID、进程PID/启动时间及仍被持有的进程锁；标记RUNNING_REGISTRY_SNAPSHOT，表示启动时注册、不是实时连通性探测。启动前清理旧投影，关闭时删除；缺失、异工作区、死亡进程的投影不会当作有效注册表。chat也发布同一投影，不另开HTTP监听。该文件是可丢弃诊断资料，不是配置来源。
离线工具描述来自生产工具的静态描述工厂和同一重试分类器，不构建网络/MCP客户端；available=false表示未建立运行时可用性，MCP=NOT_DISCOVERED。
`profile show`比较当前frontmatter/body版本与启动版本，分别显示MATCH、RESTART_REQUIRED、BODY_CHANGED、NOT_LOADED、SNAPSHOT_UNAVAILABLE或NOT_RUNNING；正文/Bootstrap在下一任务热读，frontmatter需要重启。
`session list`支持`--limit 1..200`及`--cursor <UUID>`，nextCursor为空表示末页；允许按已移出定义的profile查询历史。缺失/损坏数据库返回3/STORAGE_UNAVAILABLE，执行写入异常或暂停返回7。

## 十二项基础操作

| 命令 | 输入与行为 | 验证要点 |
| --- | --- | --- |
| init | 创建缺失目录、workspace UUID、runtime/Agent/Bootstrap模板 | 重复执行不覆盖用户文件；不发网络请求 |
| status | 显示根、所选backend、Agent有效性、进程及故障状态 | 无服务时明确未运行；不把文件存在等同于健康 |
| chat --profile <name> --user <id> [--session <id>] | stdin/stdout对话，channel=cli；/quit退出 | 不传Session则新建；续聊必须身份匹配；每次任务60秒 |
| serve | 启动HTTP、管理台和已配置调度 | 默认127.0.0.1:8080；一个工作区只能有一个运行进程 |
| gateway | 常驻模式，当前同样启用HTTP、调度与已支持入口 | 不据名称声称已提供入站IM适配；不额外重复注册调度 |
| profile list | 列出目录定义及解析状态 | 不读数据库Provider／Agent注册表 |
| profile create <name> | 创建一个Agent目录与AGENT.md模板 | 已存在拒绝；新增需重启加载 |
| profile show <name> | 显示脱敏配置与正文 | 不显示秘密值，提示当前文件与已加载快照是否不同 |
| profile delete <name> | 停止该工作区运行后，把定义移至agent-archive，不删历史 | 活跃运行时拒绝，避免正文热读中途失去文件；明确这是移出定义 |
| provider list | 列出文件配置的服务名、模型连接状态信息 | 无真实调用时只说已配置；不回显key |
| tool list [--profile <name>] | 服务运行时查注册工具；离线列静态工具并标MCP未发现 | 不为了轻命令启动MCP进程 |
| session list [--profile <name>] | 查询当前根数据库的会话摘要 | 只读连接或服务查询；数据库缺失／损坏明确错误，不新建空库 |

帮助/版本不算额外业务命令。人工恢复走下方HTTP／管理台控制入口，不扩展为配置CRUD。
chat、serve、gateway属于运行模式，互斥使用同一工作区进程锁；维护者已在本次评审修订中确认。
服务运行时通过管理台或HTTP发任务；再启动chat明确返回忙碌并提示上述入口，不能偷偷切换工作区。
轻量查询仍可使用；终端远程聊天模式留待后续独立需求。

## HTTP错误与页面表现

HTTP成功及错误统一为code/message/data/timestamp/traceId。
有执行发生时，失败响应须带执行快照或执行标识；参数错误在执行前返回错误详情。

| 场景 | HTTP／code | 页面与调用方行为 |
| --- | --- | --- |
| 空输入／无效参数 | 400 INVALID_INPUT | 指向问题字段，不启动任务 |
| 未知资源 | 404 NOT_FOUND | 明确不可用，不展示假结果 |
| 同Session或schedule忙碌 | 409 SESSION_BUSY/SCHEDULE_BUSY | 不排队、不自动重试 |
| 已归档／身份或模型不匹配 | 409 SESSION_ARCHIVED/SESSION_IDENTITY_MISMATCH/SESSION_MODEL_CHANGED | 新开会话或修正身份，不能隐式切换 |
| 无效Agent配置 | 409 AGENT_UNAVAILABLE | 显示安全配置错误 |
| 消息过大 | 413 MESSAGE_TOO_LARGE | 明确32768 UTF-8字节上限 |
| 引擎致命失败／迭代上限 | 422 EXECUTION_FAILED | 显示执行ID、已完成与未完成部分 |
| Provider故障 | 503 PROVIDER_UNAVAILABLE | 不自动换服务；错误脱敏 |
| 记录故障后的新请求 | 503 EXECUTION_PAUSED | 提示维护者处理，禁用运行按钮 |
| 动作已发生但记录失败 | 500 PARTIAL_RECORDING_FAILURE | 突出“部分完成／记录保存异常”，不能用普通失败诱导重发 |
| 手动任务到60秒 | 504 EXECUTION_TIMEOUT | 停止加载，显示执行ID；无自动重发；可查迟到结果 |
| 普通查询遇到存储故障 | 500 STORAGE_UNAVAILABLE | 展示错误，不能用空列表代替 |
| 循环结束仍有未解决工具错误 | 200，Execution.status=PARTIAL | 显示失败步骤与通知状态，不能渲染“全部成功” |
| 有副作用动作结果不确定 | 200，PARTIAL／SIDE_EFFECT_UNCERTAIN；已超时或记录故障优先采用对应错误 | 立即结束任务，提示先核查；不再调用模型／工具／重试，不能提供自动重发按钮 |
| 参数错误经引擎核实纠正且其余请求完成 | 200，SUCCEEDED且hasToolErrors=true、hasUnresolvedToolErrors=false | 显示“成功，过程中有已纠正的错误”，原错误及纠正关联仍可查看 |

客户端不得自动重试POST messages/invoke/run/recover；用户刷新页面只做GET查询。
退出网页或网络断开不宣称外部动作撤销；业务截止和闸门规则仍由服务端执行。

## 管理台范围

静态入口/admin/，通过同源/api/v1访问以下数据：
- Agent列表：有效性、配置版本、模型名、允许工具与通知目标名称；手动运行。
- 会话：按Agent/入口/用户筛选，分页历史，明确当前身份可续聊的会话；
  受信任维护者可只读检查其他入口历史，不将这描述为身份授权。
- 共享记忆：CORE和ARCHIVAL分开，标backend和“工作区共享”；无编辑、清空或后台自动抽取按钮。
- 定时任务：文件中的时间和时区、运行enabled、下次触发、历史；启停和立即执行。
- 执行详情：每次模型／工具尝试、结果、耗时、未知用量、截止和记录状态。
- 显示工作区时区；工具错误区分已解决／未解决，不能通过隐藏旧失败把结果变成成功。
- 运行故障：显示暂停原因、记录缺口、在途数、人工确认恢复入口。

用户标识保存在当前浏览器的设置中并随请求发送；显式显示当前值，不能暗称已登录。页面目前要求1–128位可见ASCII标识，避免浏览器原始HTTP头对Unicode的编码歧义；历史筛选仍可查询Unicode标识，不重写已有身份。
不提供Agent定义／Provider／白名单／通知渠道／cron的编辑页面。
页面使用真实API；没有数据时显示空态，加载时显示加载态，查询失败时显示错误态。
基础可用性：语义化表单、键盘可达、状态文字不只靠颜色；中文说明、代码标识保持英文；
模型／工具内容以文本呈现，不直接innerHTML，防止返回内容执行脚本。

## 人工恢复操作

1. GET /runtime/state显示PAUSED_RECORDING、incidentId和currentIncident；详情含受影响executionIds、
   knownActions、unknownActions、missingRecordSummary、evidenceSources、detailsComplete。
   detailsComplete=false时突出“信息不完整”，空数组不能显示成“没有发生动作”。
2. 维护者在系统外修复磁盘／权限／存储等原因，并核对可能已经送达的通知；修复本身不解锁。
3. 页面要求勾选“已核对缺失记录，不重放已发生动作”，填写简短核对说明。
4. POST /runtime/recover提交incidentId、acknowledgeMissingRecords=true、notes，
   X-Oryx-User-Id记录操作者标识；这是当前信任环境中的维护操作，不是假装具备认证。
5. 服务检查当前incident匹配、无未收尾本地调用、存储可写，提交恢复和缺口说明后开闸。
   维护者可确认确实无法补齐的缺口；不要求伪造detailsComplete=true才允许恢复。
   不满足返回409或503；失败不显示已恢复。重复确认同一已确认incident返回当前状态，不重做动作。
6. 页面刷新状态；只有READY才允许新运行。漏掉的定时任务不补跑，旧执行不重新启动。
   若历史不可重建，保留“记录缺失”而非伪造成功审计。

接口说明与Swagger UI置于同一受信任服务；默认关闭跨源。
无认证不是公开互联网部署许可；本轮不额外引入SSO或角色系统。

