package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.config.*;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.core.audit.AuditSink;
import io.github.oryxlabs.oryxos.core.execution.ExecutionStore;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeGate;
import io.github.oryxlabs.oryxos.core.session.SessionStore;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("storage-faults")
@Tag("restart-recovery")
class RestartRecoveryIT {
  @TempDir Path root;

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void productionUsesPersistentPortsAndVersionedTables() throws Exception {
    var workspace = workspace();
    try (var context =
        new BootRuntimeLauncher()
            .start(workspace, Map.of("spring.main.web-application-type", "none"))) {
      assertEquals(
          1,
          context.getBeansOfType(SessionStore.class).size(),
          "Production must supply a persistent SessionStore");
      assertEquals(1, context.getBeansOfType(ExecutionStore.class).size());
      assertTrue(
          context.getBean(SessionStore.class).getClass().getName().contains("JpaSessionStore"));
      assertTrue(context.getBean(AuditSink.class).getClass().getName().contains("JpaAuditSink"));
      assertEquals(RuntimeGate.State.READY, context.getBean(RuntimeGate.class).state());
      try (var c =
              context.getBean(StorageLifecycle.class).resources().dataSource().getConnection();
          var s = c.createStatement();
          var rows = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
        var names = new HashSet<String>();
        while (rows.next()) names.add(rows.getString(1));
        assertTrue(
            names.containsAll(
                List.of(
                    "sessions",
                    "messages",
                    "executions",
                    "workspace_state",
                    "llm_calls",
                    "tool_invocations",
                    "incidents",
                    "memory_entries")));
        assertTrue(names.containsAll(List.of("scheduled_tasks", "task_executions")), "T082 adds persistent schedule state and history");
      }
    }
    assertFalse(WorkspaceProcessLock.isRunning(workspace));
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void abandonedRunPausesWithoutReplacingOldEvidence() throws Exception {
    var workspace = workspace();
    var old = UUID.randomUUID();
    var file = root.resolve("runtime/runs/" + old + ".json");
    Files.createDirectories(file.getParent());
    String evidence = "{\"runId\":\"" + old + "\",\"startedAt\":\"2026-11-13T00:00:00Z\"}";
    Files.writeString(file, evidence);
    try (var context =
        new BootRuntimeLauncher()
            .start(workspace, Map.of("spring.main.web-application-type", "none"))) {
      assertEquals(RuntimeGate.State.PAUSED_RECORDING, context.getBean(RuntimeGate.class).state());
      assertEquals(evidence, Files.readString(file));
    }
    assertEquals(evidence, Files.readString(file));
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void actualJvmChatResumesStructuredHistoryAndAuditsAfterRestart() throws Exception {
    try (var f = new io.github.oryxlabs.oryxos.boot.support.PersistentFixture(root)) {
      f.useTool = true;
      probe("chat", 0);
      var q = new io.github.oryxlabs.oryxos.storage.query.SqliteReadOnlyQueries(f.workspace);
      var session = q.sessions(100).getFirst();
      var first = q.executions(session.id(), 100).getFirst();
      assertEquals("SUCCEEDED", first.get("status"));
      assertEquals(2, q.modelAttempts(UUID.fromString((String) first.get("id"))).size());
      assertEquals(1, q.toolAttempts(UUID.fromString((String) first.get("id"))).size());
      assertEquals(1, f.receipts.get());
      assertTrue(
          q.history(session.id(), null, 100).stream()
              .allMatch(m -> m.message().providerOpaque() == null));
      assertFalse(
          q.modelAttempts(UUID.fromString((String) first.get("id")))
              .toString()
              .contains("PRIVATE_OPAQUE_CANARY"));
      assertTrue(
          q.modelAttempts(UUID.fromString((String) first.get("id"))).stream()
              .allMatch(m -> m.get("input_tokens") == null && m.get("output_tokens") == null));
      probe("chat", 0, session.id().toString());
      assertEquals(3, f.models.get());
      assertEquals(1, f.receipts.get());
      var messages = f.requests.getLast().path("messages");
      assertEquals(
          2,
          messages.findValuesAsText("content").stream()
              .filter(v -> v.equals("remember this turn"))
              .count());
      assertTrue(messages.toString().contains("PRIVATE_OPAQUE_CANARY"));
      assertTrue(messages.toString().contains("send-1"));
      assertEquals(2, q.executions(session.id(), 100).size());
      assertEquals(6, q.history(session.id(), null, 100).size());
      try (var files = Files.list(root.resolve("runtime/runs"))) {
        assertEquals(0, files.count());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void actualJvmCrashLeavesStartedAttemptAndRequiresRecoveryWithoutReplay() throws Exception {
    try (var f = new io.github.oryxlabs.oryxos.boot.support.PersistentFixture(root)) {
      probe("crash", 23);
      UUID execution = UUID.fromString(Files.readString(root.resolve("probe-execution.txt")));
      List<Path> old;
      try (var files = Files.list(root.resolve("runtime/runs"))) {
        old = files.toList();
      }
      assertEquals(1, old.size());
      String original = Files.readString(old.getFirst());
      try (var c = f.start()) {
        var recovery = c.getBean(io.github.oryxlabs.oryxos.core.runtime.RecoveryPort.class);
        var state = recovery.state();
        assertEquals("PAUSED_RECORDING", state.state());
        assertEquals(original, Files.readString(old.getFirst()));
        assertTrue(
            state.currentIncident().unknownActions().stream()
                .anyMatch(a -> a.executionId().equals(execution)));
        assertFalse(state.currentIncident().detailsComplete());
        assertEquals(
            io.github.oryxlabs.oryxos.core.execution.Execution.Status.INTERRUPTED,
            c.getBean(ExecutionStore.class).find(execution).status());
        assertEquals(0, f.models.get());
        assertEquals(
            "READY",
            recovery
                .recover(
                    new io.github.oryxlabs.oryxos.core.runtime.RecoveryPort.Request(
                        state.incidentId(),
                        true,
                        "Crash inspected; no replay; missing records acknowledged",
                        "reviewer"))
                .state());
        assertEquals(0, f.models.get());
      }
      try (var c = f.start()) {
        assertEquals(RuntimeGate.State.READY, c.getBean(RuntimeGate.class).state());
        assertEquals(0, f.models.get());
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void emptySessionSnapshotArchiveAndReadonlyQueriesSurviveRestart() throws Exception {
    try (var f = new io.github.oryxlabs.oryxos.boot.support.PersistentFixture(root)) {
      UUID id;
      try (var c = f.start()) {
        var manager = c.getBean(io.github.oryxlabs.oryxos.core.session.SessionManager.class);
        var profile = c.getBean(ToolConfiguration.CheckedAgents.class).catalog().require("local");
        try (var lease =
            manager.acquire(
                null,
                "cli",
                "alice",
                profile,
                io.github.oryxlabs.oryxos.core.session.Session.Kind.CONVERSATION)) {
          id = lease.session().id();
        }
      }
      Path agent = root.resolve("agents/local/AGENT.md");
      Files.writeString(agent, Files.readString(agent).replace("requested-model", "changed-model"));
      try (var c = f.start()) {
        assertEquals("requested-model", c.getBean(SessionStore.class).find(id).model());
        var service = c.getBean(AgentRuntimeConfiguration.RuntimeAgents.class).require();
        var error =
            assertThrows(
                io.github.oryxlabs.oryxos.core.error.OryxError.class,
                () ->
                    service.process(
                        io.github.oryxlabs.oryxos.core.execution.ProcessRequest.manual(
                            "cli", "alice", "local", id, "continue")));
        assertEquals("SESSION_MODEL_CHANGED", error.code());
        assertEquals(0, f.models.get());
        c.getBean(io.github.oryxlabs.oryxos.core.session.SessionManager.class)
            .archive(id, "cli", "alice", "local");
      }
      var q = new io.github.oryxlabs.oryxos.storage.query.SqliteReadOnlyQueries(f.workspace);
      assertEquals(
          io.github.oryxlabs.oryxos.core.session.Session.Status.ARCHIVED, q.session(id).status());
      assertNotNull(q.session(id).archivedAt());
      assertTrue(q.history(id, null, 100).isEmpty());
      var missing = root.resolve("missing");
      Files.createDirectory(missing);
      assertThrows(
          io.github.oryxlabs.oryxos.core.error.OryxError.class,
          () ->
              new io.github.oryxlabs.oryxos.storage.query.SqliteReadOnlyQueries(
                      new WorkspaceHandle(missing))
                  .sessions(100));
      assertFalse(Files.exists(missing.resolve("oryxos.db")));
    }
  }

  void probe(String mode, int exit, String... more) throws Exception {
    var cmd =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                io.github.oryxlabs.oryxos.boot.support.PersistentRuntimeProbe.class.getName(),
                root.toString(),
                mode));
    cmd.addAll(List.of(more));
    var log = root.resolve("probe-" + UUID.randomUUID() + ".log");
    var builder = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().clear();
    builder.environment().put("PATH", "/usr/bin:/bin");
    var process = builder.start();
    try {
      assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(exit, process.exitValue(), Files.readString(log));
      assertFalse(Files.readString(log).contains("LOCAL_SECRET_CANARY"));
      assertFalse(Files.readString(log).contains("PRIVATE_OPAQUE_CANARY"));
    } finally {
      if (process.isAlive()) process.destroyForcibly().waitFor();
    }
  }

  WorkspaceHandle workspace() {
    var w = new WorkspaceHandle(root);
    new WorkspaceInitializer()
        .initialize(w, Map.of("runtime.yaml", "schema_version: 1\nproviders: {}\n"));
    return w;
  }
}
