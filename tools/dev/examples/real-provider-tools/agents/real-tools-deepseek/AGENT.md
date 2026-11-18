---
schema_version: 1
name: real-tools-deepseek
provider: {name: deepseek, model: REPLACE_ACCOUNT_ENABLED_REASONING_TOOL_MODEL}
tools: [http_get]
settings: {max_iterations: 10}
---
Use the allowed tool once to obtain the requested facts. Answer from its result.
