# Quickstart & Verification Guide: 第 18 节 CLI 与会话层边界对齐

## 1. 环境准备

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
```

## 2. 本节定向验证

```bash
# 只跑第 18 节新增的两个 harness 测试类
./tools/dev/mvnw -B -ntp -pl oryxos-core,oryxos-storage test -Dtest=SessionManagerTest,SessionRepositoryTest

# 跑完整 us5-cli 分组（含 12 命令、轻重分流、新增会话 harness 共 22 项，自动触发 test_report_gate.py）
./tools/dev/mvnw -B -ntp test -Dgroups=us5-cli
```

## 3. 全量构建与静态门禁

```bash
# 全量编译、Spotless、Checkstyle/P3C、SpotBugs/FindSecBugs、PMD、全部 local 测试与分组核对
./tools/dev/mvnw -B -ntp clean verify

# 质量基线只减不增校验
./tools/dev/quality-baseline --check-shrink origin/main
```

## 4. 人工验收剩余项（harness 无法替代的进程/真模型体验）

1. 使用真实模型运行 `oryxos chat --profile <name> --user <user>` 完成多轮对话，验证 `/quit` 正常退出。
2. 人工体感轻命令（`oryxos init`、`oryxos profile list`、`oryxos --help`）秒回，重命令（`oryxos chat`、`oryxos serve`）才启动 Spring。
3. 人工核对跨模式（`chat` / `serve` / `gateway`）共享同一工作区配置与 SQLite 会话存储。
