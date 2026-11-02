---
title: OryxOS 是什么？
description: OryxOS OryxOS 是什么？：当前能力与运行时设计。
---

# OryxOS 是什么？

OryxOS 是 Java 原生、可私有部署的 **AI Agent Harness OS**。它的目标是在同一套底座上运行和管理多个 Agent，统一提供模型接入、推理、上下文、工具、记忆与审计。

## Model → Harness → OS

模型生成回复；Harness 驱动推理循环、执行工具、组装上下文并记录行为；Agent Harness OS 管理多个 Agent 及其共享的基础设施。

第一阶段聚焦单机：通过目录定义 Agent，通过 CLI 或 HTTP 调用，后续让定时任务复用同一引擎。底座分布式和跨节点协作在后续阶段推进。

## 目前可以做什么

- 构建九个 Maven 模块。
- 运行独立 CLI，打印项目版本。
- 启动可执行 Spring Boot JAR，运行 HTTP 应用骨架。
- 阅读本中英双语网站及项目文档。

ReAct 引擎、模型接入、记忆、工具、业务 API 和管理台**尚未实现**。架构与能力页面描述的是设计目标。

## 设计原则

- 用配置与指令定义 Agent。
- 自己控制 ReAct 循环，复用 Spring AI 的协议适配和工具 Schema 生成。
- 通过 MCP 接入外部工具。
- 模型与工具调用写入持久化审计记录。
- 先做好单机运行，再依据使用需求增加基础设施。

[构建当前工程](./quick-start.md)，或[了解系统架构](./architecture.md)。
