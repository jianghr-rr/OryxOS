package io.github.oryxlabs.oryxos.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.lifecycle.*;
import io.github.oryxlabs.oryxos.storage.transaction.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Minimized SQLite writer probe, explicitly opt-in; not the HTTP/T101 acceptance workload. */
@Tag("kernel-writer-probe")
class WriteCoordinatorPerformanceIT {
  @TempDir Path root;

  @Test
  void hundredCallersRetainQueueAndFullCommitCosts() throws Exception {
    var workspace = new WorkspaceHandle(root);
    new WorkspaceInitializer().initialize(workspace, Map.of());
    var samples = new ConcurrentLinkedQueue<Map<String, Object>>();
    var ids = new AtomicInteger();
    String payload = "auditable record ".repeat(64);
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var storage = new StorageResourceFactory().open(workspace, lock);
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var writer = new WorkspaceWriteCoordinator(() -> storage, new RuntimeGate());
      writer.write(em ->
          StoredSql.update(
              em, "CREATE TABLE writer_probe(id INTEGER PRIMARY KEY, content TEXT NOT NULL)"));
      for (int i = 0; i < 20; i++)
        writer.write(em ->
            StoredSql.update(
                em, "INSERT INTO writer_probe VALUES (?,?)", ids.incrementAndGet(), payload));
      for (int round = 0; round < 3; round++) {
        int burst = round;
        var start = new CountDownLatch(1);
        var jobs = new ArrayList<Future<?>>();
        for (int client = 0; client < 100; client++) {
          int caller = client;
          jobs.add(
              callers.submit(() -> {
                var segments = new ArrayList<RuntimeTiming.Segment>();
                start.await();
                long began = System.nanoTime();
                try (var timing = RuntimeTiming.bind(segments::add)) {
                  for (int record = 0; record < 12; record++)
                    writer.write(em ->
                        StoredSql.update(
                            em,
                            "INSERT INTO writer_probe VALUES (?,?)",
                            ids.incrementAndGet(),
                            payload));
                }
                samples.add(
                    Map.of(
                        "burst",
                        burst,
                        "caller",
                        caller,
                        "elapsedNanos",
                        System.nanoTime() - began,
                        "segments",
                        segments));
                return null;
              }));
        }
        start.countDown();
        for (var job : jobs) job.get(10, TimeUnit.SECONDS);
      }
      try (var connection = storage.dataSource().getConnection();
          var statement = connection.createStatement()) {
        try (var rows = statement.executeQuery("SELECT count(*) FROM writer_probe")) {
          assertEquals(3620, rows.getInt(1));
        }
        try (var rows = statement.executeQuery("PRAGMA synchronous")) {
          assertEquals(2, rows.getInt(1));
        }
        try (var rows = statement.executeQuery("PRAGMA journal_mode")) {
          assertEquals("wal", rows.getString(1));
        }
        try (var rows = statement.executeQuery("PRAGMA integrity_check")) {
          assertEquals("ok", rows.getString(1));
        }
      }
    }
    var output =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "writer-probe-" + System.getProperty("oryxos.test.runId") + ".json");
    Files.writeString(
        output,
        StoredJson.write(Map.of("label", "MINIMIZED_WRITER_PROBE_NOT_T101", "samples", samples)));
    long max = samples.stream().mapToLong(s -> (long) s.get("elapsedNanos")).max().orElseThrow();
    assertTrue(max <= 50_000_000L, "Writer-only maximum " + (max / 1e6) + "ms > 50ms; " + output);
  }
}
