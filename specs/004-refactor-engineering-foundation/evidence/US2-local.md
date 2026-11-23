# US2：日志、追踪与运维端点本地验证（T043，quickstart §2）

日期 2026-11-23，分支 `004-refactor-engineering-foundation`（HEAD `5b288ba` 加未提交的 US2 改动），Temurin 21.0.13+9，Apple M4。

## 自动化测试

| 命令 | 结果 |
| --- | --- |
| `./tools/dev/mvnw -B -pl oryxos-boot -am test -Dgroups=f004-observability,us5-published-contract,f0-foundation` | 退出码 0，`Test evidence PASS: 28 required cases`，耗时 37 秒；`PublishedOpenApiTest` 2/2 通过（19 操作 / 44 schema 零差异） |
| `./tools/dev/mvnw -B -pl oryxos-boot -am clean verify -Dgroups=f004-observability` | 退出码 0，`Test evidence PASS: 12 required cases`，`Quality gate PASS: 460 baseline entries` |
| `./tools/dev/mvnw -B clean verify`（修复两条门禁发现之前） | 测试全部通过：`Test evidence PASS: 334 required cases`（T001 的 322 例加 `f004-observability` 的 12 例），耗时 636 秒；门禁拦下新代码中的 2 条发现，处理见下文 |

测试有效性的反向检查：临时把 `RequestTrace.currentOrNew()` 改成总是生成新 ID，`TraceCorrelationTest` 4 个用例中有 3 个失败；源码随后还原，与备份逐字节一致。

全量验证时门禁拦下的两条发现及处理方式：

| 发现 | 处理 |
| --- | --- |
| `pmd SystemPrintln GuardedRollingFileAppender.java:21` | 契约要求在日志系统不可用时向 stderr 输出 `[oryxos-logging] WARN ...`，而 Logback 的状态信息默认不可见，因此按契约在 `config/pmd/exclude-pmd.properties` 中登记一条带 `reason` 与登记日期的例外（与 CLI 的两条同类例外相同），没有放进基线，也没有在代码里加抑制注解 |
| `spotbugs NM_CLASS_NOT_EXCEPTION RedactingConverters.java:22` | 把内部类 `RedactingConverters$Exception` 改名为 `$Throwable`，`logback-spring.xml` 中的转换规则同步修改；`%redactedException` 这个转换词不变 |

## 手工验证（quickstart 2.1–2.9）

脚本在临时工作区 `/tmp/oryxos-004` 上执行。端口改用 `SERVER_PORT=18080`，因为 8080 正被维护者自己的开发服务占用，该服务未受影响。每次启动的服务在检查结束后都已停止。

| # | 实际结果 | 结论 |
| --- | --- | --- |
| 2.1 | `oryxos.json` 共 36 行（启动加 10 余个请求，不足 100 行），每行都能被 JSON 解析，均含 `@timestamp level logger_name thread_name message application`，`application=oryxos` | 通过 |
| 2.2 | 10 个指向不存在会话的请求，返回的 `traceId` 两两不同，在日志文件中 10/10 命中 | 通过 |
| 2.3 | `X-Request-ID: probe-abcdef12` 的响应 `traceId` 为 `probe-abcdef12`；`X-Request-ID: bad id` 得到新生成的小写 UUID v4 | 通过 |
| 2.4 | `/actuator/health`、`/health/liveness`、`/health/readiness` 都是 `UP`；health 响应只有 `{"status":"UP","groups":["liveness","readiness"]}`，不含组件详情 | 通过 |
| 2.5 | `/actuator/prometheus` 中 `jvm_memory_used_bytes`、`http_server_requests_seconds_count`、`process_uptime_seconds` 都存在，且带 `application="oryxos"` | 通过 |
| 2.6 | `/v3/api-docs` 中 `actuator` 出现 0 次 | 通过 |
| 2.7 | 不带 `prod`，并经 `script` 提供终端时，stderr 是带 ANSI 颜色的文本，没有 JSON；日志目录没有新增文件 | 通过 |
| 2.8 | `ORYXOS_LOG_DIR=/proc/forbidden`（macOS 上无法创建）加 `prod` 启动：服务正常启动，health 为 `UP`；stderr 含 `[oryxos-logging] WARN log directory not writable` | 通过 |
| 2.9 | 用 `git archive 9055902` 构建重构前的 JAR，`--version` 与 `--root /tmp/oryxos-004 status` 的输出和退出码，与当前 JAR 逐字节一致 | 通过 |

## 与任务描述的差异

- 日志目录与滚动参数使用 Logback `springProperty`，属性来源为 `oryxos.log.*`。环境变量 `ORYXOS_LOG_DIR`、`ORYXOS_LOG_MAX_FILE_SIZE` 等通过 Spring 宽松绑定生效，与 T028 的写法一致。
- T038 只列了两个转换器。实际多了 `%redactedMdc`，用于在文本格式中对 MDC 值脱敏，以满足 T027“MDC 值也要脱敏”的要求。
- `AgentService` 在任务结束时多输出一行 DEBUG 日志：`execution_finished status=… errorCode=…`。`TraceCorrelationTest` 用它验证异步执行的日志带有 `traceId` 和 `executionId`；生产环境默认是 INFO 级别，不会输出这一行。
- 健康组配置了 `management.endpoint.health.validate-group-membership: false`。`oryxRuntime` 指示器由 `WebApiConfiguration` 中的 `@Bean("oryxRuntime")` 注册，而该配置类带 `@ConditionalOnWebApplication(SERVLET)`，在非 Servlet 的 Spring 上下文里不存在。关闭校验可以避免这类上下文因健康组引用了不存在的指示器而启动失败。
- 当 `X-Request-ID` 不合法或缺失时，`RequestTrace` 用现有的 `Uuids.v4()` 生成 ID，没有直接调用 `UUID.randomUUID()`。`Uuids` 的 `PREDICTABLE_RANDOM` 发现仍保留在基线中，见 `US1-security-triage.md`。

## 环境注意事项

- 第一次运行 `f004-observability` 时 12 个用例全部报 `NoClassDefFoundError: HttpExecutionAdapter`。原因是 IDE 后台的 Eclipse 编译器把一个带 “Unresolved compilation problems” 的 `WebApiConfiguration.class` 写进了 `oryxos-boot/target/classes`，Maven 又沿用了这份增量产物。加上 `clean` 后问题消失，代码本身没有问题。如果编辑器开着 Java 插件，本地验证建议都带上 `clean`。
- `clean` 会删掉并重建 `oryxos-boot/target/oryxos-boot-1.0.0-SNAPSHOT.jar`，而 8080 上那个已运行的服务正是从这个路径启动的。它已打开的旧文件句柄仍然有效，但如果要让它用上新代码，需要维护者自己重启。
