# US5 第九批 Slice 5–6 性能排查与止损证据

- **日期**：2026-11-18（Asia/Shanghai）
- **结论**：`T101` **仍未通过，保持未勾选**（按路线 C 止损固化当前成果，不提交、不推送，未进入 `T102–T104`）。
- **验收时长调整**：按用户明确确认，已将性能基线配置文件与压测框架的验收时长从 `4 小时`（`14400s` / `PT4H`）调整为 **2 分钟**（`120s` / `PT2M` / `twoMinutes`）；`10 Agents / 100 Sessions`、`clientThinkMillis=0`、`modelDelayMillis=10000`、`toolDelayMillis=5000`、`sessionCreateP99AtMost200ms`、`everyTaskInternalAtMost50ms`、`everyTaskIncludingIngressAtMost50ms` 与完整 SQLite 审计要求均未放宽。

---

## 1. 本轮（Slice 5–6）实施的优化

1. **消除虚拟线程 `synchronized` 钉死（Slice 5）**
   - `RuntimeGate`、`StorageLifecycle`、`Execution`、`SessionManager` 改用 `ReentrantLock`；`StorageResources` 改用 `ReentrantReadWriteLock`；`WorkspaceProcessLock.isHeld()` 无锁读取；`ContextLoader` 与 `Redactor` 优化热路径分配。
2. **跨轮热会话缓存与写入合并（Slice 5–6）**
   - `SessionManager` 增加有界 `HotSession` 跨轮序列号与已完成轮次缓存（回滚/归档自动失效）；`ReActLoop` 合并零 I/O 后置写入；`SessionStore` / `JpaSessionStore` / `AgentService` 对现有会话内联 HTTP 请求合并 `lease.begin` 与首轮 `audit.model(STARTED)` 为单次提交屏障（[HttpReadPathTest.java](../../../oryxos-boot/src/test/java/io/github/oryxlabs/oryxos/boot/HttpReadPathTest.java) 验证 `db.write <= 4`、`db.read <= 1`）。
3. **SQLite WAL 与后台安静期检查点（Slice 6）**
   - [SqliteConnectionFactory.java](../../../oryxos-storage/src/main/java/io/github/oryxlabs/oryxos/storage/sqlite/SqliteConnectionFactory.java) 设置 `PRAGMA wal_autocheckpoint=0; PRAGMA cache_size=-16000; PRAGMA temp_store=MEMORY`（保持 `PRAGMA synchronous=FULL`）。
   - [WorkspaceWriteCoordinator.java](../../../oryxos-storage/src/main/java/io/github/oryxlabs/oryxos/storage/transaction/WorkspaceWriteCoordinator.java) 设置写窗口吸收上限 `500us`、`FlushModeType.COMMIT`，并用单线程 `ScheduledThreadPoolExecutor` 在安静期（`>= 60ms` 无写入、延迟 `80ms`、`CHECKPOINT_THRESHOLD_JOBS=80`）执行 `PASSIVE` + `RESTART` 检查点。
4. **HTTP 连接预热、Direct AsyncContext 与 ZGC 参数（Slice 5–6）**
   - [HttpExecutionAdapter.java](../../../oryxos-web/src/main/java/io/github/oryxlabs/oryxos/web/execution/HttpExecutionAdapter.java) 直接通过 `AsyncContext` 写入并显式设置 `Content-Length`；[pom.xml](../../../pom.xml) Surefire `<argLine>` 设为 `-XX:+UseZGC -XX:+ZGenerational -Xms3g -Xmx3g -XX:+AlwaysPreTouch -XX:ConcGCThreads=2 -XX:-ZProactive`。
   - [OpenAiClientFactory.java](../../../oryxos-provider/src/main/java/io/github/oryxlabs/oryxos/provider/OpenAiClientFactory.java) 与 [ControlledHttpClient.java](../../../oryxos-tool/src/main/java/io/github/oryxlabs/oryxos/tool/http/ControlledHttpClient.java) 在并发 `>= 4` 时异步预热 `64` 条空闲路由连接，并设 `validateAfterInactivity=15s`。

---

## 2. 最新实测验证结果

1. **全量本地回归（最后一次代码修改后执行，全绿）**
   - 命令：`ORYXOS_JAVA_HOME=/Users/jianghaoran/jikeshijian/.oryxos-course/tools/jdk-21.0.13+9/Contents/Home ./tools/dev/mvnw -B -ntp verify`
   - 结果：`EXIT_CODE=0`，`Test evidence PASS: 322 required cases; run 5d5857fe-fc39-4461-b760-4978a6469597`；Python 工具测试 `Ran 6 tests in 0.004s OK`。
   - 其中 2 分钟同负载诊断 `SoakDiagnosticIT`（`runId=5d5857fe-fc39-4461-b760-4978a6469597`）：
     - `completed = 500/500`，`failedTasks = 0`，`violations = 0`，`audit.verified = true`（`sessions=111`、`executions=520`、`messages=2080`、`llm_calls=1040`、`tool_invocations=520`、`missingAcknowledgedWrites=0`、`corruptOrForeignRecords=0`、`badToolCallPairing=0`）。
     - `creation.p99Millis = 4.59375ms`（`<= 200ms`）；`internal.p50Millis = 60.592375ms`、`internal.maxMillis = 112.525334ms`（`over50Millis = 384`）；`ingressIncludedInternal.p50Millis = 66.169833ms`、`ingressIncludedInternal.maxMillis = 118.492458ms`（`over50Millis = 431`）；`gcCount = 36` / `gcMillis = 418`。
2. **2 分钟短程门禁 `kernel-short-gate`（存档于 `.oryxos-dev/evidence/batch9-r5-short-gate-s6c/`，`runId=96b3332c-ffd4-4d32-8d93-4045d20deac3`）**
   - `completed = 500/500`，`failedTasks = 0`，`violations = 0`，`audit.verified = true`，`creation.p99Millis = 3.97675ms`，`gcCount = 24` / `gcMillis = 190`。
   - `internal`：`p50 = 61.873375ms`、`p95 = 87.420042ms`、`p99 = 103.571708ms`、`max = 112.139542ms`（`over50Millis = 379`）。
   - `ingressIncludedInternal`：`p50 = 67.103958ms`、`max = 118.6325ms`（`over50Millis = 426`）。

---

## 3. 未通过原因与缺口分析

- 从 Slice 4 末基线 `201.80ms` 优化至 `112.14ms`（`t2` 内部 P50 已压至 `48.33ms`），但 **500/500 全量最大 `<= 50ms`** 仍未满足，原因如下：
  1. **同进程外部桩虚拟线程超睡**：`SoakTiming` 固定只扣除配置延迟 `25000ms`，而 100 并发下外部桩 `Thread.sleep(10000/5000)` 实际超睡（`externalObservedNanos - 25000ms`）在 `96b3332c` 中每任务占 `11.0..30.7ms`。
  2. **同步落库与并发调度物理底噪**：在保留 `PRAGMA synchronous=FULL` 与每轮 4 次落库屏障的前提下，100 并发写入聚合提交、Tomcat 进出队列调度、JSON 序列化及 3 次 HTTP 往返仍占约 `35..55ms`（`t0` 另有冷建连与 JIT 预热开销）。
- 因此未启动 `kernel-soak`（避免在门禁未过时继续跑验收），`T101` 保持未勾选；`spec.md` / `tasks.md` / `requirements.md` 的 2 分钟文案暂按路线 C 止损口径同步说明于 `tasks.md`。
