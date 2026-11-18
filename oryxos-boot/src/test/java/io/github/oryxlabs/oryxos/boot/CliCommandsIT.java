package io.github.oryxlabs.oryxos.boot;

import static io.github.oryxlabs.oryxos.boot.support.RuntimeProcess.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.oryxlabs.oryxos.boot.support.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Full entry-point acceptance; use oryxos.acceptance.jar to exercise the built distribution. */
@Tag("us5-cli-process")
class CliCommandsIT {
  @TempDir Path root;

  JsonNode command(int exit, String input, String... args) throws Exception {
    var options = new ArrayList<>(List.of(args));
    options.add("--json");
    var result = RuntimeProcess.cli(root, input, options.toArray(String[]::new));
    assertEquals(exit, result.exit(), result.stdout() + result.stderr());
    assertFalse(result.stdout().contains("LOCAL_SECRET_CANARY"));
    assertFalse(result.stderr().contains("LOCAL_SECRET_CANARY"));
    assertFalse(result.stdout().contains("PRIVATE_OPAQUE_CANARY"));
    assertEquals(1, result.stdout().lines().count(), "stdout must contain one JSON envelope");
    var value = JSON.readTree(result.stdout());
    assertEquals(
        Set.of("code", "message", "data", "timestamp", "traceId"),
        JSON.convertValue(value, Map.class).keySet());
    if (!args[0].equals("chat"))
      assertFalse(
          result.stderr().contains("Starting OryxOsApplication"), "Light commands stay offline");
    evidence("CLI", Map.of("args", List.of(args), "exit", result.exit(), "response", value));
    return value.path("data");
  }

  Map<String, Long> rows() throws Exception {
    var counts = new LinkedHashMap<String, Long>();
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("oryxos.db"));
        var s = c.createStatement()) {
      for (var table :
          List.of("sessions", "messages", "executions", "llm_calls", "tool_invocations")) {
        try (var r = s.executeQuery("SELECT count(*) FROM " + table)) {
          assertTrue(r.next());
          counts.put(table, r.getLong(1));
        }
      }
    }
    return counts;
  }

  @Test
  void twelveOperationsShareTheWorkspaceAndArchiveWithoutDeletingHistory() throws Exception {
    try (var f = new PersistentFixture(root)) {
      var config = Files.readAllBytes(root.resolve("runtime.yaml"));
      command(0, "", "init");
      command(0, "", "init");
      assertArrayEquals(config, Files.readAllBytes(root.resolve("runtime.yaml")));
      command(0, "", "status");
      command(0, "", "profile", "list");
      command(0, "", "profile", "create", "draft");
      assertTrue(Files.exists(root.resolve("agents/draft/AGENT.md")));
      command(0, "", "profile", "show", "local");
      command(0, "", "provider", "list");
      assertEquals("NOT_DISCOVERED", command(0, "", "tool", "list").path("mcpDiscovery").asText());
      command(3, "", "session", "list");
      assertFalse(Files.exists(root.resolve("oryxos.db")));
      assertEquals(0, f.models.get());

      var chat = command(0, "hello\n/quit\n", "chat", "--profile", "local", "--user", "cli-review");
      assertEquals("SUCCEEDED", chat.path("status").asText());
      assertEquals("cli", chat.path("identity").path("channel").asText());
      String session = chat.path("sessionId").asText();
      String execution = chat.path("id").asText();
      assertFalse(session.isBlank());
      try (var serve = new RuntimeProcess(root, "serve")) {
        assertEquals(
            "READY", serve.data("GET", "/runtime/state", null, 200).path("state").asText());
        assertEquals(
            "RUNNING_REGISTRY_SNAPSHOT",
            command(0, "", "tool", "list", "--profile", "local").path("source").asText());
        assertEquals(
            "MATCH",
            command(0, "", "profile", "show", "local").path("snapshotComparison").asText());
        command(4, "do not run\n/quit\n", "chat", "--profile", "local", "--user", "cli-review");
        command(4, "", "profile", "delete", "local");
        assertEquals(1, f.models.get());
        assertEquals(
            "SUCCEEDED",
            serve
                .data(
                    "POST",
                    "/agents/local/invoke",
                    Map.of("content", "HTTP works while CLI is busy"),
                    200)
                .path("status")
                .asText());
        assertEquals(
            2, serve.data("GET", "/sessions/" + session, null, 200).path("messages").size());
        evidence("serve", Map.of("pid", serve.pid(), "chatBusy", true, "httpUsable", true));
      }
      var before = rows();
      assertTrue(before.get("llm_calls") > 0);
      assertTrue(command(0, "", "profile", "delete", "local").path("historyRetained").asBoolean());
      assertFalse(Files.exists(root.resolve("agents/local/AGENT.md")));
      assertEquals(before, rows());
      assertEquals(2, command(0, "", "session", "list", "--profile", "local").path("items").size());
      try (var gateway = new RuntimeProcess(root, "gateway")) {
        assertEquals(
            execution,
            gateway.data("GET", "/executions/" + execution, null, 200).path("id").asText());
        assertEquals(
            2, gateway.data("GET", "/sessions/" + session, null, 200).path("messages").size());
        evidence("gateway", Map.of("pid", gateway.pid(), "historyAfterArchive", true));
      }
      assertEquals(before, rows());
      assertEquals(2, f.models.get());
      assertEquals(0, f.receipts.get());
      evidence(
          "twelve-cli-operations",
          Map.of("operationCount", 12, "models", 2, "effects", 0, "rows", before));
    }
  }

  @Test
  void processExitCodesDistinguishConfigurationMissingFailureAndPause() throws Exception {
    try (var f = new PersistentFixture(root)) {
      command(2, "", "session", "list", "--limit", "0");
      command(3, "", "profile", "show", "missing");
      f.modelStatus = 500;
      var failed =
          command(5, "hello\n/quit\n", "chat", "--profile", "local", "--user", "cli-review");
      assertEquals("FAILED", failed.path("status").asText());
      Files.createDirectories(root.resolve("runtime/runs"));
      Files.writeString(root.resolve("runtime/runs/abandoned.json"), "{}");
      command(7, "", "chat", "--profile", "local", "--user", "cli-review");
      assertEquals(1, f.models.get());
      assertEquals(0, f.receipts.get());
    }
  }

  @Test
  void distributionWaitsRealSixtySecondsAndNeverExecutesTheLateTool() throws Exception {
    try (var f = new PersistentFixture(root)) {
      var release = new CountDownLatch(1);
      f.useTool = true;
      f.beforeModel =
          n -> {
            try {
              release.await(75, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          };
      long started = System.nanoTime();
      try {
        var result =
            command(6, "wait\n/quit\n", "chat", "--profile", "local", "--user", "cli-review");
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(elapsed >= 59000 && elapsed < 75000, "whole JVM elapsed=" + elapsed);
        assertEquals("TIMED_OUT", result.path("status").asText());
        assertFalse(result.path("id").asText().isBlank());
        evidence(
            "cli-real-deadline",
            Map.of("elapsedMs", elapsed, "executionId", result.path("id").asText()));
      } finally {
        release.countDown();
      }
      Thread.sleep(300);
      assertEquals(1, f.models.get());
      assertEquals(0, f.receipts.get());
      try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("oryxos.db"));
          var s = c.createStatement();
          var r = s.executeQuery("SELECT status FROM executions")) {
        assertTrue(r.next());
        assertEquals("TIMED_OUT", r.getString(1));
        assertFalse(r.next());
      }
      evidence("cli-no-late-effect", Map.of("models", f.models.get(), "effects", f.receipts.get()));
    }
  }
}
