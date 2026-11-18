---
schema_version: 1
name: default
description: 默认 Agent 模板；请配置 Provider 后使用
provider:
  name: configure-provider
  model: configure-model
tools: []
mcp_servers: []
bootstrap: [AGENTS.md, SOUL.md, USER.md]
notify_channels: {}
schedules: []
settings:
  max_iterations: 10
  max_history_turns: 20
---
根据用户任务提供有依据的回答。缺少必要信息时说明，不编造结果。
