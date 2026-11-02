---
title: 路线图
description: OryxOS 路线图：当前能力与运行时设计。
---

# 路线图

## 当前已有

- 面向 Java 21 的九模块 Maven 工程骨架。
- CLI 版本输出与可执行 Spring Boot 应用。
- 中英双语项目网站与技术文档。

## 第一阶段 · 单机运行时

按 Provider → ReAct → Memory / Tool → Web Service 的依赖顺序实现核心能力。补齐 Agent 目录加载、MCP 接入、会话与审计持久化、CLI 子命令及定时执行。

使用每日天气、科技日报、GitHub 日报验证完整链路。同一个 Agent 应能通过定时和手动两种方式触发。

## 第二阶段 · 底座分布式

状态外置、多副本部署、调度协调，提升可用性和容量。这些是后续目标，不是当前部署保证。

## 第三阶段 · 跨节点协作

通过 Agent 通信底座与 A2A 接入，探索 Agent 发现、任务委托及可靠异步协作。

## 横向能力

工具策略、更强隔离、可观测性、Web 管理、认证、SSO 和多租户，将随着运行时与使用场景逐步发展。

[在 GitHub 讨论提案](https://github.com/jianghr-rr/OryxOS/issues)
