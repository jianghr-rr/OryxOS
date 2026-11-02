---
title: Tools & integration
description: OryxOS Tools & integration — current capabilities and runtime design.
---

# Tools & integration

Tool integration is part of the target runtime and has not been implemented in the scaffold.

## Three extension paths

| Path | How it works |
| --- | --- |
| Instructions + existing MCP | Define the task and reuse an existing MCP server |
| Custom MCP server | Expose business operations from any suitable language |
| Native Java | Annotate Java methods with `@Tool` and reuse Spring services |

Built-in, Java, and MCP tools are adapted to `OryxTool`. The model selects a tool, `ToolExecutor` executes it, and the result is returned to the reasoning loop with an audit record.

## Baseline built-in tools

| Group | Tools |
| --- | --- |
| Files | `read_file`, `write_file`, `list_dir` |
| Commands | `shell` |
| HTTP | `http_get`, `http_post` |
| Memory | `save_memory`, `recall_memory` |
| Notifications | `notify` |

## Controlled execution

File, command, and HTTP operations pass through application-level allowlists. Notifications reuse HTTP checks and tool audit. Secrets belong in controlled configuration, not in agent instructions or committed files.

A private deployment can still send requests to external providers, MCP servers, or notification services. Choose local or private services when data must stay inside the network.

## REST integration

Business systems will use `/api/v1` for sessions, Agent invocation, and resource queries. The current Boot JAR starts HTTP infrastructure only; those business endpoints are not available yet.
