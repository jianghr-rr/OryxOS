# F0-A 验证证据（2026-11-12）

覆盖 T001–T006；FR-030、FR-041、宪章 III/VI/VII。个人仓库 /Users/jianghaoran/github/OryxOS；JDK Temurin 21.0.13+9，Maven Wrapper 3.9.16，Boot 3.5.16。

- `tools/dev/mvnw -B -ntp clean verify`：父工程和九模块成功；Boot 随机端口上下文启动通过；发现3个入口/IT必跑用例，另有原启动测试。完整命令日志复制到 `.oryxos-dev/evidence/`。
- `tools/dev/mvnw -B -ntp test -Dgroups=f0-discovery`：3个必跑用例通过，核查本次 runId，非旧报告。
- 帮助/版本在实现前用子进程测试得到失败（旧入口启动Spring）；分发实现后两项均通过。
- 完整boot JAR `--help`/`--version` 和薄cli JAR `--version` 均退出0，版本1.0.0-SNAPSHOT；无Spring/Tomcat、无工作区创建、无模型/MCP调用。未知业务命令不返回假成功。
- `python3 tools/dev/test_report_gate.py`：漏IT、跳过、旧报告、缺必跑方法均拒绝。
- `help:effective-pom`、`dependency:tree` 均成功：AI1.1.8、MCP0.18.3、Hibernate两件6.6.53.Final、SQLite3.51.3.0、Picocli4.7.7、validator1.5.9、springdoc2.9.1。九模块依赖无环，cli不依赖boot，memory不依赖storage。
- 从/tmp调用个人入口仍固定个人仓库；缺失/错误JDK拒绝。未知us1分组、未指定real分组、模式不匹配及跳过测试均非零退出。
- 全局设置与官方实验仓库受跟踪文件共2416份前后哈希无变化；本轮未操作ACSP仓库。仅只读借用已有JDK，不调用课程run.sh。

本阶段没有Agent业务/API/会话/通知/记忆；无真实外部联调。按本轮授权继续F0-B，T018后停止。F0没有业务执行ID，外部业务副作用计数0。
