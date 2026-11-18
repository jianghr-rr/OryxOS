# US4 可信 MCP 前提与未完成状态

**T071、T072未完成。** SDK与可控fixture的本地成功不代替维护者选定真实服务的验收。
本批没有提供适用的可信stdio服务、锁定版本、调用参数和私有配置，没有启动真实服务或代选外部工具。

`ConfiguredMcpIT`已编译并登记`real-mcp`。它通过SDK0.18.3进行真实initialize/分页发现/一次调用/退出，
经ToolExecutor授权和日志记录，不调用模型、不自动重连或重放。

## 所需前提

1. 个人项目下已init的独立私有工作区，有效Agent配置，`mcp_servers: [所选server]`，
   `tools: [mcp.<server>.<remote-tool>]`；工具入口沿用Agent配置校验但不会调用其Provider。
2. `mcp_servers.yaml` schema_version=1，所选服务transport=stdio、绝对command、args、显式env secret引用。
   所需秘密置于权限600的私有`secrets.local.yaml`；不继承Provider或其他项目环境变量。
3. 已安装、来源可信且版本固定的本地服务。入口必须是绝对可执行文件，或以第一个绝对脚本参数启动；
   不以npx/uvx或启动时下载包替代锁定。准备入口文件SHA-256以及initialize返回的serverInfo.version。
   该哈希仅核对入口文件；服务依赖也需维护者通过其lockfile/发行版固定，不能把单文件哈希称作整个依赖闭包验证。
4. 明确选择可安全执行一次的remote tool及JSON对象参数文件（绝对路径、≤64KiB），确认其真实副作用。
   参数文件只保存任务必要数据，不保存秘密值；server env只注入所需秘密引用。

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
ORYXOS_REPO=/Users/jianghaoran/github/OryxOS
ORYXOS_REAL_WS="$ORYXOS_REPO/.oryxos-dev/real-tools-workspace"
# 把以下server/tool/version/hash/参数文件替换为维护者确认的真实配置。
"$ORYXOS_REPO/tools/dev/mvnw" -B -ntp -pl oryxos-boot -am test \
  -Doryxos.tests.mode=real -Dgroups=real-mcp \
  -Doryxos.real.workspace="$ORYXOS_REAL_WS" -Doryxos.real.agent=real-tools \
  -Doryxos.real.mcp.server=trusted-server -Doryxos.real.mcp.tool=chosen-tool \
  -Doryxos.real.mcp.version=REPLACE_WITH_PINNED_VERSION \
  -Doryxos.real.mcp.entrypointSha256=REPLACE_WITH_64_HEX_SHA256 \
  -Doryxos.real.mcp.argumentsFile="$ORYXOS_REAL_WS/call-arguments.json" \
  -Doryxos.real.mcp.confirmCall=true
```

先核对入口哈希，后启动服务；返回版本不符拒绝工具调用并关闭服务。
实际发现数量、版本、入口哈希、执行/调用ID、attempt、outcome及关闭状态保存在
`.oryxos-dev/evidence/us4-real/<runId>/mcp.json`。初始化、调用或关闭任一步失败都不能关闭T071。
进程清理不等于撤销已发动作；UNKNOWN先人工核查，不自动重跑。

最小环境为PATH/HOME/TMPDIR加显式env引用；该策略已有带外来CANARY的实际子JVM验证。
可信MCP仍可通过其自身权限访问本机/网络，不宣称为OS沙箱。
本次前检runId `29c49352-3f0b-46ae-b8d0-a764dcf08eb1`在confirmCall缺失处失败，0跳过、0可信服务启动；
与[通知前检](US4-real-notify.md)共用归档，不替代真实验收。
