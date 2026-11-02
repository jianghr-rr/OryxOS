---
title: 参与贡献
description: OryxOS 参与贡献：当前能力与运行时设计。
---

# 参与贡献

OryxOS 是 oryx-labs 发起、以 AI coding 推进的项目。欢迎从设计反馈、文档、功能实现、测试或真实使用场景开始参与。

## 开始修改前

1. 阅读[开发指南](https://github.com/jianghr-rr/OryxOS/blob/main/CLAUDE.md)与对应技术方案。
2. 较大的接口和架构调整先通过 [Issue](https://github.com/jianghr-rr/OryxOS/issues) 讨论。
3. 每个 PR 聚焦一个问题，说明行为、验证结果和限制。

## 验证改动

Java 改动：

```bash
./mvnw clean package
```

网站改动：

```bash
npm ci
npm run docs:build
npm run docs:preview
```

检查中英双语、桌面与移动布局、站内导航，以及 `/OryxOS/` 子路径下的资源。生成产物和凭证不要提交 Git。

## 网站部署

仓库已提供 GitHub Pages 工作流。维护者需要在仓库 **Settings → Pages → Build and deployment** 中选择 **GitHub Actions**。使用默认项目网址时，**Custom domain 留空**。

网站文件推送到 `main` 后，工作流构建并部署到 `https://jianghr-rr.github.io/OryxOS/`。在本地准备工作流不等于已经发布。

## 参考

网站布局参考 [robustmq/mq9](https://github.com/robustmq/mq9)，内容调整为 OryxOS。项目计划采用 Apache License 2.0，正式 LICENSE 文件尚待补齐。
