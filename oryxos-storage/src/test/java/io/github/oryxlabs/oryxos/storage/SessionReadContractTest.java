package io.github.oryxlabs.oryxos.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.storage.execution.JpaExecutionStore;
import io.github.oryxlabs.oryxos.storage.lifecycle.StorageResourceFactory;
import io.github.oryxlabs.oryxos.storage.session.JpaSessionStore;
import io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("kernel-lease-read")
class SessionReadContractTest {
  @TempDir Path root;

  @Test
  void hotProjectionsAvoidManagedContextsAndClosedStorageNeverLooksEmpty() throws Exception {
    var workspace = new WorkspaceHandle(root);
    var wid = new WorkspaceInitializer().initialize(workspace, Map.of());
    try (var lock = WorkspaceProcessLock.acquire(workspace)) {
      var resources = new StorageResourceFactory().open(workspace, lock);
      var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
      var store = new JpaSessionStore(wid, writes);
      var sid = UUID.randomUUID();
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
      var execution = execution(sid);
      long contexts;
      try (resources) {
        store.begin(
            session,
            true,
            execution.snapshot(0),
            new SessionMessage(
                UUID.randomUUID(),
                sid,
                execution.id(),
                execution.turnId(),
                1,
                ChatMessage.user("read me"),
                null,
                Instant.now()),
            new JpaExecutionStore(writes));
        org.hibernate.stat.Statistics stats;
        try (var em = resources.openEntityManager()) {
          stats =
              em.getEntityManagerFactory()
                  .unwrap(org.hibernate.SessionFactory.class)
                  .getStatistics();
        }
        stats.setStatisticsEnabled(true);
        stats.clear();
        assertEquals(session, store.find(sid));
        assertEquals(1, store.lastSequence(sid));
        assertEquals(1, store.turnMessages(sid, execution.turnId()).size());
        assertEquals(1, store.history(sid, execution.turnId(), 4).current().size());
        contexts = stats.getSessionOpenCount();
        stats.setStatisticsEnabled(false);
        assertEquals(0, resources.dataSource().getHikariPoolMXBean().getActiveConnections());
      }
      var error =
          assertThrows(io.github.oryxlabs.oryxos.core.error.OryxError.class, () -> store.find(sid));
      assertEquals("STORAGE_UNAVAILABLE", error.code());
      assertEquals(
          0,
          contexts,
          "Read-only projections need no persistence context, dirty checking or entity tracking");
    }
  }

  @Test
  void exclusiveLeaseReadsSequenceOnceAndResumesFromSqliteAfterRestart() throws Exception {
    var workspace = new WorkspaceHandle(root);
    var wid = new WorkspaceInitializer().initialize(workspace, Map.of());
    var profile =
        new AgentProfile(
            "agent",
            "agent",
            "p",
            "m",
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            List.of(),
            4,
            4,
            root.resolve("AGENT.md"),
            "revision",
            "body");
    UUID sessionId = null;
    var lookups = new ArrayList<Long>();
    for (int process = 0; process < 2; process++) {
      try (var lock = WorkspaceProcessLock.acquire(workspace);
          var resources = new StorageResourceFactory().open(workspace, lock)) {
        var writes = new WorkspaceWriteCoordinator(() -> resources, new RuntimeGate());
        var store = new JpaSessionStore(wid, writes);
        var executions = new JpaExecutionStore(writes);
        var sessions = new SessionManager(store, TimeSource.system());
        try (var lease =
            sessions.acquireForExecution(
                sessionId, "cli", "alice", profile, Session.Kind.CONVERSATION)) {
          sessionId = lease.session().id();
          var execution = execution(sessionId);
          var segments = new ConcurrentLinkedQueue<RuntimeTiming.Segment>();
          try (var timing = RuntimeTiming.bind(segments::add)) {
            lease.begin(execution.snapshot(0), "user " + process, executions);
            for (int i = 0; i < 3; i++)
              lease.append(execution.id(), execution.turnId(), assistant("reply " + i), null);
          }
          lookups.add(segments.stream().filter(s -> s.name().equals("session.sequence")).count());
          lease.finish(execution.turnId());
          assertEquals((process + 1) * 4, store.lastSequence(sessionId));
          assertEquals(
              java.util.stream.LongStream.rangeClosed(1, (process + 1) * 4).boxed().toList(),
              store.messages(sessionId).stream().map(SessionMessage::seq).toList());
          assertTrue(store.completedTurns(sessionId).contains(execution.turnId()));
        }
      }
    }
    assertEquals(
        List.of(0L, 1L),
        lookups,
        "An exclusive lease must not re-query unchanged durable sequence before every append");
  }

  static Execution execution(UUID sid) {
    var now = Instant.now();
    return new Execution(
        UUID.randomUUID(),
        sid,
        UUID.randomUUID(),
        ProcessRequest.manual("cli", "alice", "agent", sid, "task"),
        "revision",
        now,
        now.plusSeconds(60),
        ZoneId.of("Asia/Shanghai"),
        ToolNameMap.empty());
  }

  static ChatMessage assistant(String text) {
    return new ChatMessage(
        ChatMessage.Role.ASSISTANT,
        text,
        List.of(),
        null,
        null,
        new ChatMessage.ProviderOpaque("p", "m", "continuation"));
  }
}
