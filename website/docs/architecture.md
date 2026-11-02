---
title: Architecture
description: OryxOS Architecture — current capabilities and runtime design.
---

# Architecture

The diagram shows the **target single-node architecture**, not a list of completed features.

<script setup>
import architecture from '../../docs/images/architecture.svg'
</script>

<img :src="architecture" alt="OryxOS target architecture" />

## One engine, three entry points

CLI, REST, and the scheduler call `AgentService`. The service owns request context and persistence, while `ReActLoop` coordinates model calls, memory, and tool execution.

`AgentLoader` derives a Profile from an Agent directory. `ContextLoader` and `PromptBuilder` assemble instructions, Bootstrap files, the current time, memory, recent messages, and tool schemas.

## Runtime boundaries

| Component | Responsibility |
| --- | --- |
| Provider | Explicit provider-name-to-model mapping and protocol adaptation |
| ReAct | Reason → act → observe, message accumulation, iteration limits |
| Memory | Unified access to session history and long-term stores |
| Tool | Built-in, Java, and MCP tools through one execution contract |
| Storage | Sessions, model/tool audit, and scheduler state |

Spring AI automatic tool execution stays disabled. OryxOS controls scheduling and execution to prevent duplicate tool calls.

## Storage and safety

SQLite is the baseline for sessions and audit. Long-term memory has Markdown, SQLite, and self-hosted Mem0 adapters in the design. Agent definitions and editable context remain in the workspace.

Application-level path, command, and domain allowlists are the initial guardrails. They are not container isolation. A script may make its own network requests; running a script-bearing Agent requires trust in its author.

## Nine modules

`oryxos-core`, `oryxos-provider`, `oryxos-memory`, `oryxos-tool`, `oryxos-channel-cli`, `oryxos-web`, `oryxos-storage`, `oryxos-cli`, and `oryxos-boot` form the initial Maven structure.

[Full technical design](https://github.com/jianghr-rr/OryxOS/blob/main/docs/TechnicalSolution.md)
