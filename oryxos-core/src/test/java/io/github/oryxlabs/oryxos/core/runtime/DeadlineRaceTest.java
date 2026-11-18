package io.github.oryxlabs.oryxos.core.runtime;

import static io.github.oryxlabs.oryxos.core.react.LoopFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.agent.AgentService;
import io.github.oryxlabs.oryxos.core.audit.*;
import io.github.oryxlabs.oryxos.core.context.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.memory.ContextMemory;
import io.github.oryxlabs.oryxos.core.react.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.session.*;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us2-loop")
@Tag("deadline-boundary")
class DeadlineRaceTest {
  @TempDir Path root;

  @Test
  @Tag("us5-http-api")
  @org.junit.jupiter.api.Tag("us5-http")
  void completedModelStillExpiresCallerWhileFinalRecordingIsBlocked() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var saved = new AtomicReference<Execution.Snapshot>();
      f.script.add(reply("done"));
      var store =
          new ExecutionStore() {
            public void save(Execution.Snapshot s) {
              if (s.status() == Execution.Status.SUCCEEDED) {
                entered.countDown();
                try {
                  assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                  throw new AssertionError(e);
                }
              }
              saved.set(s);
            }

            public Execution.Snapshot find(UUID id) {
              return saved.get();
            }
          };
      try (var service =
          new AgentService(
              f.context.workspace(),
              f.catalog(),
              f.sessions,
              store,
              f.loop(),
              f.registry,
              f.gate,
              f.clock,
              ZoneId.of("Asia/Shanghai"),
              new Redactor())) {
        var h = service.start(ProcessRequest.manual("http", "u", "demo", null, "hello"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        f.clock.nanos = Duration.ofSeconds(60).toNanos();
        h.expireIfDue();
        assertTrue(h.waitExpired());
        var response = h.await();
        assertEquals(
            Execution.Status.SUCCEEDED,
            response.status(),
            "Preserve the model result already known before deadline");
        assertEquals(Execution.RecordingStatus.UNKNOWN, response.recordingStatus());
        assertEquals(
            "SESSION_BUSY",
            assertThrows(
                    OryxError.class,
                    () ->
                        f.sessions.acquire(
                            response.sessionId(),
                            "http",
                            "u",
                            f.profile,
                            Session.Kind.CONVERSATION))
                .code());
        release.countDown();
        assertEquals(Execution.RecordingStatus.COMPLETE, h.awaitSettled().recordingStatus());
        assertEquals(Execution.RecordingStatus.COMPLETE, saved.get().recordingStatus());
        assertEquals(
            Execution.RecordingStatus.UNKNOWN,
            h.await().recordingStatus(),
            "Returned timeout snapshot is immutable");
        assertEquals(1, f.requests.size());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void slowStartedRecordCannotAuthorizeActionAfterDeadline() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var sink =
          new AuditSink() {
            public void modelCall(ModelCallRecord r) {}

            public void toolCall(ToolCallRecord r) {
              f.audit.add(r);
              if (r.status().equals("STARTED")) f.clock.nanos = Duration.ofSeconds(60).toNanos();
            }
          };
      var result =
          new ToolExecutor(f.registry, sink, d -> fail("No retry"))
              .execute(f.context, call("one", "first", "{\"value\":1}"));
      assertEquals(ToolResult.Outcome.NOT_EXECUTED, result.result().outcome());
      assertTrue(f.effects.isEmpty());
      assertEquals(2, f.audit.size());
      assertEquals(0, f.gate.inFlight());
      assertThrows(
          OryxError.class, () -> f.context.dispatcher().call(() -> fail("No new model IO")));
    }
  }

  @Test
  void closureRejectsInternalSubrequestsButDoesNotUndoAdmittedWork() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      f.context
          .dispatcher()
          .call(
              () -> {
                assertEquals(1, f.gate.inFlight());
                f.context.stop();
                assertEquals(
                    "EXECUTION_STOPPED",
                    assertThrows(
                            OryxError.class,
                            () -> f.context.dispatcher().call(() -> fail("No subrequest")))
                        .code());
                return null;
              });
      assertEquals(0, f.gate.inFlight());
    }
  }

  @Test
  void recordingFailurePausesOtherExecutionsAndNeverDispatches() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var other = f.gate.execution(new Deadline(f.clock, Duration.ofSeconds(60)));
      var sink =
          new AuditSink() {
            public void modelCall(ModelCallRecord r) {}

            public void toolCall(ToolCallRecord r) {
              throw new IllegalStateException("storage unavailable");
            }
          };
      assertEquals(
          "RECORDING_FAILURE",
          assertThrows(
                  OryxError.class,
                  () ->
                      new ToolExecutor(f.registry, sink, d -> {})
                          .execute(f.context, call("one", "first", "{\"value\":1}")))
              .code());
      assertTrue(f.effects.isEmpty());
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, f.gate.state());
      assertThrows(OryxError.class, other::admit);
      assertEquals(Execution.RecordingStatus.INCOMPLETE, f.context.snapshot().recordingStatus());
    }
  }

  @Test
  void callerDeadlineKeepsSessionBusyUntilLateToolAndFinalRecordExit() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var finalSaved = new CountDownLatch(1);
      var saved = new AtomicReference<Execution.Snapshot>();
      var requests = new AtomicInteger();
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
            return ToolResult.success("late fact");
          });
      var loop =
          new ReActLoop(
              r -> {
                requests.incrementAndGet();
                return reply(
                    null,
                    call("late", "first", "{\"value\":1}"),
                    call("never", "second", "{\"value\":1}"));
              },
              f.registry,
              new ToolExecutor(f.registry, f.sink, d -> {}),
              ctx -> {
                var h =
                    f.sessions.history(ctx.execution().sessionId(), ctx.execution().turnId(), 20);
                return new ContextMemory(h.recent(), h.current(), "", "");
              },
              new PromptBuilder(new ContextLoader()));
      var store =
          new ExecutionStore() {
            public void save(Execution.Snapshot s) {
              saved.set(s);
              if (s.status() != Execution.Status.RUNNING) finalSaved.countDown();
            }

            public Execution.Snapshot find(UUID id) {
              return saved.get();
            }
          };
      try (var service =
          new AgentService(
              f.context.workspace(),
              f.catalog(),
              f.sessions,
              store,
              loop,
              f.registry,
              f.gate,
              f.clock,
              ZoneId.of("Asia/Shanghai"),
              new Redactor())) {
        var handle =
            service.start(ProcessRequest.manual("cli", "other-user", "demo", null, "weather"));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        f.clock.nanos = Duration.ofSeconds(60).toNanos();
        handle.expireIfDue();
        var response = handle.await();
        assertEquals(Execution.Status.TIMED_OUT, response.status());
        assertEquals("DEADLINE", response.stopReason());
        assertEquals(Execution.RecordingStatus.UNKNOWN, response.recordingStatus());
        assertTrue(response.inFlightCount() > 0);
        assertEquals(
            "SESSION_BUSY",
            assertThrows(
                    OryxError.class,
                    () ->
                        f.sessions.acquire(
                            response.sessionId(),
                            "cli",
                            "other-user",
                            f.profile,
                            Session.Kind.CONVERSATION))
                .code());
        release.countDown();
        assertTrue(finalSaved.await(5, TimeUnit.SECONDS));
        assertEquals(1, requests.get());
        assertEquals(List.of("first"), f.effects);
        assertEquals(Execution.Status.TIMED_OUT, saved.get().status());
        assertEquals("DEADLINE", saved.get().stopReason());
        assertTrue(
            f.audit.stream()
                .anyMatch(r -> r.toolCallId().equals("late") && r.status().equals("SUCCESS")));
        assertTrue(
            f.audit.stream()
                .anyMatch(
                    r -> r.toolCallId().equals("never") && r.status().equals("NOT_EXECUTED")));
        assertEquals(Execution.Status.TIMED_OUT, handle.await().status());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void lateRecordFailureRetainsDeadlineAndMarksIncomplete() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      // The store can discover the failure before the scheduled deadline callback runs.
      f.clock.nanos = Duration.ofSeconds(60).toNanos();
      f.context.recordingFailed();
      var result = f.context.snapshot();
      assertEquals(Execution.Status.PARTIAL_RECORDING_FAILURE, result.status());
      assertEquals("DEADLINE", result.stopReason());
      assertEquals(Execution.RecordingStatus.INCOMPLETE, result.recordingStatus());
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, f.gate.state());
      f.context.recordingFailed();
      assertEquals("DEADLINE", f.context.snapshot().stopReason());
    }
    try (var f = new LoopFixture(root.resolve("callback-first"), 10)) {
      f.clock.nanos = Duration.ofSeconds(60).toNanos();
      f.context.expire();
      var returned = f.context.snapshot();
      f.context.recordingFailed();
      assertEquals(Execution.Status.TIMED_OUT, returned.status());
      assertEquals(Execution.Status.PARTIAL_RECORDING_FAILURE, f.context.snapshot().status());
      assertEquals("DEADLINE", f.context.snapshot().stopReason());
    }
  }

  @Test
  void initialSessionStorageFailurePausesWorkspaceBeforeAnyProviderCall() throws Exception {
    try (var f = new LoopFixture(root, 10)) {
      var broken =
          new LoopFixture.Store() {
            @Override
            public synchronized void create(Session session) {
              throw new IllegalStateException("disk unavailable");
            }
          };
      var store =
          new ExecutionStore() {
            public void save(Execution.Snapshot s) {
              fail("No execution without session");
            }

            public Execution.Snapshot find(UUID id) {
              return null;
            }
          };
      try (var service =
          new AgentService(
              f.context.workspace(),
              f.catalog(),
              new SessionManager(broken, f.clock),
              store,
              f.loop(),
              f.registry,
              f.gate,
              f.clock,
              ZoneId.of("Asia/Shanghai"),
              new Redactor())) {
        assertEquals(
            "RECORDING_FAILURE",
            assertThrows(
                    OryxError.class,
                    () -> service.start(ProcessRequest.manual("cli", "u", "demo", null, "hello")))
                .code());
        assertEquals(RuntimeGate.State.PAUSED_RECORDING, f.gate.state());
        assertTrue(f.requests.isEmpty());
      }
    }
    try (var f = new LoopFixture(root.resolve("execution-store-failure"), 10)) {
      var broken =
          new ExecutionStore() {
            public void save(Execution.Snapshot s) {
              throw new IllegalStateException("disk unavailable");
            }

            public Execution.Snapshot find(UUID id) {
              return null;
            }
          };
      try (var service =
          new AgentService(
              f.context.workspace(),
              f.catalog(),
              f.sessions,
              broken,
              f.loop(),
              f.registry,
              f.gate,
              f.clock,
              ZoneId.of("Asia/Shanghai"),
              new Redactor())) {
        var result = service.process(ProcessRequest.manual("cli", "u", "demo", null, "hello"));
        assertEquals(Execution.Status.FAILED, result.status());
        assertEquals(Execution.RecordingStatus.INCOMPLETE, result.recordingStatus());
        assertTrue(f.requests.isEmpty());
        assertTrue(f.effects.isEmpty());
        assertEquals(RuntimeGate.State.PAUSED_RECORDING, f.gate.state());
      }
    }
  }
}
