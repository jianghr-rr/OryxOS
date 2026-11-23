# P0：重构前完整校验基线（T001，SC-001 对照）

| 项 | 值 |
| --- | --- |
| 日期 | 2026-11-21 |
| 检出 | `main` @ `9055902caa99e54bd00be43f97be0cc6e4b2c40f`；计时开始时没有未提交的 Java 或 POM 改动（`git status` 为空） |
| 命令 | `time ./tools/dev/mvnw clean verify` |
| JDK | Temurin 21.0.13+9（`ORYXOS_JAVA_HOME` 指向本机已有的 JDK 21） |
| 机器 | Apple M4，10 核，16 GiB |
| 结果 | `BUILD SUCCESS`，退出码 0 |
| **T0** | **594 秒**（Maven 报告 `Total time: 09:52 min`） |
| 测试 | 各模块汇总 235 个测试类级结果行，0 失败、0 错误、0 跳过；报告门禁 `Test evidence PASS: 322 required cases` |

说明：

- 依赖已在本地缓存（`.oryxos-dev/repository`），T0 不含首次下载依赖的时间。之后的 SC-001 对比也在同一台机器、缓存已就绪的条件下测量。
- 本地默认分组包含 `kernel-soak-harness`，它占了 boot 模块测试的大部分耗时。
