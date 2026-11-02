---
title: Define an Agent
description: OryxOS Define an Agent — current capabilities and runtime design.
---

# Define an Agent

This page describes the planned Agent format. The current scaffold does not load or execute Agent directories yet.

## One directory = one Agent

```text
.oryxos/agents/daily-tech-digest/
├── AGENT.md
├── REFERENCE.md
├── skills/
│   └── digest-format.md
└── scripts/
    └── fetch_news.py
```

`AGENT.md` contains YAML frontmatter for runtime configuration and a Markdown body describing the task. The runtime derives a Profile from it; a separate Profile YAML file is not required.

## Progressive loading

- The main instructions and selected Bootstrap files enter the system prompt.
- Reference material and sub-instructions are read through `read_file` when needed.
- Scripts run through `shell`; their results enter the conversation.

An Agent directory is a source of instructions, not an executable tool. The runtime does not parse the instructions into a fixed workflow.

## Example task instructions

```text
Prepare a daily technology digest.
Read skills/digest-format.md for the editorial format.
Use available news tools and the user's remembered interests.
Summarize the selected stories and send the digest to the configured destination.
```

## Trigger paths

The design supports manual CLI input, REST invocation, and scheduled triggers through the same `AgentService`. Cron, timezone, tool scope, and notification destinations are runtime configuration concerns.

Some configuration details are still being reconciled across the source documents. Refer to the [development guide](https://github.com/jianghr-rr/OryxOS/blob/main/CLAUDE.md) before implementing a schema.
