package io.github.oryxlabs.oryxos.boot;

import static io.github.oryxlabs.oryxos.boot.support.RuntimeProcess.JSON;
import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.boot.support.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us5-process")
@Tag("us5-http")
class CrossProcessAcceptanceIT {
  @TempDir Path root;

  void sql(String statement) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("oryxos.db"));
        var s = c.createStatement()) {
      s.execute(statement);
    }
  }

  void setup(PersistentFixture f) throws Exception {
    Files.writeString(
        root.resolve("secrets.local.yaml"),
        "LOCAL_NOTIFY: " + f.url() + "\n",
        StandardOpenOption.APPEND);
    var agent = root.resolve("agents/local/AGENT.md");
    Files.writeString(
        agent,
        Files.readString(agent)
            .replace(
                "tools: [http_post]",
                "tools: [http_post, save_memory, notify]\n"
                    + "notify_channels:\n"
                    + "  local:\n"
                    + "    type: webhook\n"
                    + "    url: '${LOCAL_NOTIFY}'\n"
                    + "    payload: {text: '$CONTENT'}\n"
                    + "    acknowledgement: {mode: http_2xx}\n"
                    + "schedules:\n"
                    + "  - id: daily\n"
                    + "    cron: '0 0 8 * * *'\n"
                    + "    message: scheduled test\n"
                    + "    enabled: false"));
  }

  static Object tool(String name, Map<String, Object> args) throws Exception {
    return Map.of(
        "role",
        "assistant",
        "tool_calls",
        List.of(
            Map.of(
                "id",
                "call-1",
                "type",
                "function",
                "function",
                Map.of("name", name, "arguments", JSON.writeValueAsString(args)))));
  }

  @Test
  void historyMemoryAuditsScheduleAndEmptySnapshotsSurviveDistinctJvms() throws Exception {
    try (var f = new PersistentFixture(root)) {
      setup(f);
      f.modelMessage =
          n -> {
            try {
              var m = f.requests.getLast().path("messages");
              String last = m.get(m.size() - 1).path("content").asText();
              if (last.startsWith("remember-"))
                return tool(
                    "save_memory",
                    Map.of(
                        "scope",
                        last.endsWith("core") ? "CORE" : "ARCHIVAL",
                        "content",
                        "persistent shared preference"));
              return Map.of("role", "assistant", "content", "saved");
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          };
      String empty, active, archived, execution;
      long pid;
      try (var p = new RuntimeProcess(root, "serve")) {
        pid = p.pid();
        empty = p.data("POST", "/sessions", Map.of("agentName", "local"), 201).path("id").asText();
        active = p.data("POST", "/sessions", Map.of("agentName", "local"), 201).path("id").asText();
        archived =
            p.data("POST", "/sessions", Map.of("agentName", "local"), 201).path("id").asText();
        p.data("DELETE", "/sessions/" + archived, null, 200);
        assertEquals(
            "SESSION_ARCHIVED",
            p.call("POST", "/sessions/" + archived + "/messages", Map.of("content", "hello"), 409)
                .path("code")
                .asText());
        execution =
            p.data(
                    "POST",
                    "/sessions/" + active + "/messages",
                    Map.of("content", "remember-core"),
                    200)
                .path("id")
                .asText();
        p.data("POST", "/agents/local/invoke", Map.of("content", "remember-archival"), 200);
        p.data("POST", "/schedules/local:daily/run", null, 200);
        p.data("PUT", "/schedules/local:daily/enabled", Map.of("enabled", false), 200);
      }
      int calls = f.models.get();
      var agent = root.resolve("agents/local/AGENT.md");
      Files.writeString(agent, Files.readString(agent).replace("requested-model", "new-model"));
      // Simulate an old stored time: startup must neither replay it nor override file definitions.
      sql("UPDATE task_executions SET created_at='2000-01-01T00:00:00Z'");
      try (var p = new RuntimeProcess(root, "gateway")) {
        assertNotEquals(pid, p.pid());
        assertEquals(calls, f.models.get());
        var h = p.data("GET", "/sessions/" + active, null, 200);
        assertEquals(4, h.path("messages").size());
        assertEquals("http", h.path("session").path("identity").path("channel").asText());
        assertEquals(
            "ARCHIVED",
            p.data("GET", "/sessions/" + archived, null, 200)
                .path("session")
                .path("status")
                .asText());
        assertEquals(
            "requested-model",
            p.data("GET", "/sessions/" + empty, null, 200).path("session").path("model").asText());
        assertEquals(
            "SESSION_MODEL_CHANGED",
            p.call("POST", "/sessions/" + empty + "/messages", Map.of("content", "hello"), 409)
                .path("code")
                .asText());
        assertEquals(
            "SESSION_IDENTITY_MISMATCH",
            p.call(
                    "POST",
                    "/sessions/" + active + "/messages",
                    Map.of("content", "hello"),
                    409,
                    "other")
                .path("code")
                .asText());
        assertEquals(
            "SESSION_MODEL_CHANGED",
            p.call("POST", "/sessions/" + archived + "/messages", Map.of("content", "hello"), 409)
                .path("code")
                .asText());
        var audit = p.data("GET", "/executions/" + execution, null, 200);
        assertEquals(2, audit.path("llmCalls").size());
        assertEquals(1, audit.path("toolAttempts").size());
        var memory = p.data("GET", "/memory", null, 200);
        assertEquals(1, memory.path("core").size());
        assertEquals(1, memory.path("archival").size());
        assertFalse(
            p.data("GET", "/schedules", null, 200)
                .path("items")
                .get(0)
                .path("enabled")
                .asBoolean());
        assertEquals(
            1, p.data("GET", "/schedules/local:daily/executions", null, 200).path("items").size());
        assertEquals(
            "new-model",
            p.data("POST", "/sessions", Map.of("agentName", "local"), 201).path("model").asText());
        assertEquals(calls, f.models.get());
      }
      var cli = RuntimeProcess.cli(root, "", "--json", "session", "list", "--profile", "local");
      assertEquals(0, cli.exit(), cli.stderr());
      assertTrue(cli.stdout().contains(active));
      assertTrue(cli.stdout().contains(empty));
      assertEquals(calls, f.models.get());
      RuntimeProcess.evidence(
          "history-memory-audit-schedule",
          Map.of(
              "executionId",
              execution,
              "activeSession",
              active,
              "emptySession",
              empty,
              "archivedSession",
              archived,
              "modelCalls",
              calls,
              "notificationReceipts",
              f.receipts.get(),
              "pass",
              true));
    }
  }

  @Test
  void httpAdmissionRecordFailureStopsAllIoAcrossRestartUntilExplicitRecovery() throws Exception {
    try (var f = new PersistentFixture(root)) {
      setup(f);
      try (var p = new RuntimeProcess(root, "serve")) {
        sql(
            "CREATE TRIGGER fail_before_io BEFORE INSERT ON llm_calls BEGIN SELECT"
                + " RAISE(ABORT,'local fault'); END");
        assertEquals(
            "INCOMPLETE",
            p.data("POST", "/agents/local/invoke", Map.of("content", "hello"), 500)
                .path("recordingStatus")
                .asText());
        assertEquals(0, f.models.get());
        assertEquals(0, f.receipts.get());
        p.call("POST", "/schedules/local:daily/run", null, 503);
        sql("DROP TRIGGER fail_before_io");
        assertEquals(
            "PAUSED_RECORDING", p.data("GET", "/runtime/state", null, 200).path("state").asText());
      }
      var cli =
          RuntimeProcess.cli(
              root, "hello\n/quit\n", "--json", "chat", "--profile", "local", "--user", "reviewer");
      assertEquals(7, cli.exit(), cli.stderr());
      try (var p = new RuntimeProcess(root, "serve")) {
        var state = p.data("GET", "/runtime/state", null, 200);
        assertEquals("PAUSED_RECORDING", state.path("state").asText());
        var ack =
            Map.of(
                "incidentId",
                state.path("incidentId").asText(),
                "acknowledgeMissingRecords",
                true,
                "notes",
                "No local model or notification request received; no replay.");
        assertEquals("READY", p.data("POST", "/runtime/recover", ack, 200).path("state").asText());
        assertEquals(0, f.models.get());
        assertEquals(0, f.receipts.get());
        RuntimeProcess.evidence(
            "before-IO-fault",
            Map.of(
                "modelCalls",
                f.models.get(),
                "notificationReceipts",
                f.receipts.get(),
                "pass",
                true));
      }
    }
  }

  @Test
  void realNotifyAcceptedOnceWithBrokenFaultEvidenceRequiresHttpRecoveryAfterProcessRestart()
      throws Exception {
    try (var f = new PersistentFixture(root)) {
      setup(f);
      f.modelMessage =
          n -> {
            try {
              return tool(
                  "notify",
                  Map.of("content", "local acceptance notification", "targetName", "local"));
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          };
      f.afterReceipt =
          () -> {
            try {
              sql(
                  "CREATE TRIGGER fail_result BEFORE UPDATE ON tool_invocations BEGIN SELECT"
                      + " RAISE(ABORT,'local fault'); END");
              sql(
                  "CREATE TRIGGER fail_incident BEFORE INSERT ON incidents BEGIN SELECT"
                      + " RAISE(ABORT,'local fault'); END");
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          };
      String execution;
      Map<Path, String> old = new HashMap<>();
      try (var p = new RuntimeProcess(root, "serve")) {
        Files.createDirectory(root.resolve("runtime/fault.json"));
        Files.writeString(root.resolve("runtime/fault.json/obstruction"), "local fault");
        var e = p.data("POST", "/agents/local/invoke", Map.of("content", "notify"), 500);
        execution = e.path("id").asText();
        assertEquals("INCOMPLETE", e.path("recordingStatus").asText());
        assertEquals(1, f.models.get());
        assertEquals(1, f.receipts.get());
        assertEquals(
            "EXECUTION_PAUSED",
            p.call("POST", "/agents/local/invoke", Map.of("content", "again"), 503)
                .path("code")
                .asText());
        p.call("POST", "/schedules/local:daily/run", null, 503);
        try (var paths = Files.list(root.resolve("runtime/runs"))) {
          for (var path : paths.toList()) old.put(path, Files.readString(path));
        }
        assertFalse(old.isEmpty());
        sql("DROP TRIGGER fail_result");
        sql("DROP TRIGGER fail_incident");
        assertEquals(
            "PAUSED_RECORDING", p.data("GET", "/runtime/state", null, 200).path("state").asText());
      }
      try (var p = new RuntimeProcess(root, "serve")) {
        for (var e : old.entrySet()) assertEquals(e.getValue(), Files.readString(e.getKey()));
        var s = p.data("GET", "/runtime/state", null, 200);
        assertEquals("PAUSED_RECORDING", s.path("state").asText());
        assertFalse(s.path("currentIncident").path("detailsComplete").asBoolean());
        assertEquals(s.path("incidentId"), s.path("currentIncident").path("id"));
        assertTrue(
            s.path("currentIncident").path("evidenceSources").toString().contains("RUN_MARKER"));
        p.call("POST", "/agents/local/invoke", Map.of("content", "again"), 503);
        var bad =
            Map.of(
                "incidentId",
                s.path("incidentId").asText(),
                "acknowledgeMissingRecords",
                false,
                "notes",
                "not acknowledged");
        p.call("POST", "/runtime/recover", bad, 400);
        Files.delete(root.resolve("runtime/fault.json/obstruction"));
        Files.delete(root.resolve("runtime/fault.json"));
        assertEquals(
            "PAUSED_RECORDING", p.data("GET", "/runtime/state", null, 200).path("state").asText());
        var ack =
            Map.of(
                "incidentId",
                s.path("incidentId").asText(),
                "acknowledgeMissingRecords",
                true,
                "notes",
                "One local notification received; missing evidence acknowledged; no replay.");
        assertEquals("READY", p.data("POST", "/runtime/recover", ack, 200).path("state").asText());
        p.data("POST", "/runtime/recover", ack, 200);
        assertEquals(
            "INCOMPLETE",
            p.data("GET", "/executions/" + execution, null, 200).path("recordingStatus").asText());
        assertEquals(1, f.models.get());
        assertEquals(1, f.receipts.get());
      }
      try (var p = new RuntimeProcess(root, "serve")) {
        assertEquals("READY", p.data("GET", "/runtime/state", null, 200).path("state").asText());
        assertEquals(1, f.receipts.get());
        assertEquals(1, f.models.get());
        RuntimeProcess.evidence(
            "notify-record-fault-restart-recovery",
            Map.of(
                "executionId",
                execution,
                "modelCalls",
                f.models.get(),
                "notificationReceipts",
                f.receipts.get(),
                "oldRunMarkers",
                old.size(),
                "pass",
                true));
      }
    }
  }
}
