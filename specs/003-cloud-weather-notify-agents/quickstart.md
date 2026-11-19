# Quickstart & Validation Guide: 云端天气与通知 Agent 配置及自动交付

**Feature**: `003-cloud-weather-notify-agents`  
**Date**: 2026-11-19  

本指南用于验证云端 `runtime.yaml.template`、托管 Agent 模板（`weather`、`feishu-notify`）、工作区同步脚本及发布链路端到端可用。

## 1. 本地自动化回归与模板契约验证（无网络、无真实密钥）

### 1.1 运行全量本地回归与模板契约测试 `TencentCloudConfigTemplateTest`

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
./tools/dev/mvnw -B -ntp verify
```

（`tools/dev/mvnw` 会自动注入 `runId`、隔离本地仓库并核查全部分组 XML 报告；如在原生 `mvn` 环境单独只跑模板测试可执行 `mvn -B -ntp -pl oryxos-boot -am test -Dtest=TencentCloudConfigTemplateTest -Dsurefire.failIfNoSpecifiedTests=false`。）

**预期结果**：
- `Tests run: >= 1, Failures: 0, Errors: 0, Skipped: 0`
- 证明 `deploy/tencent-cloud/config/runtime.yaml.template` 与 `deploy/tencent-cloud/config/agents/{weather,feishu-notify}/AGENT.md` 可被真实 `RuntimeConfigLoader`、`AgentCatalog`、`HttpTargetPolicy`、`NotificationTargetResolver` 正常加载，`schedules` 为空，未授权主机被拦截，且日志与摘要无敏感凭证泄露。

### 1.2 本地干跑验证 `sync-workspace-config.sh`

在一个临时目录中模拟已上线且带有旧坏 `runtime.yaml`、占位 `agents/default`、模拟数据库 `oryxos.db`、`0600` 的 `secrets.local.yaml` 与自建非托管 `agents/custom-keep/AGENT.md`：

```bash
TEST_WS="$(mktemp -d)"
mkdir -p "$TEST_WS/agents/default" "$TEST_WS/agents/custom-keep" "$TEST_WS/memory" "$TEST_WS/output"
printf 'invalid_old_config: true\n' > "$TEST_WS/runtime.yaml"
printf 'default-placeholder\n' > "$TEST_WS/agents/default/AGENT.md"
printf 'custom-agent\n' > "$TEST_WS/agents/custom-keep/AGENT.md"
printf 'sqlite-stub\n' > "$TEST_WS/oryxos.db"
printf 'DEEPSEEK_API_KEY: stub\nFEISHU_WEBHOOK_URL: https://example.invalid/hook\n' > "$TEST_WS/secrets.local.yaml"
chmod 0600 "$TEST_WS/secrets.local.yaml"

ORYXOS_SKIP_CHOWN=true bash deploy/tencent-cloud/scripts/sync-workspace-config.sh deploy/tencent-cloud/config "$TEST_WS"
ORYXOS_SKIP_CHOWN=true bash deploy/tencent-cloud/scripts/sync-workspace-config.sh deploy/tencent-cloud/config "$TEST_WS"
```

**预期结果**：
- 连续执行两次均退出码 `0`；
- `$TEST_WS/runtime.yaml` 与 `deploy/tencent-cloud/config/runtime.yaml.template` 一致；
- `$TEST_WS/agents/weather/AGENT.md` 与 `$TEST_WS/agents/feishu-notify/AGENT.md` 存在；
- `$TEST_WS/agents/default` 已被清理；
- `$TEST_WS/agents/custom-keep/AGENT.md`、`$TEST_WS/oryxos.db`、`$TEST_WS/memory`、`$TEST_WS/output` 及 `$TEST_WS/secrets.local.yaml`（权限仍为 `0600`）均完整保留。

### 1.3 脚本语法与脱敏扫描

```bash
bash -n deploy/tencent-cloud/scripts/*.sh
```

并对 `specs/003-cloud-weather-notify-agents/`、`deploy/tencent-cloud/`、`.github/workflows/`、`docs/TencentCloudDeployment.md` 执行敏感凭证扫描，确认命中数为 `0`。

---

## 2. 云端部署与端到端实测验收

1. **触发发布**：确认 GitHub Repository Secrets 已配置 `DEEPSEEK_API_KEY` 与 `FEISHU_WEBHOOK_URL`，提交并推送到 `main` 分支（或手动触发 `Deploy OryxOS to Tencent Cloud`）。
2. **核查部署日志**：在 GitHub Actions `Switch release and run health check` 输出中确认出现 `weather: AVAILABLE` 与 `feishu-notify: AVAILABLE`（且无 `default`）。
3. **检查管理台页面**：打开 `https://oryxos.curlyhair.cn/admin/#agents`：
   - 页面显示 `weather` 与 `feishu-notify` 均处于 `AVAILABLE` 状态，`default` 不再存在；
   - 打开 `/admin/#schedules` 确认列表无自动定时任务。
4. **手动触发验证**：
   - 在 `/admin/#agents` 中对 `feishu-notify` 发起一次手动运行（如：`请发送一条消息到飞书群：OryxOS 云端通知测试成功`）→ 60 秒内飞书测试群收到消息且 `/admin/#executions` 显示 `SUCCEEDED`、无明文 Webhook URL。
   - 在 `/admin/#agents` 中对 `weather` 发起一次手动运行（如：`查询今天上海的天气并发送到飞书群`）→ 60 秒内返回真实天气摘要，飞书测试群收到天气播报，执行记录含 `http_get` 与 `notify` 两次确认调用且敏感信息脱敏。
