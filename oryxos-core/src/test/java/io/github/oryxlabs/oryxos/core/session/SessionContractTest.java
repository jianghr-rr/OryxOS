package io.github.oryxlabs.oryxos.core.session;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.agent.AgentCatalog;
import io.github.oryxlabs.oryxos.core.agent.AgentService;
import io.github.oryxlabs.oryxos.core.config.RuntimeConfigLoader;
import io.github.oryxlabs.oryxos.core.context.ContextLoader;
import io.github.oryxlabs.oryxos.core.context.PromptBuilder;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.execution.ExecutionStore;
import io.github.oryxlabs.oryxos.core.execution.ProcessRequest;
import io.github.oryxlabs.oryxos.core.memory.ContextMemory;
import io.github.oryxlabs.oryxos.core.provider.ProviderPort;
import io.github.oryxlabs.oryxos.core.react.LoopFixture;
import io.github.oryxlabs.oryxos.core.react.ReActLoop;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeGate;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.tool.ToolExecutor;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us3-memory")
class SessionContractTest {
  @TempDir Path root;

  @Test
  void archiveRequiresMatchingIdentityAndNoActiveLease() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var id = f.lease.session().id();
      assertEquals(
          "SESSION_IDENTITY_MISMATCH",
          assertThrows(OryxError.class, () -> f.sessions.archive(id, "http", "user", "demo"))
              .code());
      assertEquals(
          "SESSION_BUSY",
          assertThrows(OryxError.class, () -> f.sessions.archive(id, "cli", "user", "demo"))
              .code());
      f.lease.close();
      f.sessions.archive(id, "cli", "user", "demo");
      assertEquals(Session.Status.ARCHIVED, f.store.find(id).status());
      assertFalse(f.store.messages(id).isEmpty());
      assertEquals(
          "SESSION_ARCHIVED",
          assertThrows(
                  OryxError.class,
                  () -> f.sessions.acquire(id, "cli", "user", f.profile, Session.Kind.CONVERSATION))
              .code());
    }
  }

  @Test
  void oneShotArchivesOnlyWhenItsLeaseActuallyCloses() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var lease =
          f.sessions.acquire(null, "scheduler", "scheduler", f.profile, Session.Kind.ONE_SHOT);
      var id = lease.session().id();
      assertEquals(Session.Status.ACTIVE, f.store.find(id).status());
      lease.close();
      assertEquals(Session.Status.ARCHIVED, f.store.find(id).status());
    }
  }

  @Test
  void creationSnapshotCannotHaveEmptyModelOrProvider() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var s = f.lease.session();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new Session(
                  s.id(),
                  s.channel(),
                  s.userId(),
                  s.agentName(),
                  "",
                  s.model(),
                  s.kind(),
                  s.status(),
                  s.createdAt()));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new Session(
                  s.id(),
                  s.channel(),
                  s.userId(),
                  s.agentName(),
                  s.providerName(),
                  " ",
                  s.kind(),
                  s.status(),
                  s.createdAt()));
    }
  }

  @Test
  void oneShotDeadlineDoesNotArchiveUntilLateWorkAndFinalRecordExit() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var archived = new CountDownLatch(1);
      var finalRecorded = new AtomicBoolean();
      var store =
          new LoopFixture.Store() {
            public synchronized void archive(UUID id, Instant at) {
              assertTrue(finalRecorded.get());
              super.archive(id, at);
              archived.countDown();
            }
          };
      var sessions = new SessionManager(store, f.clock);
      var records = executionRecords(finalRecorded);
      f.add(
          "first",
          false,
          (a, c) -> {
            entered.countDown();
            try {
              assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
            return ToolResult.success("late result");
          });
      var loop =
          loop(
              f,
              sessions,
              r -> LoopFixture.reply(null, LoopFixture.call("late", "first", "{\"value\":1}")));
      try (var service = service(f, sessions, records, loop)) {
        var handle = service.start(scheduled());
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        f.clock.nanos = Duration.ofSeconds(60).toNanos();
        handle.expireIfDue();
        var timedOut = handle.await();
        assertEquals(Execution.Status.TIMED_OUT, timedOut.status());
        assertEquals(Session.Status.ACTIVE, store.find(timedOut.sessionId()).status());
        assertEquals(
            "SESSION_BUSY",
            assertThrows(
                    OryxError.class,
                    () -> sessions.archive(timedOut.sessionId(), "scheduler", "scheduler", "demo"))
                .code());
        release.countDown();
        assertTrue(archived.await(5, TimeUnit.SECONDS));
        assertEquals(Session.Status.ARCHIVED, store.find(timedOut.sessionId()).status());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void oneShotArchiveFailureCompletesCallerAndPausesAdmission() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var store =
          new LoopFixture.Store() {
            public synchronized void archive(UUID id, Instant at) {
              throw new IllegalStateException("archive unavailable");
            }
          };
      var sessions = new SessionManager(store, f.clock);
      var records = executionRecords(new AtomicBoolean());
      try (var service =
          service(f, sessions, records, loop(f, sessions, r -> LoopFixture.reply("done")))) {
        var result =
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> service.process(scheduled()));
        assertEquals(Execution.RecordingStatus.INCOMPLETE, result.recordingStatus());
        assertEquals(RuntimeGate.State.PAUSED_RECORDING, f.gate.state());
        assertEquals(result, records.find(result.id()));
        assertEquals(Session.Status.ACTIVE, store.find(result.sessionId()).status());
      }
    }
  }

  @Test
  @Tag("kernel-session-concurrency")
  void blockedSessionStorageDoesNotSerializeOtherSessionAdmission() throws Exception {
    try (var f = new LoopFixture(root, 10);
        var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var blocked = new java.util.concurrent.atomic.AtomicReference<UUID>();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var store =
          new LoopFixture.Store() {
            public Session find(UUID id) {
              if (id.equals(blocked.get())) {
                entered.countDown();
                try {
                  release.await();
                } catch (InterruptedException e) {
                  throw new RuntimeException(e);
                }
              }
              return super.find(id);
            }
          };
      var sessions = new SessionManager(store, f.clock);
      UUID first, second;
      try (var lease =
          sessions.acquire(null, "cli", "user", f.profile, Session.Kind.CONVERSATION)) {
        first = lease.session().id();
      }
      try (var lease =
          sessions.acquire(null, "cli", "user", f.profile, Session.Kind.CONVERSATION)) {
        second = lease.session().id();
      }
      blocked.set(first);
      var waiting =
          workers.submit(
              () -> sessions.acquire(first, "cli", "user", f.profile, Session.Kind.CONVERSATION));
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var independent =
            workers.submit(
                () ->
                    sessions.acquire(second, "cli", "user", f.profile, Session.Kind.CONVERSATION));
        try (var lease = independent.get(1, TimeUnit.SECONDS)) {
          assertEquals(second, lease.session().id());
        }
      } finally {
        release.countDown();
      }
      waiting.get(5, TimeUnit.SECONDS).close();
      blocked.set(null);
      sessions.archive(first, "cli", "user", "demo");
      assertEquals(
          "SESSION_ARCHIVED",
          assertThrows(
                  OryxError.class,
                  () ->
                      sessions.acquire(first, "cli", "user", f.profile, Session.Kind.CONVERSATION))
              .code());
    }
  }

  private ExecutionStore executionRecords(AtomicBoolean finalRecorded) {
    return new ExecutionStore() {
      final Map<UUID, Execution.Snapshot> records = new ConcurrentHashMap<>();

      public void save(Execution.Snapshot s) {
        records.put(s.id(), s);
        if (s.status() != Execution.Status.RUNNING) finalRecorded.set(true);
      }

      public Execution.Snapshot find(UUID id) {
        return records.get(id);
      }
    };
  }

  private ReActLoop loop(LoopFixture f, SessionManager sessions, ProviderPort provider) {
    return new ReActLoop(
        provider,
        f.registry,
        new ToolExecutor(f.registry, f.sink, d -> {}),
        ctx -> {
          var h = sessions.history(ctx.execution().sessionId(), ctx.execution().turnId(), 20);
          return new ContextMemory(h.recent(), h.current(), "", "");
        },
        new PromptBuilder(new ContextLoader()));
  }

  private AgentService service(
      LoopFixture f, SessionManager sessions, ExecutionStore records, ReActLoop loop)
      throws Exception {
    f.catalog();
    var definition = f.profile.definition();
    Files.writeString(
        definition,
        Files.readString(definition)
            .replace(
                "bootstrap:",
                "schedules: [{id: tick, cron: '0 * * * * *', message: check}]\nbootstrap:"));
    var env = Map.of("TEST_KEY", "fixture");
    var redactor = new Redactor();
    var config = new RuntimeConfigLoader(env, redactor).load(f.context.workspace());
    var catalog = AgentCatalog.load(f.context.workspace(), config, env, redactor);
    return new AgentService(
        f.context.workspace(),
        catalog,
        sessions,
        records,
        loop,
        f.registry,
        f.gate,
        f.clock,
        ZoneId.of("Asia/Shanghai"),
        redactor);
  }

  private ProcessRequest scheduled() {
    return new ProcessRequest(
        "scheduler",
        "scheduler",
        "demo",
        null,
        "check",
        ProcessRequest.Trigger.SCHEDULED,
        "demo:tick");
  }
}
