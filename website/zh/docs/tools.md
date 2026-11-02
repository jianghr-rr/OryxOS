---
title: 工具与接入
description: OryxOS 工具与接入：当前能力与运行时设计。
---

# 工具与接入

工具接入属于目标运行时的能力，当前工程骨架尚未实现。

## 三种扩展方式

| 方式 | 做法 |
| --- | --- |
| 指令 + 现成 MCP | 描述任务并复用已有 MCP Server |
| 自定义 MCP Server | 用合适的语言封装企业业务操作 |
| 原生 Java | 使用 `@Tool` 标注 Java 方法，复用 Spring 服务 |

内置、Java 与 MCP 工具统一适配为 `OryxTool`。模型选择工具，`ToolExecutor` 执行并记录审计，再把结果交回推理循环。

## 基线内置工具

| 分组 | 工具 |
| --- | --- |
| 文件 | `read_file`、`write_file`、`list_dir` |
| 命令 | `shell` |
| HTTP | `http_get`、`http_post` |
| 记忆 | `save_memory`、`recall_memory` |
| 通知 | `notify` |

## 受控执行

文件、命令与 HTTP 操作通过应用层白名单校验。通知复用 HTTP 校验和工具审计。密钥放在受控配置中，不写入 Agent 指令或提交文件。

私有部署仍可能向外部模型、MCP Server 和通知服务发送请求。需要数据完全留在内网时，应选择本地或私有服务。

## REST 接入

业务系统将通过 `/api/v1` 访问会话、Agent 调用与资源查询。当前 Boot JAR 只启动 HTTP 基础设施，这些业务端点尚不可用。
