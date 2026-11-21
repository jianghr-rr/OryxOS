package io.github.oryxlabs.oryxos.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.execution.JpaExecutionStore;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResourceFactory;
import io.github.oryxlabs.oryxos.storage.session.JpaSessionStore;
import io.github.oryxlabs.oryxos.storage.transaction.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Real SQLite reads with a hundred callers; diagnostic only, never T101 acceptance. */
@Tag("kernel-reader-probe")
class SessionReadPerformanceIT {
  @TempDir Path root;

  @Test
  void hundredSessionReadersIncludeConnectionWaitAndMaterialization() throws Exception {
    var workspace = new WorkspaceHandle(root);
    var wid = new WorkspaceInitializer().initialize(workspace, Map.of());
    var samples = new ConcurrentLinkedQueue<Map<String, Object>>();
    try (var lock = WorkspaceProcessLock.acquire(workspace);
        var resources = new StorageResourceFactory().open(workspace, lock);
        var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
      var sessions = new JpaSessionStore(wid, writes);
      var executions = new JpaExecutionStore(writes);
      var turns = new ConcurrentHashMap<UUID, UUID>();
      var seeds = new ArrayList<Future<?>>();
      for (int client = 0; client < 100; client++)
        seeds.add(
            callers.submit(() -> {
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
              for (int turn = 0; turn < 5; turn++) {
                var execution = SessionReadContractTest.execution(sid);
                sessions.begin(
                    session,
                    turn == 0,
                    execution.snapshot(0),
                    new SessionMessage(
                        UUID.randomUUID(),
                        sid,
                        execution.id(),
                        execution.turnId(),
                        turn * 2 + 1,
                        ChatMessage.user("request " + turn),
                        null,
                        Instant.now()),
                    executions);
                sessions.append(
                    new SessionMessage(
                        UUID.randomUUID(),
                        sid,
                        execution.id(),
                        execution.turnId(),
                        turn * 2 + 2,
                        SessionReadContractTest.assistant("response ".repeat(128)),
                        null,
                        Instant.now()));
                sessions.finishTurn(sid, execution.turnId());
                turns.put(sid, execution.turnId());
              }
            }));
      for (var seed : seeds) seed.get(20, TimeUnit.SECONDS);
      var ids = List.copyOf(turns.keySet());
      for (int i = 0; i < 20; i++) read(sessions, ids.get(i), turns.get(ids.get(i)));
      for (int round = 0; round < 3; round++) {
        var start = new CountDownLatch(1);
        var jobs = new ArrayList<Future<?>>();
        for (var sid : ids)
          jobs.add(
              callers.submit(() -> {
                start.await();
                var spans = new ArrayList<RuntimeTiming.Segment>();
                long begin = System.nanoTime();
                try (var timing = RuntimeTiming.bind(spans::add)) {
                  read(sessions, sid, turns.get(sid));
                }
                samples.add(Map.of("elapsedNanos", System.nanoTime() - begin, "segments", spans));
                return null;
              }));
        start.countDown();
        for (var job : jobs) job.get(10, TimeUnit.SECONDS);
      }
    }
    var out =
        Path.of(
            System.getProperty("oryxos.test.evidenceDir"),
            "reader-probe-" + System.getProperty("oryxos.test.runId") + ".json");
    Files.writeString(
        out,
        StoredJson.write(Map.of("label", "MINIMIZED_READER_PROBE_NOT_T101", "samples", samples)));
    long max = samples.stream().mapToLong(s -> (long) s.get("elapsedNanos")).max().orElseThrow();
    assertTrue(max <= 50_000_000L, "Reader maximum " + max / 1e6 + "ms > 50ms; " + out);
  }

  private static void read(JpaSessionStore store, UUID sid, UUID turn) {
    assertEquals(sid, store.find(sid).id());
    assertEquals(10, store.lastSequence(sid));
    var history = store.history(sid, turn, 4);
    assertEquals(8, history.recent().size());
    assertEquals(2, history.current().size());
    assertNotNull(history.current().getLast().providerOpaque());
    assertEquals(2, store.turnMessages(sid, turn).size());
  }
}
