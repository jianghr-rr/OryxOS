---
title: 构建与运行
description: OryxOS 构建与运行：当前能力与运行时设计。
---

# 构建与运行

## 环境要求

Java 工程使用 **JDK 21**；网站使用 **Node.js 22+**，推荐 24。Maven Wrapper 会自动下载 Maven 3.9.16，首次构建需要联网。

将 `JAVA_HOME` 指向 JDK 21 安装目录，并确认 `java -version` 与 `./mvnw -version` 显示正确的 Java 版本。

## 构建全部模块

```bash
git clone https://github.com/jianghr-rr/OryxOS.git
cd OryxOS
./mvnw clean package
```

Windows 使用 `mvnw.cmd clean package`。该命令编译九个模块并执行应用启动测试。

## 运行 CLI

```bash
java -jar oryxos-cli/target/oryxos-cli-1.0.0-SNAPSHOT.jar
```

预期输出：

```text
OryxOS 1.0.0-SNAPSHOT
```

CLI 打印 Maven 项目版本后退出。`chat`、`init`、`serve` 等业务子命令尚未实现。

## 启动应用骨架

```bash
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar
```

HTTP 服务默认监听 8080，追加 `--server.port=8081` 可改端口，Ctrl-C 退出。

启动不需要模型密钥或数据库配置。业务 API 与管理台尚未实现，访问 `/`、`/api/v1`、`/admin/` 返回 **404** 属于预期。

## 本地运行网站

在仓库根目录执行：

```bash
npm ci
npm run docs:dev
```

打开终端输出地址下的 `/OryxOS/`，中文首页为 `/OryxOS/zh/`。

```bash
npm run docs:build
npm run docs:preview
```

静态产物位于 `website/.vitepress/dist/`。
