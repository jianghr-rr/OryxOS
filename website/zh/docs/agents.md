---
title: 定义 Agent
description: OryxOS 定义 Agent：当前能力与运行时设计。
---

# 定义 Agent

本页介绍计划中的 Agent 格式。当前工程骨架尚不能加载或执行 Agent 目录。

## 一个目录 = 一个 Agent

```text
.oryxos/agents/daily-tech-digest/
├── AGENT.md
├── REFERENCE.md
├── skills/
│   └── digest-format.md
└── scripts/
    └── fetch_news.py
```

`AGENT.md` 的 YAML frontmatter 表达运行配置，Markdown 正文表达任务指令。运行时从中派生 Profile，无需再写独立的 Profile YAML。

## 按需加载

- 主指令和选定的 Bootstrap 文件进入 system prompt。
- 子指令和参考资料在需要时通过 `read_file` 读取。
- 脚本通过 `shell` 执行，结果进入对话上下文。

Agent 目录提供上下文，本身不是可执行 Tool。底座不把任务正文解析成固定工作流。

## 任务指令示意

```text
生成每日科技日报。
读取 skills/digest-format.md，按照其中的组稿规范输出。
使用可用的新闻工具，结合记忆中的用户关注方向筛选内容。
整理摘要，并发送到配置好的通知目标。
```

## 触发方式

设计包含 CLI 手动输入、REST 调用和定时任务，三者共用 `AgentService`。cron、时区、工具范围和通知目标由运行配置管理。

部分配置细节仍需在原始文档之间统一。实现 Schema 前请阅读[开发指南](https://github.com/jianghr-rr/OryxOS/blob/main/CLAUDE.md)中的差异清单。
