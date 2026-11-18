package io.github.oryxlabs.oryxos.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.audit.*;
import io.github.oryxlabs.oryxos.storage.execution.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.*;
import io.github.oryxlabs.oryxos.storage.query.SqliteReadOnlyQueries;
import io.github.oryxlabs.oryxos.storage.session.*;
import io.github.oryxlabs.oryxos.storage.transaction.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("storage-faults")
class PersistentStorageContractTest {
  @TempDir Path root;

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void upgradingV2PreservesMemoryLedgerAndChecksums() throws Exception {
    var w = new WorkspaceHandle(root);
    var wid = new WorkspaceInitializer().initialize(w, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(w)) {
      try (var pool =
          new io.github.oryxlabs.oryxos.storage.sqlite.SqliteConnectionFactory().create(w)) {
        var migrations =
            new ArrayList<io.github.oryxlabs.oryxos.storage.migration.SchemaMigrator.Migration>();
        for (var item :
            Map.of(1, "V001__schema_metadata.sql", 2, "V002__memory_ledger.sql").entrySet())
          try (var in = getClass().getResourceAsStream("/db/migration/" + item.getValue())) {
            migrations.add(
                new io.github.oryxlabs.oryxos.storage.migration.SchemaMigrator.Migration(
                    item.getKey(),
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
          }
        migrations.sort(
            java.util.Comparator.comparingInt(
                io.github.oryxlabs.oryxos.storage.migration.SchemaMigrator.Migration::version));
        new io.github.oryxlabs.oryxos.storage.migration.SchemaMigrator(migrations).migrate(pool);
        try (var c = pool.getConnection();
            var q =
                c.prepareStatement(
                    "INSERT INTO"
                        + " memory_entries(id,workspace_id,scope,raw_content,created_at,source_execution_id,source_agent_name,backend,write_status)"
                        + " VALUES(?,?,?,?,?,?,?,?,?)")) {
          int n = 1;
          for (var value :
              List.of(
                  UUID.randomUUID().toString(),
                  wid.toString(),
                  "CORE",
                  "existing remembered fact",
                  Instant.now().toString(),
                  UUID.randomUUID().toString(),
                  "agent",
                  "sqlite",
                  "CONFIRMED")) q.setString(n++, value);
          q.executeUpdate();
        }
      }
      try (var resources = new StorageResourceFactory().open(w, lock);
          var c = resources.dataSource().getConnection();
          var s2 = c.createStatement()) {
        try (var rows = s2.executeQuery("SELECT raw_content FROM memory_entries")) {
          assertTrue(rows.next());
          assertEquals("existing remembered fact", rows.getString(1));
        }
        try (var rows = s2.executeQuery("SELECT count(*) FROM schema_migrations")) {
          assertEquals(7, rows.getInt(1));
        }
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void structuredMessagesPaginationModelSnapshotAndForeignKeysPersist() throws Exception {
    var w = new WorkspaceHandle(root);
    UUID wid = new WorkspaceInitializer().initialize(w, Map.of());
    UUID sid = UUID.randomUUID();
    var session =
        new Session(
            sid,
            "cli",
            "alice",
            "agent",
            "p",
            "m",
            Session.Kind.CONVERSATION,
            Session.Status.ACTIVE,
            Instant.now());
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock)) {
      var gate = new RuntimeGate();
      gate.readyAtFirstStartup();
      var writes = new WorkspaceWriteCoordinator(() -> resources, gate);
      var sessions = new JpaSessionStore(wid, writes);
      var executions = new JpaExecutionStore(writes);
      for (int i = 0; i < 53; i++) {
        var e = execution(sid);
        sessions.begin(
            session,
            i == 0,
            e.snapshot(0),
            message(sid, e, i * 2 + 1, ChatMessage.user("turn " + i)),
            executions);
        var assistant =
            new ChatMessage(
                ChatMessage.Role.ASSISTANT,
                "reply " + i,
                List.of(),
                null,
                null,
                new ChatMessage.ProviderOpaque("p", "m", "opaque-private"));
        sessions.append(message(sid, e, i * 2 + 2, assistant));
        sessions.finishTurn(sid, e.turnId());
        e.finish(Execution.Status.SUCCEEDED, "FINAL_REPLY", "reply", null, Instant.now());
        executions.save(e.snapshot(0));
      }
      assertEquals(53, sessions.completedTurns(sid).size());
      assertEquals(
          "opaque-private",
          sessions.messages(sid).getLast().message().providerOpaque().reasoningContent());
      var q = new SqliteReadOnlyQueries(w);
      var page = q.history(sid, null, 100);
      assertEquals(100, page.size());
      assertEquals(7, page.getFirst().seq());
      assertEquals(106, page.getLast().seq());
      assertEquals(6, q.history(sid, 7L, 100).size());
      assertTrue(page.stream().allMatch(m -> m.message().providerOpaque() == null));
      assertEquals("m", q.session(sid).model());
      var invalid = message(sid, execution(sid), 107, ChatMessage.user("orphan"));
      assertThrows(RuntimeException.class, () -> sessions.append(invalid));
      assertEquals(106, sessions.messages(sid).size());
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, gate.state());
    }
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock)) {
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
      var sessions = new JpaSessionStore(wid, writes);
      assertEquals(106, sessions.messages(sid).size());
      sessions.archive(sid, Instant.now());
      assertNotNull(sessions.find(sid).archivedAt());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void auditsRetainFailuresCorrectionLinksDenialsUnknownsAndNullUsage() throws Exception {
    var w = new WorkspaceHandle(root);
    UUID wid = new WorkspaceInitializer().initialize(w, Map.of());
    var sid = UUID.randomUUID();
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock)) {
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
      var e = execution(sid);
      var sessions = new JpaSessionStore(wid, writes);
      var executions = new JpaExecutionStore(writes);
      sessions.begin(
          new Session(
              sid,
              "cli",
              "alice",
              "agent",
              "p",
              "m",
              Session.Kind.CONVERSATION,
              Session.Status.ACTIVE,
              Instant.now()),
          true,
          e.snapshot(0),
          message(sid, e, 1, ChatMessage.user("hello")),
          executions);
      var redactor = new Redactor();
      redactor.register("SECRET_CANARY");
      var audit = new JpaAuditSink(writes, redactor);
      var now = Instant.now();
      var model = UUID.randomUUID();
      audit.modelCall(
          new ModelCallRecord(
              model, e.id(), 1, 1, "p", "m", null, "STARTED", now, null, null, null, null, null));
      audit.modelCall(
          new ModelCallRecord(
              model,
              e.id(),
              1,
              1,
              "p",
              "m",
              "actual",
              "SUCCEEDED",
              now,
              now,
              3L,
              null,
              null,
              null));
      var failed = UUID.randomUUID();
      audit.toolCall(tool(failed, e.id(), "bad", 0, "ERROR", "UNRESOLVED", null, null));
      var success = UUID.randomUUID();
      audit.toolCall(tool(success, e.id(), "fixed", 1, "STARTED", "NOT_APPLICABLE", null, null));
      audit.toolCall(tool(success, e.id(), "fixed", 1, "SUCCESS", "NOT_APPLICABLE", null, null));
      audit.toolCall(tool(failed, e.id(), "bad", 0, "ERROR", "RESOLVED", null, "fixed"));
      audit.toolCall(tool(success, e.id(), "fixed", 1, "SUCCESS", "NOT_APPLICABLE", "bad", null));
      audit.toolCall(
          tool(UUID.randomUUID(), e.id(), "denied", 0, "DENIED", "UNRESOLVED", null, null));
      audit.toolCall(
          tool(UUID.randomUUID(), e.id(), "unknown", 1, "UNKNOWN", "UNRESOLVED", null, null));
      audit.toolCall(
          tool(
              UUID.randomUUID(), e.id(), "remaining", 0, "NOT_EXECUTED", "UNRESOLVED", null, null));
      var q = new SqliteReadOnlyQueries(w);
      var rows = q.toolAttempts(e.id());
      assertEquals(5, rows.size());
      var bad =
          rows.stream().filter(r -> r.get("tool_call_id").equals("bad")).findFirst().orElseThrow();
      assertEquals("ERROR", bad.get("status"));
      assertEquals("RESOLVED", bad.get("resolution_status"));
      assertEquals("fixed", bad.get("resolved_by_call_id"));
      assertFalse(rows.toString().contains("SECRET_CANARY"));
      assertFalse(rows.toString().contains("https://private"));
      var m = q.modelAttempts(e.id()).getFirst();
      assertEquals("actual", m.get("model"));
      assertNull(m.get("input_tokens"));
      assertNull(m.get("output_tokens"));
      assertThrows(
          RuntimeException.class,
          () ->
              audit.toolCall(tool(failed, e.id(), "bad", 0, "SUCCESS", "RESOLVED", null, "fixed")));
      assertEquals(
          "ERROR",
          q.toolAttempts(e.id()).stream()
              .filter(r -> r.get("tool_call_id").equals("bad"))
              .findFirst()
              .orElseThrow()
              .get("status"));
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void writerQueueIsBoundedAndWaitingUsesRemainingBudget() throws Exception {
    var w = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(w, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new RuntimeGate();
      gate.readyAtFirstStartup();
      var writes = new WorkspaceWriteCoordinator(() -> resources, gate);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var first =
          workers.submit(
              () ->
                  writes.write(
                      em -> {
                        entered.countDown();
                        try {
                          release.await();
                        } catch (InterruptedException e) {
                          throw new IllegalStateException(e);
                        }
                        return null;
                      }));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      var waiting = new ArrayList<Future<?>>();
      for (int i = 0; i < 256; i++) waiting.add(workers.submit(() -> writes.write(em -> null)));
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (writes.queued() < 256 && System.nanoTime() < end) Thread.sleep(2);
      assertEquals(256, writes.queued());
      assertThrows(RuntimeException.class, () -> writes.write(em -> null));
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, gate.state());
      release.countDown();
      first.get(3, TimeUnit.SECONDS);
      for (var f : waiting) f.get(3, TimeUnit.SECONDS);
      var entered2 = new CountDownLatch(1);
      var release2 = new CountDownLatch(1);
      var second =
          workers.submit(
              () ->
                  writes.write(
                      em -> {
                        entered2.countDown();
                        try {
                          release2.await();
                        } catch (InterruptedException e) {
                          throw new IllegalStateException(e);
                        }
                        return null;
                      }));
      assertTrue(entered2.await(2, TimeUnit.SECONDS));
      long start = System.nanoTime();
      assertThrows(
          RuntimeException.class,
          () -> writes.write(UUID.randomUUID(), Duration.ofMillis(30), em -> null));
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
      release2.countDown();
      second.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  @Tag("kernel-group-timing")
  void groupTimingRetainsAnotherMembersWorkUntilTheSharedCommit() throws Exception {
    var workspace = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(workspace, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var resources = new StorageResourceFactory().open(workspace, lock);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new RuntimeGate();
      gate.readyAtFirstStartup();
      new WorkspaceWriteCoordinator(() -> resources, gate)
          .write(em -> StoredSql.update(em, "CREATE TABLE group_timing (id INTEGER)"));
      var starter = new java.util.concurrent.atomic.AtomicReference<Runnable>();
      var writes = new WorkspaceWriteCoordinator(() -> resources, gate, starter::set);
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var spans =
          List.of(
              new CopyOnWriteArrayList<RuntimeTiming.Segment>(),
              new CopyOnWriteArrayList<RuntimeTiming.Segment>());
      var calls = new ArrayList<Future<?>>();
      for (int i = 0; i < 2; i++) {
        int id = i;
        calls.add(
            workers.submit(
                () -> {
                  try (var timing = RuntimeTiming.bind(spans.get(id)::add)) {
                    return writes.write(
                        em -> {
                          StoredSql.update(em, "INSERT INTO group_timing VALUES (?)", id);
                          if (id == 1) {
                            entered.countDown();
                            awaitLatch(release);
                          }
                          return null;
                        });
                  }
                }));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (writes.queued() < i + 1 && System.nanoTime() < until) Thread.sleep(1);
        assertEquals(i + 1, writes.queued());
      }
      var writer = Thread.ofPlatform().start(starter.get());
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Thread.sleep(40);
        assertTrue(calls.stream().noneMatch(Future::isDone));
      } finally {
        release.countDown();
      }
      for (var call : calls) call.get(5, TimeUnit.SECONDS);
      writer.join();
      for (var own : spans) {
        var byName =
            own.stream()
                .filter(
                    s ->
                        Set.of(
                                "db.group.before",
                                "db.group.after",
                                "db.action",
                                "db.commit",
                                "db.acknowledge.queue",
                                "db.write")
                            .contains(s.name()))
                .collect(java.util.stream.Collectors.toMap(RuntimeTiming.Segment::name, s -> s));
        assertTrue(byName.containsKey("db.group.before"));
        assertTrue(byName.containsKey("db.group.after"));
        assertTrue(
            byName.get("db.group.before").endNanos() <= byName.get("db.action").startNanos());
        assertTrue(byName.get("db.group.after").startNanos() >= byName.get("db.action").endNanos());
        assertTrue(byName.get("db.group.after").endNanos() <= byName.get("db.commit").startNanos());
        assertTrue(
            byName.get("db.acknowledge.queue").startNanos() >= byName.get("db.commit").endNanos());
        assertTrue(
            byName.get("db.write").endNanos() >= byName.get("db.acknowledge.queue").endNanos());
      }
      var waiting =
          spans.getFirst().stream()
              .filter(s -> s.name().equals("db.group.after"))
              .findFirst()
              .orElseThrow();
      assertTrue(waiting.endNanos() - waiting.startNanos() >= TimeUnit.MILLISECONDS.toNanos(30));
      assertEquals(
          2,
          ((Number)
                  writes.read(
                      em ->
                          em.createNativeQuery("SELECT count(*) FROM group_timing")
                              .getSingleResult()))
              .intValue());
    }
  }

  @Test
  @Tag("kernel-group-timing")
  void tricklingArrivalsDoNotHoldInitialBatchMemberBeyondBoundedAbsorptionWindow() throws Exception {
    var workspace = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(workspace, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var resources = new StorageResourceFactory().open(workspace, lock);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new RuntimeGate();
      gate.readyAtFirstStartup();
      var writes = new WorkspaceWriteCoordinator(() -> resources, gate);
      writes.write(em -> StoredSql.update(em, "CREATE TABLE trickle_probe (id INTEGER PRIMARY KEY)"));
      var firstEntered = new CountDownLatch(1);
      var firstRelease = new CountDownLatch(1);
      var initialStarted = new java.util.concurrent.atomic.AtomicLong();
      var first =
          workers.submit(
              () ->
                  writes.write(
                      em -> {
                        firstEntered.countDown();
                        awaitLatch(firstRelease);
                        initialStarted.set(System.nanoTime());
                        StoredSql.update(em, "INSERT INTO trickle_probe VALUES (0)");
                        return null;
                      }));
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
      var trickles = new CopyOnWriteArrayList<Future<?>>();
      trickles.add(
          workers.submit(
              () ->
                  writes.write(
                      em -> {
                        StoredSql.update(em, "INSERT INTO trickle_probe VALUES (1)");
                        awaitMillis(2);
                        return null;
                      })));
      long waitQueued = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (writes.queued() < 1 && System.nanoTime() < waitQueued) Thread.sleep(1);
      assertEquals(1, writes.queued());
      var feeder =
          workers.submit(
              () -> {
                for (int i = 2; i <= 25; i++) {
                  int id = i;
                  Thread.sleep(1);
                  trickles.add(
                      workers.submit(
                          () ->
                              writes.write(
                                  em -> {
                                    StoredSql.update(
                                        em, "INSERT INTO trickle_probe VALUES (?)", id);
                                    awaitMillis(2);
                                    return null;
                                  })));
                }
                return null;
              });
      firstRelease.countDown();
      first.get(5, TimeUnit.SECONDS);
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - initialStarted.get());
      feeder.get(5, TimeUnit.SECONDS);
      for (var t : trickles) t.get(5, TimeUnit.SECONDS);
      assertTrue(
          elapsedMs < 25,
          () ->
              "Trickling arrivals held initial batch member for "
                  + elapsedMs
                  + "ms instead of bounding arrival absorption");
      assertEquals(
          26,
          ((Number)
                  writes.read(
                      em ->
                          em.createNativeQuery("SELECT count(*) FROM trickle_probe")
                              .getSingleResult()))
              .intValue());
    }
  }

  private static void awaitMillis(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  @Test
  @Tag("kernel-group-timing")
  void concurrentReadersNeverStarveSoleWriterWithinFourConnectionPool() throws Exception {
    var workspace = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(workspace, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var resources = new StorageResourceFactory().open(workspace, lock);
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        var writerWorkers = Executors.newVirtualThreadPerTaskExecutor()) {
      assertEquals(4, resources.dataSource().getMaximumPoolSize());
      var gate = new RuntimeGate();
      gate.readyAtFirstStartup();
      var writes = new WorkspaceWriteCoordinator(() -> resources, gate);
      var entered = new CountDownLatch(3);
      var allEntered = new java.util.concurrent.atomic.AtomicInteger();
      var release = new CountDownLatch(1);
      var readJobs = new ArrayList<Future<?>>();
      for (int i = 0; i < 4; i++) {
        readJobs.add(
            readers.submit(
                () ->
                    writes.readSql(
                        connection -> {
                          allEntered.incrementAndGet();
                          entered.countDown();
                          awaitLatch(release);
                          try (var s = connection.createStatement();
                              var r = s.executeQuery("SELECT 1")) {
                            assertTrue(r.next());
                          }
                          return null;
                        })));
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      Thread.sleep(30);
      try {
        var writeResult =
            writerWorkers.submit(
                () -> {
                  assertTrue(resources.isOpen());
                  return writes.write(
                      em ->
                          StoredSql.update(
                              em, "CREATE TABLE writer_slot_probe (id INTEGER PRIMARY KEY)"));
                });
        assertDoesNotThrow(() -> writeResult.get(500, TimeUnit.MILLISECONDS));
      } finally {
        release.countDown();
      }
      for (var job : readJobs) job.get(5, TimeUnit.SECONDS);
      assertEquals(4, allEntered.get());
    }
  }

  @Test
  @Tag("kernel-group-timing")
  void sessionAppendAndFinishTurnReuseStatementsWithinBatch() throws Exception {
    var workspace = new WorkspaceHandle(root);
    UUID wid = new WorkspaceInitializer().initialize(workspace, Map.of());
    UUID sid = UUID.randomUUID();
    var session =
        new Session(
            sid,
            "cli",
            "alice",
            "agent",
            "p",
            "m",
            Session.Kind.CONVERSATION,
            Session.Status.ACTIVE,
            Instant.now());
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var resources = new StorageResourceFactory().open(workspace, lock);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new RuntimeGate();
      gate.readyAtFirstStartup();
      var seedWrites = new WorkspaceWriteCoordinator(() -> resources, gate);
      var seedSessions = new JpaSessionStore(wid, seedWrites);
      var seedExecutions = new JpaExecutionStore(seedWrites);
      for (int i = 0; i < 100; i++) {
        var e = execution(sid);
        seedSessions.begin(
            session,
            i == 0,
            e.snapshot(0),
            message(sid, e, i * 2L + 1, ChatMessage.user("seed-u-" + i)),
            seedExecutions);
        seedSessions.append(
            message(
                sid,
                e,
                i * 2L + 2,
                new ChatMessage(ChatMessage.Role.ASSISTANT, "seed-a-" + i, List.of(), null, null, null)));
        seedSessions.finishTurn(sid, e.turnId());
      }
      var batchExecutions = new ArrayList<Execution>();
      for (int i = 0; i < 5; i++) {
        var e = execution(sid);
        batchExecutions.add(e);
        seedSessions.begin(
            session,
            false,
            e.snapshot(0),
            message(sid, e, 200L + i * 2L + 1, ChatMessage.user("batch-u-" + i)),
            seedExecutions);
      }
      var starter = new java.util.concurrent.atomic.AtomicReference<Runnable>();
      var writes = new WorkspaceWriteCoordinator(() -> resources, gate, starter::set);
      var sessions = new JpaSessionStore(wid, writes);
      var spans = new ConcurrentLinkedQueue<RuntimeTiming.Segment>();
      var calls = new ArrayList<Future<?>>();
      for (int i = 0; i < 5; i++) {
        int idx = i;
        var e = batchExecutions.get(idx);
        calls.add(
            workers.submit(
                () -> {
                  try (var timing = RuntimeTiming.bind(spans::add)) {
                    sessions.append(
                        message(
                            sid,
                            e,
                            200L + idx * 2L + 2,
                            new ChatMessage(
                                ChatMessage.Role.ASSISTANT,
                                "batch-a-" + idx,
                                List.of(),
                                null,
                                null,
                                null)));
                    return null;
                  }
                }));
      }
      long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (writes.queued() < 5 && System.nanoTime() < until) Thread.sleep(1);
      assertEquals(5, writes.queued());
      var writer = Thread.ofPlatform().start(starter.get());
      for (var call : calls) call.get(5, TimeUnit.SECONDS);
      writer.join(5000);
      starter.set(null);
      calls.clear();
      for (int i = 0; i < 5; i++) {
        var e = batchExecutions.get(i);
        calls.add(
            workers.submit(
                () -> {
                  try (var timing = RuntimeTiming.bind(spans::add)) {
                    sessions.finishTurn(sid, e.turnId());
                    return null;
                  }
                }));
      }
      until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (writes.queued() < 5 && System.nanoTime() < until) Thread.sleep(1);
      assertEquals(5, writes.queued());
      writer = Thread.ofPlatform().start(starter.get());
      for (var call : calls) call.get(5, TimeUnit.SECONDS);
      writer.join(5000);
      long prepares = spans.stream().filter(s -> s.name().equals("db.statement.prepare")).count();
      assertTrue(
          prepares >= 4 && prepares <= 5,
          () ->
              "Expected all 4 distinct SQL shapes (context, insert, turn select, turn update) to"
                  + " prepare via StoredSql cache once per batch, got "
                  + prepares);
      assertEquals(210, sessions.messages(sid).size());
      assertEquals(105, sessions.completedTurns(sid).size());
    }
  }

  @Test
  @Tag("kernel-timing")
  void timingRetainsQueueWaitReadAndDurableCommit() throws Exception {
    var w = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(w, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var first =
          workers.submit(
              () -> {
                // Hold the completed first commit, so the second request must use a fresh
                // transaction.
                try (var timing =
                    RuntimeTiming.bind(
                        segment -> {
                          if (segment.name().equals("db.commit")) {
                            entered.countDown();
                            awaitLatch(release);
                          }
                        })) {
                  writes.write(
                      em -> StoredSql.update(em, "CREATE TABLE timing_probe (id INTEGER)"));
                }
              });
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var segments = new CopyOnWriteArrayList<RuntimeTiming.Segment>();
      var second =
          workers.submit(
              () -> {
                try (var scope = RuntimeTiming.bind(segments::add)) {
                  writes.write(em -> StoredSql.update(em, "INSERT INTO timing_probe VALUES (1)"));
                  writes.read(em -> em.createNativeQuery("select 1").getSingleResult());
                }
              });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (writes.queued() == 0 && System.nanoTime() < deadline) Thread.sleep(1);
      assertEquals(1, writes.queued());
      Thread.sleep(30);
      release.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
      var byName =
          segments.stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      RuntimeTiming.Segment::name, segment -> segment));
      var queue = byName.get("db.queue");
      assertTrue(queue.endNanos() - queue.startNanos() >= 30_000_000L);
      assertTrue(
          byName
              .keySet()
              .containsAll(
                  Set.of(
                      "db.read",
                      "db.read.connection",
                      "db.read.query",
                      "db.begin",
                      "db.action",
                      "db.statement.prepare",
                      "db.statement.bind",
                      "db.statement.execute",
                      "db.commit",
                      "db.acknowledge.queue",
                      "db.write")));
      assertTrue(byName.get("db.write").startNanos() <= queue.startNanos());
      assertTrue(byName.get("db.write").endNanos() >= byName.get("db.commit").endNanos());
    }
  }

  @Test
  @Tag("kernel-history")
  void boundedHistoryPreservesSequenceIncompleteTurnsOpaqueAndRestart() throws Exception {
    var w = new WorkspaceHandle(root);
    UUID wid = new WorkspaceInitializer().initialize(w, Map.of());
    UUID sid = UUID.randomUUID(), current = null;
    var session =
        new Session(
            sid,
            "cli",
            "alice",
            "agent",
            "p",
            "m",
            Session.Kind.CONVERSATION,
            Session.Status.ACTIVE,
            Instant.now());
    SessionHistoryPort.History expected = null;
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock)) {
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
      var sessions = new JpaSessionStore(wid, writes);
      var executions = new JpaExecutionStore(writes);
      for (int i = 0; i < 60; i++) {
        var e = execution(sid);
        current = e.turnId();
        sessions.begin(
            session,
            i == 0,
            e.snapshot(0),
            message(sid, e, i * 2 + 1, ChatMessage.user("turn " + i)),
            executions);
        sessions.append(
            message(
                sid,
                e,
                i * 2 + 2,
                new ChatMessage(
                    ChatMessage.Role.ASSISTANT,
                    "reply " + i,
                    List.of(),
                    null,
                    null,
                    new ChatMessage.ProviderOpaque("p", "m", "continuation " + i))));
        if (i % 2 == 0) sessions.finishTurn(sid, e.turnId());
      }
      expected =
          new HistorySelector()
              .select(sessions.messages(sid), sessions.completedTurns(sid), current, 4);
      assertEquals(120, sessions.lastSequence(sid));
      assertEquals(2, sessions.turnMessages(sid, current).size());
      org.hibernate.stat.Statistics stats;
      try (var em = resources.openEntityManager()) {
        stats =
            em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
      }
      stats.setStatisticsEnabled(true);
      stats.clear();
      assertEquals(expected, sessions.history(sid, current, 4));
      assertTrue(
          stats.getEntityLoadCount() <= 12,
          "History must materialize only four complete turns plus the active turn, not all 120"
              + " messages");
      stats.setStatisticsEnabled(false);
    }
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock)) {
      var sessions =
          new JpaSessionStore(
              wid, new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate()));
      assertEquals(120, sessions.lastSequence(sid));
      assertEquals(expected, sessions.history(sid, current, 4));
      assertEquals(
          120, sessions.messages(sid).size(), "Optimization must not delete historical records");
    }
  }

  @Test
  @Tag("kernel-history")
  void historyQueryWorkDoesNotGrowWithArchivedTurns() throws Exception {
    var w = new WorkspaceHandle(root);
    UUID wid = new WorkspaceInitializer().initialize(w, Map.of()), sid = UUID.randomUUID();
    var session =
        new Session(
            sid,
            "cli",
            "alice",
            "agent",
            "p",
            "m",
            Session.Kind.CONVERSATION,
            Session.Status.ACTIVE,
            Instant.now());
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock)) {
      resources.dataSource().setMaximumPoolSize(1);
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
      var sessions = new JpaSessionStore(wid, writes);
      var executions = new JpaExecutionStore(writes);
      long small = 0, large = 0, sequenceSmall = 0, sequenceLarge = 0;
      for (int i = 0; i < 400; i++) {
        var e = execution(sid);
        sessions.begin(
            session,
            i == 0,
            e.snapshot(0),
            message(sid, e, i * 2 + 1, ChatMessage.user("turn " + i)),
            executions);
        sessions.append(
            message(
                sid,
                e,
                i * 2 + 2,
                new ChatMessage(
                    ChatMessage.Role.ASSISTANT, "reply " + i, List.of(), null, null, null)));
        sessions.finishTurn(sid, e.turnId());
        if (i == 19 || i == 399) {
          var steps = new java.util.concurrent.atomic.AtomicLong();
          try (var c = resources.dataSource().getConnection()) {
            org.sqlite.ProgressHandler.setHandler(
                c.unwrap(org.sqlite.SQLiteConnection.class),
                1,
                new org.sqlite.ProgressHandler() {
                  protected int progress() {
                    steps.incrementAndGet();
                    return 0;
                  }
                });
          }
          long historyWork;
          try {
            var history = sessions.history(sid, e.turnId(), 4);
            assertEquals(10, history.recent().size() + history.current().size());
            historyWork = steps.getAndSet(0);
            assertEquals(i * 2 + 2, sessions.lastSequence(sid));
          } finally {
            try (var c = resources.dataSource().getConnection()) {
              org.sqlite.ProgressHandler.clearHandler(c.unwrap(org.sqlite.SQLiteConnection.class));
            }
          }
          if (i == 19) {
            small = historyWork;
            sequenceSmall = steps.get();
          } else {
            large = historyWork;
            sequenceLarge = steps.get();
          }
        }
      }
      assertTrue(small > 0, "Measure actual SQLite VM work, not wall-clock noise");
      assertTrue(
          large <= small * 2,
          "A 20x history increase must not scan 20x messages: small=" + small + ", large=" + large);
      assertTrue(sequenceSmall > 0);
      assertTrue(
          sequenceLarge <= sequenceSmall * 2,
          "Sequence lookup must seek the final index entry: small="
              + sequenceSmall
              + ", large="
              + sequenceLarge);
      assertEquals(800, sessions.messages(sid).size(), "All durable history remains readable");
    }
  }

  @Test
  @Tag("kernel-batching")
  void queuedWritesPublishOnlyAfterSharedCommitAndRollbackTogether() throws Exception {
    var w = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(w, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      try (var c = resources.dataSource().getConnection();
          var sql = c.createStatement()) {
        sql.execute("CREATE TABLE batching_probe (id INTEGER PRIMARY KEY)");
      }
      var gate = new RuntimeGate();
      gate.readyAtFirstStartup();
      var starter = new java.util.concurrent.atomic.AtomicReference<Runnable>();
      var writes = new WorkspaceWriteCoordinator(() -> resources, gate, starter::set);
      var jobs = new ArrayList<Future<?>>();
      for (int i = 0; i < 100; i++) {
        int id = i;
        jobs.add(
            workers.submit(
                () ->
                    writes.write(
                        em -> {
                          em.createNativeQuery("insert into batching_probe(id) values (:id)")
                              .setParameter("id", id)
                              .executeUpdate();
                          if (id == 99) throw new IllegalStateException("injected batch failure");
                          return id;
                        })));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (writes.queued() < i + 1 && System.nanoTime() < until) Thread.sleep(1);
        assertEquals(i + 1, writes.queued());
      }
      assertTrue(jobs.stream().noneMatch(Future::isDone));
      Thread.startVirtualThread(starter.get());
      for (var job : jobs)
        assertThrows(ExecutionException.class, () -> job.get(5, TimeUnit.SECONDS));
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, gate.state());
      try (var c = resources.dataSource().getConnection();
          var sql = c.createStatement();
          var rows = sql.executeQuery("select count(*) from batching_probe")) {
        assertEquals(
            0, rows.getInt(1), "No member of a failed group may be acknowledged or committed");
      }
    }
  }

  @Test
  @Tag("kernel-batching")
  void successfulQueuedWritesAreVisibleTogetherBeforeAcknowledgement() throws Exception {
    var w = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(w, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      try (var c = resources.dataSource().getConnection();
          var sql = c.createStatement()) {
        sql.execute("CREATE TABLE batching_probe (id INTEGER PRIMARY KEY)");
      }
      var starter = new java.util.concurrent.atomic.AtomicReference<Runnable>();
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate(), starter::set);
      var lastEntered = new CountDownLatch(1);
      var commit = new CountDownLatch(1);
      var jobs = new ArrayList<Future<?>>();
      for (int i = 0; i < 100; i++) {
        int id = i;
        jobs.add(
            workers.submit(
                () ->
                    writes.write(
                        em -> {
                          em.createNativeQuery("insert into batching_probe(id) values (:id)")
                              .setParameter("id", id)
                              .executeUpdate();
                          if (id == 99) {
                            lastEntered.countDown();
                            try {
                              commit.await();
                            } catch (InterruptedException e) {
                              throw new RuntimeException(e);
                            }
                          }
                          return id;
                        })));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (writes.queued() < i + 1 && System.nanoTime() < until) Thread.sleep(1);
        assertEquals(i + 1, writes.queued());
      }
      Thread.startVirtualThread(starter.get());
      try {
        assertTrue(lastEntered.await(5, TimeUnit.SECONDS));
        assertTrue(
            jobs.stream().noneMatch(Future::isDone),
            "Commit is the acknowledgement boundary for every queued member");
        try (var c = resources.dataSource().getConnection();
            var sql = c.createStatement();
            var rows = sql.executeQuery("select count(*) from batching_probe")) {
          assertEquals(0, rows.getInt(1));
        }
      } finally {
        commit.countDown();
      }
      for (var job : jobs) job.get(5, TimeUnit.SECONDS);
      try (var c = resources.dataSource().getConnection();
          var sql = c.createStatement();
          var rows = sql.executeQuery("select count(*) from batching_probe")) {
        assertEquals(100, rows.getInt(1));
        try (var full = sql.executeQuery("PRAGMA synchronous")) {
          assertEquals(2, full.getInt(1));
        }
      }
    }
  }

  @Test
  @Tag("kernel-batching")
  @Tag("kernel-active-batch")
  void arrivalsDuringActiveWriteShareCommitAndFailureBoundary() throws Exception {
    for (boolean fail : List.of(false, true)) {
      var workspace = new WorkspaceHandle(root.resolve(fail ? "rollback" : "success"));
      new WorkspaceInitializer().initialize(workspace, Map.of());
      try (var lock = WorkspaceProcessLock.acquire(workspace);
          var resources = new StorageResourceFactory().open(workspace, lock);
          var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        try (var c = resources.dataSource().getConnection();
            var sql = c.createStatement()) {
          sql.execute("CREATE TABLE arrivals_probe (id INTEGER PRIMARY KEY)");
        }
        var gate = new RuntimeGate();
        gate.readyAtFirstStartup();
        var writes = new WorkspaceWriteCoordinator(() -> resources, gate);
        var firstEntered = new CountDownLatch(1);
        var allowArrivals = new CountDownLatch(1);
        var lastEntered = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var spans = new ConcurrentLinkedQueue<RuntimeTiming.Segment>();
        var jobs = new ArrayList<Future<?>>();
        jobs.add(
            workers.submit(
                () -> {
                  try (var timing = RuntimeTiming.bind(spans::add)) {
                    return writes.write(
                        em -> {
                          StoredSql.update(em, "INSERT INTO arrivals_probe VALUES (0)");
                          firstEntered.countDown();
                          awaitLatch(allowArrivals);
                          return null;
                        });
                  }
                }));
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
        for (int i = 1; i < 100; i++) {
          int id = i;
          jobs.add(
              workers.submit(
                  () -> {
                    try (var timing = RuntimeTiming.bind(spans::add)) {
                      return writes.write(
                          em -> {
                            StoredSql.update(em, "INSERT INTO arrivals_probe VALUES (?)", id);
                            if (id == 99) {
                              lastEntered.countDown();
                              awaitLatch(allowCommit);
                              if (fail)
                                throw new IllegalStateException("injected late-member failure");
                            }
                            return null;
                          });
                    }
                  }));
          long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          while (writes.queued() < i && System.nanoTime() < until) Thread.sleep(1);
          assertEquals(i, writes.queued());
        }
        allowArrivals.countDown();
        try {
          assertTrue(lastEntered.await(5, TimeUnit.SECONDS));
          try (var c = resources.dataSource().getConnection();
              var sql = c.createStatement();
              var rows = sql.executeQuery("SELECT count(*) FROM arrivals_probe")) {
            assertEquals(0, rows.getInt(1), "Arrivals must not require a preceding partial commit");
          }
          assertTrue(jobs.stream().noneMatch(Future::isDone), "No early acknowledgement");
        } finally {
          allowCommit.countDown();
        }
        for (var job : jobs) {
          if (fail) assertThrows(ExecutionException.class, () -> job.get(5, TimeUnit.SECONDS));
          else job.get(5, TimeUnit.SECONDS);
        }
        assertEquals(
            fail ? RuntimeGate.State.PAUSED_RECORDING : RuntimeGate.State.READY, gate.state());
        try (var c = resources.dataSource().getConnection();
            var sql = c.createStatement();
            var rows = sql.executeQuery("SELECT count(*) FROM arrivals_probe")) {
          assertEquals(fail ? 0 : 100, rows.getInt(1));
        }
        if (!fail)
          assertEquals(
              1,
              spans.stream()
                  .filter(s -> s.name().equals("db.commit"))
                  .map(RuntimeTiming.Segment::startNanos)
                  .distinct()
                  .count(),
              "One durable group commit");
        assertEquals(
            2,
            spans.stream().filter(s -> s.name().equals("db.statement.prepare")).count(),
            "A group should prepare each of its two SQL shapes once, including rollback");
      }
    }
  }

  @Test
  @Tag("kernel-active-batch")
  void admittedWriteSettlesBeforeTimeoutOrInterruptedCallerReturns() throws Exception {
    var workspace = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(workspace, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var resources = new StorageResourceFactory().open(workspace, lock);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
      writes.write(em -> null);
      for (boolean interrupt : List.of(false, true)) {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var caller = new java.util.concurrent.atomic.AtomicReference<Thread>();
        var restored = new java.util.concurrent.atomic.AtomicBoolean();
        var result =
            workers.submit(
                () -> {
                  caller.set(Thread.currentThread());
                  int value =
                      writes.write(
                          null,
                          Duration.ofMillis(250),
                          em -> {
                            entered.countDown();
                            awaitLatch(release);
                            return 42;
                          });
                  restored.set(Thread.currentThread().isInterrupted());
                  return value;
                });
        try {
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          if (interrupt) caller.get().interrupt();
          Thread.sleep(300);
          assertFalse(
              result.isDone(), "An admitted physical transaction must settle before return");
        } finally {
          release.countDown();
        }
        assertEquals(42, result.get(5, TimeUnit.SECONDS));
        assertEquals(interrupt, restored.get());
      }
    }
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS))
        throw new IllegalStateException("test latch timed out");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(error);
    }
  }

  @Test
  @Tag("kernel-hot-writes")
  void terminalExecutionCannotBeRevivedAndCompletionSurvivesRepeatedSave() throws Exception {
    var w = new WorkspaceHandle(root);
    UUID wid = new WorkspaceInitializer().initialize(w, Map.of());
    var sid = UUID.randomUUID();
    var e = execution(sid);
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock)) {
      var gate = new RuntimeGate();
      gate.readyAtFirstStartup();
      var writes = new WorkspaceWriteCoordinator(() -> resources, gate);
      var sessions = new JpaSessionStore(wid, writes);
      var executions = new JpaExecutionStore(writes);
      var session =
          new Session(
              sid,
              "cli",
              "alice",
              "agent",
              "p",
              "m",
              Session.Kind.CONVERSATION,
              Session.Status.ACTIVE,
              Instant.now());
      sessions.begin(
          session, true, e.snapshot(0), message(sid, e, 1, ChatMessage.user("hello")), executions);
      sessions.append(
          message(
              sid,
              e,
              2,
              new ChatMessage(ChatMessage.Role.ASSISTANT, "done", List.of(), null, null, null)));
      sessions.finishTurn(sid, e.turnId());
      e.finish(Execution.Status.SUCCEEDED, "FINAL_REPLY", "done", null, Instant.now());
      executions.save(e.snapshot(0));
      executions.save(e.snapshot(0));
      assertTrue(sessions.completedTurns(sid).contains(e.turnId()));
      var revived =
          new Execution(
              e.id(),
              sid,
              e.turnId(),
              ProcessRequest.manual("cli", "alice", "agent", sid, "task"),
              "revision",
              Instant.now(),
              Instant.now().plusSeconds(60),
              ZoneId.of("Asia/Shanghai"),
              ToolNameMap.empty());
      assertThrows(RuntimeException.class, () -> executions.save(revived.snapshot(0)));
      assertEquals(Execution.Status.SUCCEEDED, executions.find(e.id()).status());
      assertTrue(sessions.completedTurns(sid).contains(e.turnId()));
      assertEquals(2, sessions.messages(sid).size());
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, gate.state());
    }
  }

  @Test
  @Tag("kernel-batching")
  void queueRemainsBoundedBeforeWriterThreadStarts() throws Exception {
    var w = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(w, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock);
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var starter = new java.util.concurrent.atomic.AtomicReference<Runnable>();
      var gate = new RuntimeGate();
      gate.readyAtFirstStartup();
      var writes = new WorkspaceWriteCoordinator(() -> resources, gate, starter::set);
      var jobs = new ArrayList<Future<?>>();
      try {
        for (int i = 0; i < 256; i++) jobs.add(workers.submit(() -> writes.write(em -> null)));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (writes.queued() < 256 && System.nanoTime() < until) Thread.sleep(1);
        assertEquals(256, writes.queued());
        var rejected = workers.submit(() -> writes.write(em -> null));
        until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!rejected.isDone() && writes.queued() == 256 && System.nanoTime() < until)
          Thread.sleep(1);
        assertEquals(
            256, writes.queued(), "Queue must stay bounded even before a writer is scheduled");
        assertTrue(
            rejected.isDone(), "A full queue must reject immediately, not wait for a deadline");
        assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS));
        assertEquals(RuntimeGate.State.PAUSED_RECORDING, gate.state());
      } finally {
        if (starter.get() != null) starter.get().run();
      }
      for (var job : jobs) job.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  @Tag("kernel-batching")
  void writerStartFailurePausesAndCompletesCallerWithoutRunningAction() {
    var gate = new RuntimeGate();
    gate.readyAtFirstStartup();
    var called = new java.util.concurrent.atomic.AtomicBoolean();
    var writes =
        new WorkspaceWriteCoordinator(
            () -> {
              throw new AssertionError("No storage may open");
            },
            gate,
            command -> {
              throw new RejectedExecutionException("injected writer rejection");
            });
    var error =
        assertThrows(
            io.github.oryxlabs.oryxos.core.error.OryxError.class,
            () ->
                writes.write(
                    em -> {
                      called.set(true);
                      return null;
                    }));
    assertEquals("RECORDING_FAILURE", error.code());
    assertEquals(RuntimeGate.State.PAUSED_RECORDING, gate.state());
    assertEquals(0, writes.queued());
    assertFalse(called.get());
  }

  @Test
  @Tag("kernel-group-timing")
  void connectionsDisableSynchronousWalAutocheckpointToKeepCommitsBounded() throws Exception {
    var w = new WorkspaceHandle(root);
    UUID wid = new WorkspaceInitializer().initialize(w, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(w);
        var resources = new StorageResourceFactory().open(w, lock)) {
      try (var c = resources.dataSource().getConnection();
          var s = c.createStatement();
          var rs = s.executeQuery("PRAGMA wal_autocheckpoint")) {
        assertTrue(rs.next());
        assertEquals(
            0,
            rs.getInt(1),
            "SQLite connections should set PRAGMA wal_autocheckpoint=0 so tx commits avoid"
                + " synchronous mid-wave checkpoints");
      }

      var gate = new RuntimeGate();
      gate.readyAtFirstStartup();
      var writes = new WorkspaceWriteCoordinator(() -> resources, gate);
      String entryId = UUID.randomUUID().toString();
      writes.write(
          em -> {
            em.createNativeQuery(
                    "INSERT INTO"
                        + " memory_entries(id,workspace_id,scope,raw_content,created_at,source_execution_id,source_agent_name,backend,write_status)"
                        + " VALUES(?1,?2,'CORE','seed',?3,?4,'agent','sqlite','CONFIRMED')")
                .setParameter(1, entryId)
                .setParameter(2, wid.toString())
                .setParameter(3, Instant.now().toString())
                .setParameter(4, UUID.randomUUID().toString())
                .executeUpdate();
            return null;
          });

      var jobs = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
      try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        for (int wIdx = 0; wIdx < 32; wIdx++) {
          int worker = wIdx;
          jobs.add(
              java.util.concurrent.CompletableFuture.runAsync(
                  () -> {
                    for (int step = 0; step < 50; step++) {
                      int n = worker * 50 + step;
                      writes.write(
                          em -> {
                            em.createNativeQuery(
                                    "UPDATE memory_entries SET raw_content = ?1 WHERE id = ?2")
                                .setParameter(1, "v-" + n)
                                .setParameter(2, entryId)
                                .executeUpdate();
                            return null;
                          });
                    }
                  },
                  pool));
        }
        for (var job : jobs) job.get(15, TimeUnit.SECONDS);
      }

      boolean drained = false;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < deadline) {
        try (var c = resources.dataSource().getConnection();
            var s = c.createStatement();
            var rs = s.executeQuery("PRAGMA wal_checkpoint(PASSIVE)")) {
          if (rs.next()) {
            int busy = rs.getInt(1);
            int log = rs.getInt(2);
            int checkpointed = rs.getInt(3);
            if (busy == 0 && (log <= 16 || checkpointed == log)) {
              drained = true;
              break;
            }
          }
        }
        Thread.sleep(50);
      }
      assertTrue(drained, "Quiet background checkpointer should drain WAL frames once idle");
    }
  }

  private static Execution execution(UUID session) {
    var now = Instant.now();
    return new Execution(
        UUID.randomUUID(),
        session,
        UUID.randomUUID(),
        ProcessRequest.manual("cli", "alice", "agent", session, "task"),
        "revision",
        now,
        now.plusSeconds(60),
        ZoneId.of("Asia/Shanghai"),
        ToolNameMap.empty());
  }

  private static SessionMessage message(UUID sid, Execution e, long seq, ChatMessage message) {
    return new SessionMessage(
        UUID.randomUUID(), sid, e.id(), e.turnId(), seq, message, null, Instant.now());
  }

  private static ToolCallRecord tool(
      UUID id,
      UUID execution,
      String call,
      int attempt,
      String status,
      String resolution,
      String correction,
      String resolved) {
    var now = Instant.now();
    return new ToolCallRecord(
        id,
        execution,
        call,
        "tool",
        attempt,
        status,
        "SECRET_CANARY https://private/credential",
        "safe",
        status.equals("UNKNOWN")
            ? ToolResult.SideEffectState.UNKNOWN
            : ToolResult.SideEffectState.NONE,
        resolution,
        correction,
        resolved,
        now,
        status.equals("STARTED") ? null : now,
        0,
        status.equals("ERROR") ? "INVALID_ARGUMENTS" : null);
  }
}
