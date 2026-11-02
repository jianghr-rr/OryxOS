---
title: Build & run
description: OryxOS Build & run — current capabilities and runtime design.
---

# Build & run

## Prerequisites

Use **JDK 21** and Node.js **22+** (24 recommended for the website). The Maven Wrapper downloads Maven 3.9.16 automatically. The first build requires internet access.

Set `JAVA_HOME` to your JDK 21 installation and make sure `java -version` and `./mvnw -version` report the intended Java version.

## Build all modules

```bash
git clone https://github.com/jianghr-rr/OryxOS.git
cd OryxOS
./mvnw clean package
```

On Windows, use `mvnw.cmd clean package`. This builds all nine modules and runs the application startup test.

## Run the CLI

```bash
java -jar oryxos-cli/target/oryxos-cli-1.0.0-SNAPSHOT.jar
```

Expected output:

```text
OryxOS 1.0.0-SNAPSHOT
```

The CLI prints the Maven project version and exits. Commands such as `chat`, `init`, and `serve` are not implemented yet.

## Start the application skeleton

```bash
java -jar oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar
```

The HTTP server starts on port 8080. Use `--server.port=8081` to change it. Press Ctrl-C to stop.

No model key or database configuration is required. Business APIs and the management UI are not present: requests to `/`, `/api/v1`, and `/admin/` return **404** as expected.

## Run this website locally

From the repository root:

```bash
npm ci
npm run docs:dev
```

Open the printed URL under `/OryxOS/`; the Chinese homepage is `/OryxOS/zh/`.

```bash
npm run docs:build
npm run docs:preview
```

Static output is written to `website/.vitepress/dist/`.
