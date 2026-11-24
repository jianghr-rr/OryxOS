# 三个 Agent 示例（T096 / T097）

这里提供可配置的业务示例；本批仅完成可控本地整合。示例没有真实密钥，不会开箱发送通知，
所有定时任务默认关闭。尚未完成 T098–T100 的公网天气、新闻、GitHub 及实际收件人验收。
假模型只用于检查运行时协议和记录，不能证明真实模型会正确组稿、选择工具或遵循指令。

| Agent | 业务来源 | 必须补齐的配置 |
| --- | --- | --- |
| daily-weather | 上海当天天气；HTTP GET 一次后通知一次 | 天气 URL/字段说明、精确 HTTP 白名单、模型、测试接收目标 |
| daily-tech-digest | 共享偏好 + 按需组稿参考 + `mcp.news.search` | 作者信任的 stdio 新闻服务及真实来源、模型、测试接收目标 |
| github-daily | 附带可信 Python 脚本的 GitHub 搜索快照 | Python 3.9+ 绝对路径、工作区绝对脚本路径、模型、测试接收目标；需要时配置 token |

三个任务的时间分别为 Asia/Shanghai 08:00、09:00、10:00，最大执行时间 60 秒。
改时区时同步 runtime 与 Agent schedule，不能依赖宿主时区。配置区改动需重启；正文和参考下次请求读取。
复用已有工作区时请手动合并，不覆盖已有 runtime、Agent 或 secrets。

## 在新的个人工作区准备

以下命令只准备文件；执行前设置你自己的 JDK 21 路径。构建和运行均走个人 tools/dev。

```bash
cd /Users/jianghaoran/github/OryxOS
export ORYXOS_JAVA_HOME=/absolute/path/to/jdk-21/Contents/Home
./tools/dev/mvnw -B -ntp package -Dgroups=kernel-local
mkdir -p .oryxos-dev/workspaces
ORYXOS_EXAMPLE_WS=$(mktemp -d "$PWD/.oryxos-dev/workspaces/examples.XXXXXX")
./tools/dev/oryxos --root "$ORYXOS_EXAMPLE_WS" init
cp examples/runtime.example.yaml "$ORYXOS_EXAMPLE_WS/runtime.yaml"
cp examples/mcp_servers.example.yaml "$ORYXOS_EXAMPLE_WS/mcp_servers.yaml"
cp -R examples/agents/. "$ORYXOS_EXAMPLE_WS/agents/"
python3 - "$ORYXOS_EXAMPLE_WS" <<'PY'
import sys
from pathlib import Path
root = Path(sys.argv[1]).resolve()
p = root / 'agents/github-daily/AGENT.md'
p.write_text(p.read_text().replace('/ABSOLUTE/WORKSPACE', str(root)))
print(root)
PY
```

随后在这个新目录里配置：

1. 将 `runtime.yaml` 的 model.example.invalid、completions_path 换为账户已确认的协议入口，
   三个 Agent 的 CONFIGURE_MODEL 换为该账户可用模型。`EXAMPLE_MODEL_KEY` 由显式环境变量或
   本地 `secrets.local.yaml` 提供；文件权限须 `chmod 600`，不能写入 Agent、Git 或截图。
   也可以改用 `runtime.example.yaml` 中注释掉的 Kimi 配置：删除各行开头的 `# `，三个 Agent 的
   provider 改为 `kimi`，密钥用 `KIMI_API_KEY`；步骤与选项限制见
   [CLI 指南“启用 Kimi”](../docs/CliGuide.md#启用-kimi)。
2. 天气默认城市 Shanghai；作者替换 .invalid 查询地址、明确响应字段并同步 HTTP 白名单。
   目前接口模板要求 city/date/temperature_c/condition/source，不假设任意供应商兼容它。
3. 安装和审阅你信任的新闻 MCP 服务，按 `mcp_servers.example.yaml` 配置绝对命令和必要 env 引用。
   `news.search` 输入 query/date，输出有标题、来源和发布日期的条目；实际 Schema 不同则同步 Agent 指令。
   不要将 `src/test/resources/kernel/news-mcp.py` 当作真实新闻服务。
4. 选择明确的**测试**通知接收端，将 `TEST_WEBHOOK_URL` 存为本地秘密，更新 HTTP 白名单。
   当前约定 `{text: "$CONTENT"}` 且 JSON `/errcode == 0` 才算确认；必须按真实接收端契约调整。
   不得为了让测试成功改成无条件 HTTP 200 成功。
5. 核对 `/usr/bin/python3` 存在且有 Python 3.9+、zoneinfo 数据；若路径不同，同步 shell 白名单及 Agent 指令。
   GitHub 默认无认证访问，可能限流；可在本地 secret 中配置 GITHUB_TOKEN 并启用 runtime 中的显式 env 引用。
   脚本拒绝重定向、不自动重试、错误不输出样例数据。脚本和 MCP 的网络访问属于作者信任边界，
   不是 HTTP 工具白名单或 OS 强隔离。

保留或移走 init 生成的 default Agent 由你决定；它不是这三个示例之一。缺 Provider/通知/MCP 配置时受影响 Agent
会不可用；缺脚本路径、天气源配置时执行会失败。这样的结果应先修配置，不能以替身宣布真实验收完成。

## 亲自验证

```bash
# 无需任何外部凭证，自动创建和清理临时工作区；只访问本地服务
./tools/dev/mvnw -B -ntp test -Dgroups=kernel-local

# 以下需完成上述真实配置；通知会发送到你明确选择的测试目标
./tools/dev/oryxos --root "$ORYXOS_EXAMPLE_WS" chat --profile daily-tech-digest --user author
# 输入：请记住：关注 AI 和芯片；确认保存结果后 /quit
./tools/dev/oryxos --root "$ORYXOS_EXAMPLE_WS" serve
```

打开同源管理台 <http://127.0.0.1:8080/admin/>，指定使用者标识，查看 Agent、共享记忆及手动运行结果。
按 execution ID 检查模型调用、工具结果与会话消息；天气应有一次查询和一次 notify，日报应保留来源和偏好，
GitHub 应有一次 shell、一次 notify 及三个栏目。确认测试收件人实际收到后，再启用选定的定时任务。
需命令行手动运行时先停止 serve（同一工作区 chat/serve 互斥）；查询刷新不会重发。
普通工具错误应留在记录中；UNKNOWN 先核查接收端，不点击运行来“确认是否送达”。

## GitHub 数据口径

脚本输出 today（当天新建）、month（本月新建）和 ai（本月有更新的 artificial-intelligence topic）三部分，
各取当前总星数排序前 3 个仓库，保留查询 URL、项目 URL、观测时间。空结果保留空栏目。
不是 GitHub Trending 页面、星数增量或全站完整榜单。`incomplete_results=true`、缺元数据、超大响应或字段错误
均非零退出，不把部分资料当完整报告。日期窗口和 stars 查询口径依据
[GitHub 仓库搜索文档](https://docs.github.com/en/enterprise-cloud%40latest/search-github/searching-on-github/searching-for-repositories)
和 [Search REST API](https://docs.github.com/en/rest/search/search)。本批只查文档，没有发起真实业务采集。

本地证据与命令见 [US5-kernel-local](../specs/001-single-node-agent-runtime/evidence/US5-kernel-local.md)。
