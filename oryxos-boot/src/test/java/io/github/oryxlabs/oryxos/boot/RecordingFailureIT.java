package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.config.StorageLifecycle;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("storage-faults")
class RecordingFailureIT {
  @TempDir Path root;

  static io.github.oryxlabs.oryxos.core.execution.ProcessRequest request() {
    return new io.github.oryxlabs.oryxos.core.execution.ProcessRequest(
        "cli",
        "alice",
        "local",
        null,
        "send hello",
        io.github.oryxlabs.oryxos.core.execution.ProcessRequest.Trigger.MANUAL,
        null);
  }

  static void sql(org.springframework.context.ConfigurableApplicationContext c, String sql)
      throws Exception {
    try (var connection =
            c.getBean(StorageLifecycle.class).resources().dataSource().getConnection();
        var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  static long count(org.springframework.context.ConfigurableApplicationContext c, String table)
      throws Exception {
    try (var connection =
            c.getBean(StorageLifecycle.class).resources().dataSource().getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
      return rows.getLong(1);
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void userInsertFailureRollsBackSessionExecutionAndMessageBeforeAnyIo() throws Exception {
    try (var f = new io.github.oryxlabs.oryxos.boot.support.PersistentFixture(root);
        var c = f.start()) {
      sql(
          c,
          "CREATE TRIGGER fail_user BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT,'injected');"
              + " END");
      var r =
          c.getBean(
                  io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration.RuntimeAgents
                      .class)
              .require()
              .process(request());
      assertEquals(
          io.github.oryxlabs.oryxos.core.execution.Execution.RecordingStatus.INCOMPLETE,
          r.recordingStatus());
      assertEquals(0, f.models.get());
      assertEquals(0, count(c, "sessions"));
      assertEquals(0, count(c, "executions"));
      assertEquals(0, count(c, "messages"));
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, c.getBean(RuntimeGate.class).state());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void startedFailureSendsNothingAndRequiresExplicitAcknowledgement() throws Exception {
    try (var f = new io.github.oryxlabs.oryxos.boot.support.PersistentFixture(root);
        var c = f.start()) {
      sql(
          c,
          "CREATE TRIGGER fail_intent BEFORE INSERT ON llm_calls BEGIN SELECT"
              + " RAISE(ABORT,'injected'); END");
      var service =
          c.getBean(
                  io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration.RuntimeAgents
                      .class)
              .require();
      var r = service.process(request());
      assertEquals(0, f.models.get());
      assertEquals(
          io.github.oryxlabs.oryxos.core.execution.Execution.RecordingStatus.INCOMPLETE,
          r.recordingStatus());
      var recovery = c.getBean(RecoveryPort.class);
      var incident = recovery.state().currentIncident();
      assertNotNull(incident);
      assertEquals(incident.id(), recovery.state().incidentId());
      assertFalse(incident.detailsComplete());
      sql(c, "DROP TRIGGER fail_intent");
      assertEquals("PAUSED_RECORDING", recovery.state().state());
      assertThrows(
          io.github.oryxlabs.oryxos.core.error.OryxError.class, () -> service.process(request()));
      assertThrows(
          io.github.oryxlabs.oryxos.core.error.OryxError.class,
          () ->
              recovery.recover(
                  new RecoveryPort.Request(incident.id(), false, "checked", "reviewer")));
      assertThrows(
          io.github.oryxlabs.oryxos.core.error.OryxError.class,
          () ->
              recovery.recover(
                  new RecoveryPort.Request(UUID.randomUUID(), true, "checked", "reviewer")));
      var ack =
          new RecoveryPort.Request(
              incident.id(),
              true,
              "Checked recipient; no request was received. Missing records acknowledged.",
              "reviewer");
      assertEquals("READY", recovery.recover(ack).state());
      assertEquals("READY", recovery.recover(ack).state());
      assertEquals(0, f.models.get());
      assertEquals(
          io.github.oryxlabs.oryxos.core.execution.Execution.Status.SUCCEEDED,
          service.process(request()).status());
      assertEquals(1, f.models.get());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void acceptedSideEffectAndFailedResultNeverReplayAcrossRestart() throws Exception {
    try (var f = new io.github.oryxlabs.oryxos.boot.support.PersistentFixture(root)) {
      f.useTool = true;
      UUID incident;
      UUID execution;
      try (var c = f.start()) {
        sql(
            c,
            "CREATE TRIGGER fail_result BEFORE UPDATE ON tool_invocations WHEN NEW.status='SUCCESS'"
                + " BEGIN SELECT RAISE(ABORT,'injected'); END");
        var r =
            c.getBean(
                    io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration.RuntimeAgents
                        .class)
                .require()
                .process(request());
        execution = r.id();
        assertEquals(
            io.github.oryxlabs.oryxos.core.execution.Execution.Status.PARTIAL_RECORDING_FAILURE,
            r.status());
        assertEquals(1, f.models.get());
        assertEquals(1, f.receipts.get());
        var state = c.getBean(RecoveryPort.class).state();
        incident = state.incidentId();
        assertNotNull(incident);
        assertTrue(state.currentIncident().affectedExecutionIds().contains(execution));
        assertFalse(state.currentIncident().detailsComplete());
        sql(c, "DROP TRIGGER fail_result");
      }
      try (var c = f.start()) {
        var recovery = c.getBean(RecoveryPort.class);
        assertEquals("PAUSED_RECORDING", recovery.state().state());
        assertEquals(incident, recovery.state().incidentId());
        assertEquals(1, f.receipts.get());
        assertTrue(
            recovery.state().currentIncident().knownActions().stream()
                .anyMatch(a -> "send-1".equals(a.toolCallId()) && a.outcome().equals("SUCCESS")));
        assertEquals(
            "READY",
            recovery
                .recover(
                    new RecoveryPort.Request(
                        incident,
                        true,
                        "Recipient confirmed one receipt; missing result remains unknown. Do not"
                            + " replay.",
                        "reviewer"))
                .state());
        assertEquals(1, f.receipts.get());
        assertEquals(1, f.models.get());
        assertEquals(1, count(c, "incidents"));
      }
      try (var c = f.start()) {
        assertEquals("READY", c.getBean(RecoveryPort.class).state().state());
        assertEquals(1, f.receipts.get());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void recoveryWaitsForInflightActionAndFinalRecording() throws Exception {
    try (var f = new io.github.oryxlabs.oryxos.boot.support.PersistentFixture(root);
        var c = f.start()) {
      f.useTool = true;
      var received = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      f.afterReceipt =
          () -> {
            received.countDown();
            try {
              release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          };
      var service =
          c.getBean(
                  io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration.RuntimeAgents
                      .class)
              .require();
      var first = service.start(request());
      assertTrue(received.await(3, java.util.concurrent.TimeUnit.SECONDS));
      sql(
          c,
          "CREATE TRIGGER fail_intent BEFORE INSERT ON llm_calls BEGIN SELECT"
              + " RAISE(ABORT,'injected'); END");
      service.process(request());
      var recovery = c.getBean(RecoveryPort.class);
      var state = recovery.state();
      var ack =
          new RecoveryPort.Request(
              state.incidentId(), true, "Checked records and receiver; no replay", "reviewer");
      try {
        assertThrows(
            io.github.oryxlabs.oryxos.core.error.OryxError.class, () -> recovery.recover(ack));
        assertEquals(1, f.receipts.get());
      } finally {
        release.countDown();
      }
      first.await();
      sql(c, "DROP TRIGGER fail_intent");
      assertEquals("PAUSED_RECORDING", recovery.state().state());
      assertEquals("READY", recovery.recover(ack).state());
      assertEquals(1, f.receipts.get());
      assertEquals(1, f.models.get());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void runEvidenceStillPreventsRestartBypassWhenDatabaseAndFaultWritesFail() throws Exception {
    try (var f = new io.github.oryxlabs.oryxos.boot.support.PersistentFixture(root)) {
      f.useTool = true;
      List<Path> old;
      try (var c = f.start()) {
        var fault = root.resolve("runtime/fault.json");
        Files.createDirectory(fault);
        Files.writeString(fault.resolve("obstruction"), "test fault");
        f.afterReceipt = () -> c.getBean(StorageLifecycle.class).resources().close();
        var result =
            c.getBean(
                    io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration.RuntimeAgents
                        .class)
                .require()
                .process(request());
        assertEquals(
            io.github.oryxlabs.oryxos.core.execution.Execution.Status.PARTIAL_RECORDING_FAILURE,
            result.status());
        assertEquals(1, f.receipts.get());
        try (var paths = Files.list(root.resolve("runtime/runs"))) {
          old = paths.toList();
        }
        assertEquals(1, old.size());
        assertTrue(Files.isRegularFile(old.getFirst()));
      }
      try (var c = f.start()) {
        var recovery = c.getBean(RecoveryPort.class);
        var state = recovery.state();
        assertEquals("PAUSED_RECORDING", state.state());
        assertFalse(state.currentIncident().detailsComplete());
        assertTrue(state.currentIncident().evidenceSources().contains("RUN_MARKER"));
        assertTrue(Files.exists(old.getFirst()));
        assertEquals(1, f.receipts.get());
        Files.delete(root.resolve("runtime/fault.json/obstruction"));
        Files.delete(root.resolve("runtime/fault.json"));
        assertEquals(
            "READY",
            recovery
                .recover(
                    new RecoveryPort.Request(
                        state.incidentId(),
                        true,
                        "Receiver confirmed one receipt; original fault details unavailable; do not"
                            + " resend",
                        "reviewer"))
                .state());
        assertEquals(1, f.receipts.get());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void repairedStorageRebuildsTheOriginalPortsWithoutMemoryFallback() throws Exception {
    try (var f = new io.github.oryxlabs.oryxos.boot.support.PersistentFixture(root)) {
      Files.writeString(f.workspace.database(), "broken database");
      try (var c = f.start()) {
        var sessions = c.getBean(io.github.oryxlabs.oryxos.core.session.SessionStore.class);
        var executions = c.getBean(io.github.oryxlabs.oryxos.core.execution.ExecutionStore.class);
        var recovery = c.getBean(RecoveryPort.class);
        var incident = recovery.state().incidentId();
        assertNotNull(incident);
        assertThrows(
            io.github.oryxlabs.oryxos.core.error.OryxError.class,
            () ->
                c.getBean(
                        io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration
                            .RuntimeAgents.class)
                    .require()
                    .process(request()));
        Files.move(f.workspace.database(), root.resolve("corrupt-evidence.db"));
        var ack =
            new RecoveryPort.Request(
                incident,
                true,
                "Corrupt fixture retained for inspection; unrecoverable history acknowledged",
                "reviewer");
        assertEquals("READY", recovery.recover(ack).state());
        assertSame(sessions, c.getBean(io.github.oryxlabs.oryxos.core.session.SessionStore.class));
        assertSame(
            executions, c.getBean(io.github.oryxlabs.oryxos.core.execution.ExecutionStore.class));
        var result =
            c.getBean(
                    io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration.RuntimeAgents
                        .class)
                .require()
                .process(request());
        assertEquals(
            io.github.oryxlabs.oryxos.core.execution.Execution.Status.SUCCEEDED, result.status());
        assertNotNull(executions.find(result.id()));
        assertNotNull(sessions.find(result.sessionId()));
        assertEquals("broken database", Files.readString(root.resolve("corrupt-evidence.db")));
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void unreadableDatabaseKeepsDiagnosticAndRecoveryServiceAvailable() throws Exception {
    var w = new WorkspaceHandle(root);
    new WorkspaceInitializer()
        .initialize(w, Map.of("runtime.yaml", "schema_version: 1\nproviders: {}\n"));
    Files.writeString(w.database(), "broken database");
    try (var context =
        new BootRuntimeLauncher().start(w, Map.of("spring.main.web-application-type", "none"))) {
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, context.getBean(RuntimeGate.class).state());
      assertFalse(context.getBean(StorageLifecycle.class).availability().available());
      assertTrue(
          Arrays.stream(context.getBeanDefinitionNames())
              .anyMatch(name -> name.equals("runtimeRecoveryService")),
          "Diagnosis/recovery must survive storage failure");
      assertTrue(
          Files.isRegularFile(root.resolve("runtime/fault.json")),
          "Independent fault evidence must be durable");
    }
    assertEquals("broken database", Files.readString(w.database()));
  }
}
