# Quickstart & Verification Guide: 第 19 节 Notify 模块边界对齐

## 1. 环境准备

```bash
export ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home
```

## 2. 本节定向验证

```bash
# 只跑第 19 节新增的两个 harness 测试类与既有 NotifyContractTest
./tools/dev/mvnw -B -ntp -pl oryxos-tool test -Dtest=WebhookNotifyAdapterTest,NotifyToolsTest,NotifyContractTest

# 跑完整 us4-tools 分组（含新增 2 类共 29 项，自动触发 test_report_gate.py）
./tools/dev/mvnw -B -ntp test -Dgroups=us4-tools
```

## 3. 全量构建与静态门禁

```bash
# 全量编译、Spotless、Checkstyle/P3C、SpotBugs/FindSecBugs、PMD、全部 local 测试与分组核对
./tools/dev/mvnw -B -ntp clean verify

# 质量基线只减不增校验
./tools/dev/quality-baseline --check-shrink origin/main
```

## 4. 人工验收剩余项（harness 无法替代的真实外网与架构评审）

1. 配置真实企业微信/飞书/钉钉群机器人 webhook 环境变量并运行 `RealNotificationIT`（或通过 `oryxos chat` 触发 `notify`），确认群里实际收到推送消息。
2. 接口中立性评审：审阅 `NotifyChannelAdapter` 与 `NotifyTarget` 签名，确认替换为企业微信/飞书官方 SDK 实现时无需修改接口签名或 `NotifyTool`。
