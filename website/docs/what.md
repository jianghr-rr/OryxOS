---
title: What is OryxOS?
description: OryxOS What is OryxOS? — current capabilities and runtime design.
---

# What is OryxOS?

OryxOS is a Java-native, self-hosted **AI Agent Harness OS**. It aims to run and manage multiple agents on a shared foundation for model access, reasoning, context, tools, memory, and audit.

## Model → Harness → OS

A model produces responses. A harness drives the reasoning loop, executes tools, assembles context, and records actions. An Agent Harness OS manages a group of agents and the infrastructure they share.

The first stage focuses on one node: define agents in directories, invoke them through CLI or HTTP, and eventually run scheduled tasks through the same engine. Distributed deployment and cross-node collaboration come later.

## What works today

- Nine Maven modules with a reproducible build.
- A standalone CLI that prints the project version.
- An executable Spring Boot JAR that starts an HTTP server.
- This bilingual website and its documentation.

The ReAct runtime, providers, memory, tools, business APIs, and management UI are **not implemented yet**. The architecture pages describe the target design.

## Design principles

- Define an agent with configuration and instructions.
- Keep control of the ReAct loop; use Spring AI for protocol adapters and tool schemas.
- Reuse MCP for external tools.
- Record model and tool calls in persistent storage.
- Start with a useful single-node runtime; add infrastructure when usage calls for it.

[Build the current scaffold](./quick-start.md) or [explore the architecture](./architecture.md).
