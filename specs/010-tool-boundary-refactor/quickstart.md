# Quickstart & Verification Guide: 第 20 节 Tool 体系边界对齐

## 1. 环境准备

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
```

## 2. 本节定向验证

```bash
# 只跑第 20 节新增的 7 个课件命名 harness 测试类
./tools/dev/mvnw -B -o -pl oryxos-tool test \
  -Dtest=OryxToolContractTest,ToolRegistryTest,FileToolsTest,ShellToolsTest,HttpToolsTest,McpClientServiceTest,McpToolAdapterTest

# 跑完整 us4-tools 分组（含全部新增 7 类与既有工具/Notify/MCP 契约测试，自动触发 test_report_gate.py）
./tools/dev/mvnw -B -o test -Dgroups=us4-tools
```

## 3. 全量构建与静态门禁

```bash
# 全量编译、Spotless、Checkstyle/P3C、SpotBugs/FindSecBugs、PMD、全部 local 测试与分组核对
./tools/dev/mvnw -B -o clean verify

# 质量基线只减不增校验
./tools/dev/quality-baseline --check-shrink origin/main
```

## 4. 人工验收剩余项（harness 无法替代的真模型/真外部 MCP 验证）

1. **方式一真跑一次**：编写 Agent 目录（`AGENT.md` + 可选 `skills/*.md`）并配置真实外部 MCP server（`.oryxos/mcp_servers.yaml`），使用真实 LLM 跑通一次对话，确认 Agent 读懂意图并调用外部 MCP 工具完成任务。
2. **方式三真跑一次**：验证 `@Tool` 原生扩展示例（如 `echo`）在 `oryxos tool list` 中可见，且真模型对话链路下可正常调通。
3. **`tool_invocations` 实链目检**：上述真跑链路完成后，查 SQLite `tool_invocations` 表确认每次内置/扩展/MCP 工具调用均留下完整审计记录且无明文敏感信息。
