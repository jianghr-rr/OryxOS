# 配置与权威数据源契约

所有示例是未来运行数据，不是仓库当前已有配置；本轮不创建实际工作区或秘密。
依据[spec](../spec.md) FR-002、003、009、020、026、028、AS-03。

## 工作区与读取顺序

所有入口先解析根：CLI --root → JVM -Doryxos.root → ORYXOS_ROOT → 当前目录/.oryxos。
转成绝对规范路径后创建WorkspaceHandle，数据库固定为root/oryxos.db；
不另接受一个能把数据库指向其他工作区的业务配置。Boot应用参数必须沿用这一解析结果，
不得再用Spring默认配置覆盖成另一个根。

~~~text
<root>/
  workspace.json                 # schemaVersion、稳定workspace UUID
  runtime.yaml                   # Provider、工具资源规则、memory backend
  secrets.local.yaml             # 可选；只允许被secret引用，禁止普通查询/工具读取
  mcp_servers.yaml               # 本地stdio服务器定义
  agents/<name>/AGENT.md         # 配置区+正文
  agents/<name>/skills/*.md      # 可选子指令
  agents/<name>/scripts/*        # 可选受信任脚本
  AGENTS.md / SOUL.md / USER.md  # 运行时公共设定，不是仓库开发指令
  memory/MEMORY.md               # md backend
  runtime/                      # 进程锁、runs/<runId>.json、故障标记
  output/                       # 示例工具输出目录，必须显式授权
  oryxos.db                     # 会话、审计、调度状态、sqlite/mem0本地账本
~~~

runtime.yaml是业务配置权威，应用内application.yml仅承载服务器/装配默认值。
单个值中的 ${NAME} 先取该进程环境变量，再取secrets.local.yaml的同名项；缺失报错，
不替换为空。禁止任意表达式求值或递归变量替换。
普通配置字段不因环境中存在同名变量被悄悄覆盖；只在明确占位符处注入。
占位符解析按定义隔离：一个Provider缺密钥只标记该Provider及其引用Agent不可用；
一个Agent通知目标缺密钥只影响该Agent；一个MCP定义缺密钥只影响引用它的Agent。
不得在遍历整份runtime.yaml时因首个局部秘密缺失就使所有Provider失效。

YAML使用安全构造、拒绝重复键／自定义tag／过量alias，输入≤1MiB、嵌套≤64。
配置区与正文必须分开解析；API只返回脱敏配置视图和加载版本。
密钥字段仅接受引用，不接受可被提交示例携带的真实值。

## Agent 定义

文件名固定AGENT.md，头部frontmatter用 --- 分隔，正文是指令唯一来源。
不另建profiles/*.yaml，不使用旧identity.prompt再提供第二份任务正文。

~~~yaml
---
schema_version: 1
name: daily-weather
description: 每日天气和穿衣建议
provider:
  name: deepseek
  model: deepseek-flash
tools: [http_get, notify]
mcp_servers: []
bootstrap: [AGENTS.md, SOUL.md, USER.md]
notify_channels:
  daily:
    type: webhook
    url: "${WEATHER_WEBHOOK_URL}"
    payload:
      text: "$CONTENT"
    acknowledgement:
      mode: http_2xx
schedules:
  - id: morning
    cron: "0 0 8 * * *"
    timezone: Asia/Shanghai
    message: "查询已配置城市的天气，给出穿衣建议并通知 daily。"
    enabled: true
    max_duration_seconds: 60
settings:
  max_iterations: 10
  max_history_turns: 20
---
查询任务需要的天气来源，依据实际数据给出建议，再向 daily 发送通知。
查询失败时说明原因，不编造天气。
~~~

| 字段 | 校验／生效规则 |
| --- | --- |
| name | [a-z0-9][a-z0-9-]{0,63}；等于目录名；不得重名 |
| provider.name/model | 必填非空；引用存在且可用Provider；不默认选第一项 |
| tools | 工具全名数组；默认空集合而非全部；重复项拒绝 |
| mcp_servers | 可选服务器名称；即使引用server，仍须tools列出允许工具 |
| bootstrap | 相对workspace受控路径；缺文件说明错误，不默默忽略；不得指向秘密 |
| notify_channels | 当前Agent作用域目标；name唯一，只有type=webhook；无默认目标且多个目标时必须明确指定 |
| default_target | 可选，位于Agent frontmatter顶层，与notify_channels同级；必须引用其中的目标名称 |
| notify_channels.<name>.headers | 可选header名称到secret引用的映射；拒绝CR/LF及Host/Content-Length/Transfer-Encoding/Connection控制头 |
| schedules | id在Agent内唯一；合法六字段cron、非空任务；timezone省略继承工作区，显式值须与工作区一致；手动调用始终60秒 |
| settings.max_iterations | 正整数，默认10，上限100；配置异常不能无界执行 |
| settings.max_history_turns | 正整数默认20；按完整turn裁剪 |
| providerOptions | 供应商专用选项由runtime.yaml维护；Agent正文不能增加请求参数或覆盖连接地址 |

配置区、新增Agent、Provider、白名单、目标与调度定义均重启加载；
正文和Bootstrap每次构造上下文重读。单个Agent无效不影响其他有效Agent。
正文修改不重注册调度，不扩展工具权限。

## Provider 与运行配置

~~~yaml
schema_version: 1
timezone: Asia/Shanghai
providers:
  deepseek:
    protocol: openai_chat_completions
    endpoint: https://api.deepseek.com
    completions_path: /chat/completions
    api_key: "${DEEPSEEK_API_KEY}"
    connect_timeout_seconds: 5
    options:
      thinking:
        type: disabled
  kimi:
    protocol: openai_chat_completions
    endpoint: https://api.moonshot.cn
    completions_path: /v1/chat/completions
    api_key: "${KIMI_API_KEY}"
    connect_timeout_seconds: 5
    options:
      reasoning_effort: low
memory:
  backend: md
  archival_context_characters: 4000
tools:
  file:
    allowed_read_paths: [agents/daily-tech-digest/skills, output]
    allowed_write_paths: [output]
  shell:
    allowed_commands: []
    allowed_script_paths: []
    timeout_seconds: 20
    env: {} # 如 REPORT_TOKEN: "${REPORT_TOKEN}"，只解析显式引用
  http:
    allowed_targets:
      - scheme: https
        host: api.example-weather.invalid
        port: 443
      - scheme: https
        host: notify.example.invalid
        port: 443
    connect_timeout_seconds: 5
    read_timeout_seconds: 20
    max_response_bytes: 65536
~~~

.invalid是必须替换的示例，不能拿它宣称真实联调成功。
Provider连接不走Agent的HTTP工具白名单，但只能使用维护者配置的endpoint/path；
用户输入、模型输出和工具结果不能替换这些连接目标。
Kimi不发送thinking或temperature；请求工厂使用可配置reasoning_effort；
DeepSeek extraBody的thinking展开至顶层。重复/v1、非法scheme、缺凭证在启动校验中发现。
模型名必须按联调当天账号确认，不固定旧课程模型。

runtime.yaml的timezone是首版唯一业务时区，缺省Asia/Shanghai，必须是有效IANA名称。
Agent不提供另一层timezone覆盖；定时条目的timezone只用于兼容显式声明并校验一致性，
不能覆盖工作区值。值不一致使所属Agent不可用，并指出应统一修改的字段。
手动任务、定时任务的cron和提示词日期时间均使用此时区；日期“今天”以该时区当日为准。
修改后重启加载；运行信息和执行详情展示已加载时区，审计时间戳仍以UTC保存。
服务器系统时区不参与业务选择；切换时区不补跑过去遗漏的定时任务。

## 资源授权与通知

工具先查Agent工具集合，再查全局资源白名单，两者都通过才允许。
空白名单表示不允许，不是无限制；路径规范化后验证每级真实父路径和symlink。
默认不把整个工作区自动加入可写白名单，避免工具篡改自己的授权和状态。

HTTP只允许http/https；按scheme、规范化host、port精确匹配。
可显式配置 *.example.com，但必须以完整域名标签边界匹配，不能匹配example.com.evil。
拒绝URL中的userinfo；每次重定向重新授权；不能让调用方自带Cookie／Authorization取代受控凭证。
本地测试仅在测试工作区显式允许127.0.0.1及具体端口。
允许域名不等于对MCP或受信任脚本内部网络的全局隔离。

Shell命令必须是绝对可执行文件路径；args数组直接传ProcessBuilder，不接受sh -c拼接。
解释器还须校验脚本路径白名单；清空环境后仅传必要PATH/HOME/TMPDIR及显式引用，
不继承整份Provider／Webhook秘密。tools.shell.env及MCP env只能显式列出合法变量名与secret引用，不能覆盖PATH/HOME/TMPDIR。
当前最小PATH为/usr/bin:/bin；HOME/TMPDIR位于本工作区runtime下的各自进程数据目录。
脚本必须来自可信作者；此限制不能冒充OS沙箱。

通知payload为JSON对象，仅替换完整字符串值$CONTENT，不进行脚本／表达式执行，
通过JSON序列化器转义文本。acknowledgement.mode：
- http_2xx：接收端明确用2xx表示受理；
- json_equals：同时要求2xx及JSON Pointer字段等于配置expected，如 /errcode=0。
企业IM需要专用签名或刷新token时不属于本阶段通用Webhook保证。
Webhooks的敏感URL、headers均来自secret引用，工具只收到targetName；
有多个目标且无Agent顶层default_target配置时省略目标返回参数错误，不随机选择；只有一个目标时可省略targetName。
headers例如 `Authorization: "${NOTIFY_AUTHORIZATION}"`，秘密项保存完整值（如Bearer前缀也在秘密值内）。
有私有headers的请求不跨origin转发这些headers；重定向仍最多3跳且每跳校验目标。

## 记忆与增强服务配置

backend取md/sqlite/mem0，默认md。切换只改配置、重启，不自动搬迁或删除旧内容；
启动与status显示所选backend及旧数据可能不可见的提示。

mem0时补充endpoint、api_key引用、timeout_seconds默认20、
search_top_k默认10、search_threshold默认0.7。
默认阈值只是起点；必须按US3-A6正反例在锁定embedding/model上验证，调整后保存验证记录。
CORE和source ledger由本地SQLite负责，Mem0 user_id=workspace UUID，metadata含
oryx_scope=ARCHIVAL、source_entry_id；不传实际用户或Agent作为隔离条件。
本次明确保存的单条内容通过messages发送，不附当前会话历史。
CORE只在本地原文事务提交，不要求远端在线。ARCHIVAL的远端ID回读和本地源/映射提交
分开记录，收据不能把“有成功投影”描述成“所有原始事实的语义覆盖已证明”。

Mem0 endpoint为明确配置的自托管地址；OSS接口无/v1前缀，使用X-API-Key。
增强模式额外要求固定Mem0版本、LLM/embedding服务、独立持久卷和关闭遥测；
默认md/sqlite没有这些前提。返回空results不等于成功保存。

## MCP 文件

~~~yaml
schema_version: 1
servers:
  news:
    transport: stdio
    command: /absolute/path/to/news-mcp
    args: []
    env:
      NEWS_API_KEY: "${NEWS_API_KEY}"
    initialization_timeout_seconds: 20
    request_timeout_seconds: 20
~~~

只支持stdio；command必须显式绝对路径，env按需要注入，不继承所有环境。
运行时发现的工具名为mcp.news.<tool>；初始化失败只影响使用该server的Agent，
不影响无关本地工具。stderr限量且脱敏，stdout只作协议数据。
上述名称是内部权限／审计名称；发送模型时按runtime契约映射为合法wireName，
不能把点号直接发给限制函数名称字符的模型服务。

## 配置错误与审查证据

错误作用域必须在独立解析／校验阶段分类：

| 错误范围 | 示例 | 影响 |
| --- | --- | --- |
| 工作区整体 | runtime.yaml无法解析、重复Provider名／重复键、不支持schema版本、无效工作区时区或共享资源规则 | CONFIGURATION_ERROR，诊断可用但不执行任务 |
| 单Provider | 该定义endpoint/options不合法、api_key占位符缺失 | Provider标UNAVAILABLE，只禁用引用它的Agent，其余正常 |
| 单Agent | frontmatter无效、引用不存在、通知秘密缺失、调度时区不一致 | 该Agent标UNAVAILABLE，其余正常 |
| 单MCP定义 | 定义错误、必要秘密缺失、初始化失败 | 只影响引用该server的Agent，不阻断其他Agent的本地工具 |

Provider可用性在此指配置校验，不以启动时真实模型调用判断账号额度或凭证有效性。
已通过配置校验但请求返回401/429等按执行错误处理，不把一次服务错误提升为全局配置错误。
单Agent错误在profiles列表显示safeError；调用该Agent返回409。
全局错误也只显示出错字段与安全说明，不回显秘密值。
查询显示loadedRevision，正文显示bodyRevision；管理台不提供配置编辑或数据库覆盖。
验收必须用两份不同Provider/目标配置证明加载源、重启边界和权限边界一致。

