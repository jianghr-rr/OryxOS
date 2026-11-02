---
title: Roadmap
description: OryxOS Roadmap — current capabilities and runtime design.
---

# Roadmap

## Available now

- Nine-module Maven scaffold targeting Java 21.
- CLI version output and executable Spring Boot application.
- Bilingual project website and technical documentation.

## Stage 1 · Single-node runtime

Implement Provider → ReAct → Memory / Tool → Web Service. Add Agent directory loading, MCP integration, persistent sessions and audit, CLI commands, and scheduled execution.

Validate the combined capabilities with daily weather, technology digest, and GitHub digest scenarios. The same Agent should support scheduled and manual invocation.

## Stage 2 · Distributed infrastructure

Externalize state, support multiple replicas, coordinate scheduling, and improve availability and capacity. These are future goals, not current deployment guarantees.

## Stage 3 · Cross-node collaboration

Explore Agent discovery, task delegation, and reliable asynchronous collaboration through an Agent communication layer and A2A integration.

## Capabilities across stages

Tool policies, stronger isolation, observability, a web management UI, authentication, SSO, and multi-tenancy will be introduced as the runtime and use cases develop.

[Discuss a proposal on GitHub](https://github.com/jianghr-rr/OryxOS/issues)
