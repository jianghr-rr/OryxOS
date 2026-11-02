---
title: 系统架构
description: OryxOS 系统架构：当前能力与运行时设计。
---

# 系统架构

下图是**单机阶段目标架构**，并非已实现功能清单。

<script setup>
import architecture from '../../../docs/images/architecture.svg'
</script>

<img :src="architecture" alt="OryxOS 单机运行时目标架构" />

## 一套引擎，三个入口

CLI、REST 和定时任务统一调用 `AgentService`，由服务管理请求上下文和持久化，再交给 `ReActLoop` 调度模型、记忆与工具。

`AgentLoader` 从 Agent 目录派生 Profile。`ContextLoader` 与 `PromptBuilder` 组合任务指令、Bootstrap、当前时间、记忆、近期消息和工具 Schema。

## 运行边界

| 组件 | 职责 |
| --- | --- |
| Provider | Provider 名到模型的显式映射、协议适配 |
| ReAct | 推理、执行、观察，累积消息并限制迭代次数 |
| Memory | 统一访问会话记忆和长期记忆 |
| Tool | 内置、Java、MCP 工具共用执行契约 |
| Storage | 会话、模型/工具审计、调度运行状态 |

必须关闭 Spring AI 自动执行工具的机制，由 OryxOS 调度实际执行，避免重复调用。

## 存储与安全

会话和审计以 SQLite 为基础。长期记忆设计为 Markdown、SQLite 和自托管 Mem0 三档后端。Agent 定义和可编辑上下文保存在工作区文件中。

初始沙箱是应用层文件、命令、域名白名单，不等同于容器隔离。脚本可以自行发起网络请求，因此运行带脚本的 Agent 需要信任作者。

## 九个模块

初始 Maven 工程包含 `oryxos-core`、`oryxos-provider`、`oryxos-memory`、`oryxos-tool`、`oryxos-channel-cli`、`oryxos-web`、`oryxos-storage`、`oryxos-cli`、`oryxos-boot`。

[完整技术方案](https://github.com/jianghr-rr/OryxOS/blob/main/docs/TechnicalSolution.md)
