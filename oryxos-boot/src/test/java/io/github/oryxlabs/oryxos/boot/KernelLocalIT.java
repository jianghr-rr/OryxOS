package io.github.oryxlabs.oryxos.boot;

import static io.github.oryxlabs.oryxos.boot.support.RuntimeProcess.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.oryxlabs.oryxos.boot.support.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** T097: tests precede the example implementation; fixtures never replace production stores. */
@Tag("kernel-local")
class KernelLocalIT {
  static final Path REPO =
      Path.of(System.getProperty("oryxos.test.evidenceDir")).getParent().getParent();
  @TempDir Path root;

  @Test
  void examplesHaveTheRequiredBusinessResources() throws Exception {
    for (var file :
        List.of(
            "runtime.example.yaml",
            "agents/daily-weather/AGENT.md",
            "agents/daily-tech-digest/AGENT.md",
            "agents/daily-tech-digest/skills/digest-format.md",
            "agents/github-daily/AGENT.md",
            "agents/github-daily/scripts/github_trending.py")) {
      assertTrue(
          Files.isRegularFile(REPO.resolve("examples").resolve(file)),
          "Missing T096 resource: " + file);
    }
  }

  JsonNode cli(String agent, String content) throws Exception {
    var result =
        RuntimeProcess.cli(
            root,
            content + "\n/quit\n",
            "chat",
            "--profile",
            agent,
            "--user",
            "kernel-author",
            "--json");
    assertEquals(0, result.exit(), result.stdout() + result.stderr());
    assertEquals(1, result.stdout().lines().count());
    var data = JSON.readTree(result.stdout()).path("data");
    evidence("kernel-cli", Map.of("agent", agent, "response", data));
    return data;
  }

  JsonNode preference() throws Exception {
    var execution = cli("daily-tech-digest", "请记住：" + KernelFixture.PREFERENCE);
    assertEquals("SUCCEEDED", execution.path("status").asText());
    return execution;
  }

  JsonNode invoke(RuntimeProcess p, String agent) throws Exception {
    return p.data("POST", "/agents/" + agent + "/invoke", Map.of("content", "请执行今日任务"), 200);
  }

  JsonNode detail(RuntimeProcess p, JsonNode execution, String channel, List<String> tools)
      throws Exception {
    String id = execution.path("id").asText();
    assertFalse(id.isBlank());
    var data = p.data("GET", "/executions/" + id, null, 200);
    assertEquals(channel, data.path("identity").path("channel").asText());
    assertEquals("Asia/Shanghai", data.path("timezone").asText());
    assertEquals("COMPLETE", data.path("recordingStatus").asText());
    var actual = new ArrayList<String>();
    data.path("toolAttempts").forEach(t -> actual.add(t.path("name").asText()));
    assertEquals(tools, actual);
    assertFalse(data.path("llmCalls").isEmpty());
    for (var call : data.path("llmCalls")) {
      assertEquals("SUCCESS", call.path("status").asText());
      assertEquals("example", call.path("providerName").asText());
      assertEquals("LOCAL-controlled-model", call.path("model").asText());
    }
    var history = p.data("GET", "/sessions/" + data.path("sessionId").asText(), null, 200);
    var proposed = new HashSet<String>();
    var answered = new HashSet<String>();
    for (var message : history.path("messages")) {
      assertEquals(id, message.path("executionId").asText());
      for (var call : message.path("toolCalls")) assertTrue(proposed.add(call.path("id").asText()));
      if (message.path("role").asText().equals("TOOL"))
        assertTrue(answered.add(message.path("toolCallId").asText()));
    }
    assertEquals(proposed, answered);
    assertEquals(tools.size(), answered.size());
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("oryxos.db"))) {
      for (var table : List.of("tool_invocations", "llm_calls")) {
        try (var s =
            c.prepareStatement("SELECT count(*) FROM " + table + " WHERE execution_id=?")) {
          s.setString(1, id);
          try (var rows = s.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(
                data.path(table.equals("llm_calls") ? "llmCalls" : "toolAttempts").size(),
                rows.getInt(1));
          }
        }
      }
    }
    return data;
  }

  List<JsonNode> scenarios() throws Exception {
    var cases = new ArrayList<JsonNode>();
    JSON.readTree(
            Files.readString(REPO.resolve("oryxos-boot/src/test/resources/kernel/scenarios.json")))
        .path("agents")
        .forEach(cases::add);
    return cases;
  }

  List<String> tools(JsonNode scenario) {
    var names = new ArrayList<String>();
    scenario.path("tools").forEach(t -> names.add(t.asText()));
    return names;
  }

  String source(String agent) {
    return agent.equals("daily-weather")
        ? "weather"
        : agent.equals("daily-tech-digest") ? "news" : "github";
  }

  JsonNode scheduled(RuntimeProcess p, String agent) throws Exception {
    String path = "/schedules/" + agent + ":daily";
    p.data("PUT", path + "/enabled", Map.of("enabled", true), 200);
    JsonNode event = null;
    long end = System.nanoTime() + Duration.ofSeconds(25).toNanos();
    while (System.nanoTime() < end) {
      var items = p.data("GET", path + "/executions", null, 200).path("items");
      if (!items.isEmpty()) {
        assertEquals(1, items.size());
        event = items.get(0);
        break;
      }
      Thread.sleep(150);
    }
    p.data("PUT", path + "/enabled", Map.of("enabled", false), 200);
    assertNotNull(event, "Real cron must fire");
    assertEquals("SCHEDULED", event.path("triggerKind").asText());
    assertEquals("DISPATCHED", event.path("status").asText());
    JsonNode execution = null;
    while (System.nanoTime() < end) {
      execution = p.data("GET", "/executions/" + event.path("executionId").asText(), null, 200);
      if (!execution.path("status").asText().equals("RUNNING")) break;
      Thread.sleep(50);
    }
    assertNotNull(execution);
    assertEquals("SUCCEEDED", execution.path("status").asText(), execution.toString());
    return execution;
  }

  @Test
  void actualCliHttpAndAutomaticCronSharePreferencesAndPersistentAudits() throws Exception {
    try (var f = new KernelFixture(root)) {
      var saved = preference();
      var weather = cli("daily-weather", "请执行今日任务");
      try (var p = new RuntimeProcess(root, "serve")) {
        detail(p, saved, "cli", List.of("save_memory"));
        assertTrue(
            p.data("GET", "/memory", null, 200).toString().contains(KernelFixture.PREFERENCE));
        var sessionIds = new HashSet<String>();
        for (var scenario : scenarios()) {
          String agent = scenario.path("name").asText();
          var manual = agent.equals("daily-weather") ? weather : invoke(p, agent);
          var d =
              detail(p, manual, agent.equals("daily-weather") ? "cli" : "http", tools(scenario));
          assertEquals("SUCCEEDED", d.path("status").asText());
          assertFalse(d.path("hasToolErrors").asBoolean());
          assertTrue(sessionIds.add(d.path("sessionId").asText()));
          int count = f.sources.get(source(agent)).get();
          int receipts = f.notifications.size();
          var auto = detail(p, scheduled(p, agent), "scheduler", tools(scenario));
          assertTrue(sessionIds.add(auto.path("sessionId").asText()));
          assertEquals(
              scenario.path("sourceRequests").asInt(), f.sources.get(source(agent)).get() - count);
          assertEquals(receipts + 1, f.notifications.size());
          assertEquals("SUCCEEDED", auto.path("status").asText());
        }
        assertEquals(6, sessionIds.size());
        assertEquals(6, f.notifications.size());
        assertEquals(2, f.sources.get("weather").get());
        assertEquals(2, f.sources.get("news").get());
        assertEquals(6, f.sources.get("github").get());
        var queries =
            f.sourceQueries.stream()
                .filter(q -> q.startsWith("/github/"))
                .map(q -> java.net.URLDecoder.decode(q, java.nio.charset.StandardCharsets.UTF_8))
                .toList();
        assertTrue(
            queries.stream()
                .allMatch(
                    q ->
                        q.contains("sort=stars")
                            && q.contains("order=desc")
                            && q.contains("per_page=3")));
        assertTrue(
            queries.stream()
                .anyMatch(
                    q ->
                        q.contains(
                            "created:" + KernelFixture.today() + ".." + KernelFixture.today())));
        assertTrue(
            queries.stream()
                .anyMatch(
                    q ->
                        q.contains(
                            "created:"
                                + LocalDate.parse(KernelFixture.today()).withDayOfMonth(1)
                                + ".."
                                + KernelFixture.today())));
        assertTrue(
            queries.stream().anyMatch(q -> q.contains("topic:artificial-intelligence pushed:")));
        assertTrue(
            f.notifications.stream()
                .filter(t -> t.contains("日报"))
                .allMatch(t -> t.contains(KernelFixture.PREFERENCE)));
        assertTrue(f.notifications.stream().anyMatch(t -> t.contains("12°C") && t.contains("雨具")));
        var mcp = Files.readAllLines(root.resolve("mcp-calls.jsonl"));
        assertEquals(2, mcp.size());
        for (var line : mcp) {
          var call = JSON.readTree(line);
          assertNotEquals(p.pid(), call.path("pid").asLong());
          assertEquals(KernelFixture.PREFERENCE, call.path("arguments").path("query").asText());
          assertFalse(call.path("providerSecretInherited").asBoolean());
        }
        evidence(
            "kernel-six-successes",
            Map.of(
                "notifications",
                f.notifications,
                "sourceCounts",
                Map.of("weather", 2, "news", 2, "github", 6),
                "mcpCalls",
                mcp,
                "sessions",
                sessionIds));
      }
      f.assertHealthy();
    }
  }

  @Test
  void sourceFailuresAcrossThreeAgentsStayVisibleWithoutNotifications() throws Exception {
    try (var f = new KernelFixture(root)) {
      preference();
      try (var p = new RuntimeProcess(root, "serve")) {
        for (var scenario : scenarios()) {
          String agent = scenario.path("name").asText();
          f.sourceFailure = source(agent);
          var names = new ArrayList<>(tools(scenario));
          names.removeLast();
          var data = detail(p, invoke(p, agent), "http", names);
          assertEquals("PARTIAL", data.path("status").asText());
          assertTrue(data.path("hasUnresolvedToolErrors").asBoolean());
          assertEquals(
              "ERROR", data.path("toolAttempts").get(names.size() - 1).path("outcome").asText());
          assertTrue(data.path("content").asText().contains("失败"));
          assertEquals(1, f.sources.get(source(agent)).get());
        }
        assertTrue(f.notifications.isEmpty());
        evidence("kernel-source-failures", Map.of("notifications", 0, "eachSourceRequests", 1));
      }
      f.assertHealthy();
    }
  }

  @Test
  void rejectedAndUnknownNotificationsNeverReplayOrClaimSuccess() throws Exception {
    try (var f = new KernelFixture(root)) {
      preference();
      try (var p = new RuntimeProcess(root, "serve")) {
        for (String mode : List.of("reject", "unknown")) {
          f.notificationMode = mode;
          for (var scenario : scenarios()) {
            int receipts = f.notifications.size();
            String agent = scenario.path("name").asText();
            var data = detail(p, invoke(p, agent), "http", tools(scenario));
            assertEquals("PARTIAL", data.path("status").asText());
            assertTrue(data.path("hasUnresolvedToolErrors").asBoolean());
            var attempts = data.path("toolAttempts");
            var last = attempts.get(attempts.size() - 1);
            assertEquals(
                mode.equals("reject") ? "ERROR" : "UNKNOWN", last.path("outcome").asText());
            if (mode.equals("unknown")) {
              assertEquals("SIDE_EFFECT_UNCERTAIN", data.path("stopReason").asText());
              assertTrue(data.path("content").asText().contains("核查"));
              assertEquals(
                  tools(scenario).size(),
                  data.path("llmCalls").size(),
                  "No model call after UNKNOWN");
            }
            assertEquals(receipts + 1, f.notifications.size());
            p.data("GET", "/executions/" + data.path("id").asText(), null, 200);
            assertEquals(receipts + 1, f.notifications.size(), "Queries never replay notification");
          }
        }
        evidence(
            "kernel-notification-failures",
            Map.of("requests", f.notifications.size(), "rejected", 3, "unknown", 3, "replays", 0));
      }
      f.assertHealthy();
    }
  }

  @Test
  void missingReferenceEmptyNewsAndIncompleteGithubNeverFabricateReports() throws Exception {
    try (var f = new KernelFixture(root)) {
      preference();
      try (var p = new RuntimeProcess(root, "serve")) {
        var reference = root.resolve("agents/daily-tech-digest/skills/digest-format.md");
        String text = Files.readString(reference);
        Files.delete(reference);
        var missing = detail(p, invoke(p, "daily-tech-digest"), "http", List.of("read_file"));
        assertEquals("PARTIAL", missing.path("status").asText());
        assertEquals(0, f.sources.get("news").get());
        Files.writeString(reference, text);
        f.emptyNews = true;
        var empty =
            detail(
                p, invoke(p, "daily-tech-digest"), "http", List.of("read_file", "mcp.news.search"));
        assertTrue(empty.path("content").asText().contains("无可核查新闻"));
        f.incompleteGithub = true;
        var incomplete = detail(p, invoke(p, "github-daily"), "http", List.of("shell"));
        assertEquals("PARTIAL", incomplete.path("status").asText());
        assertEquals(
            "PROCESS_EXIT_NONZERO",
            incomplete.path("toolAttempts").get(0).path("errorCode").asText());
        assertTrue(f.notifications.isEmpty());
      }
      f.assertHealthy();
    }
  }

  @Test
  void deniedHttpAndScriptHaveNoExternalOrProcessSideEffects() throws Exception {
    try (var f = new KernelFixture(root)) {
      preference();
      var weather = root.resolve("agents/daily-weather/AGENT.md");
      // Same live listener, but an unlisted host spelling. A forbidden request would be counted.
      Files.writeString(weather, Files.readString(weather).replace("127.0.0.1", "localhost"));
      var runtime = root.resolve("runtime.yaml");
      Files.writeString(
          runtime,
          Files.readString(runtime)
              .replace(
                  "allowed_script_paths: [agents/github-daily/scripts]",
                  "allowed_script_paths: []"));
      var script = root.resolve("agents/github-daily/scripts/github_trending.py");
      Files.writeString(script, "from pathlib import Path\nPath('SCRIPT_EXECUTED').touch()\n");
      try (var p = new RuntimeProcess(root, "serve")) {
        for (String agent : List.of("daily-weather", "github-daily")) {
          var d =
              detail(
                  p,
                  invoke(p, agent),
                  "http",
                  List.of(agent.equals("daily-weather") ? "http_get" : "shell"));
          assertEquals("PARTIAL", d.path("status").asText());
          assertEquals("DENIED", d.path("toolAttempts").get(0).path("outcome").asText());
        }
        assertEquals(0, f.sources.get("weather").get());
        assertEquals(0, f.sources.get("github").get());
        assertFalse(Files.exists(root.resolve("SCRIPT_EXECUTED")));
        assertTrue(f.notifications.isEmpty());
      }
      f.assertHealthy();
    }
  }

  @Test
  void actualScriptFailureAndAgentBodyReloadUseTheSameRuntime() throws Exception {
    try (var f = new KernelFixture(root)) {
      preference();
      try (var p = new RuntimeProcess(root, "serve")) {
        var script = root.resolve("agents/github-daily/scripts/github_trending.py");
        String original = Files.readString(script);
        Files.writeString(
            script,
            "import sys\nprint('LOCAL deliberate script failure', file=sys.stderr)\nsys.exit(7)\n");
        var failed = detail(p, invoke(p, "github-daily"), "http", List.of("shell"));
        assertEquals("PARTIAL", failed.path("status").asText());
        assertTrue(
            failed.path("toolAttempts").get(0).path("safeResult").asText().contains("exitCode=7"));
        assertTrue(f.notifications.isEmpty());
        assertEquals(0, f.sources.get("github").get());
        Files.writeString(script, original);
        detail(p, invoke(p, "github-daily"), "http", List.of("shell", "notify"));
        var body = root.resolve("agents/github-daily/AGENT.md");
        Files.writeString(body, Files.readString(body) + "\n本次加注：版本二\n");
        detail(p, invoke(p, "github-daily"), "http", List.of("shell", "notify"));
        assertEquals(2, f.notifications.size());
        assertFalse(f.notifications.get(0).contains("版本二"));
        assertTrue(f.notifications.get(1).contains("版本二"));
        assertEquals(6, f.sources.get("github").get());
      }
      f.assertHealthy();
    }
  }

  @Test
  void memoryWriteFailureCannotClaimThePreferenceWasSaved() throws Exception {
    try (var f = new KernelFixture(root)) {
      f.failMemorySave = true;
      try (var p = new RuntimeProcess(root, "serve")) {
        var data =
            p.data(
                "POST",
                "/agents/daily-tech-digest/invoke",
                Map.of("content", "请记住：" + KernelFixture.PREFERENCE),
                200);
        var d = detail(p, data, "http", List.of("save_memory"));
        assertEquals("PARTIAL", d.path("status").asText());
        assertEquals(
            "MEMORY_WRITE_FAILED", d.path("toolAttempts").get(0).path("errorCode").asText());
        assertTrue(d.path("content").asText().contains("保存失败，未声称记住"));
        assertTrue(p.data("GET", "/memory", null, 200).path("core").isEmpty());
        assertTrue(f.notifications.isEmpty());
      } finally {
        Files.setPosixFilePermissions(
            root.resolve("memory"),
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
      }
      f.assertHealthy();
    }
  }

  @Test
  void trustedScriptRefusesToForwardCredentialToAlternateSource() throws Exception {
    try (var f = new KernelFixture(root)) {
      preference();
      var config = root.resolve("runtime.yaml");
      Files.writeString(
          config,
          Files.readString(config)
              .replace("    env: {}", "    env: {GITHUB_TOKEN: '${SCRIPT_TOKEN}'}"));
      Files.writeString(
          root.resolve("secrets.local.yaml"),
          "SCRIPT_TOKEN: SCRIPT_SECRET_CANARY\n",
          StandardOpenOption.APPEND);
      try (var p = new RuntimeProcess(root, "serve")) {
        var d = detail(p, invoke(p, "github-daily"), "http", List.of("shell"));
        assertEquals("PARTIAL", d.path("status").asText());
        assertEquals(
            "PROCESS_EXIT_NONZERO", d.path("toolAttempts").get(0).path("errorCode").asText());
        assertFalse(d.toString().contains("SCRIPT_SECRET_CANARY"));
        assertEquals(0, f.sources.get("github").get());
        assertTrue(f.notifications.isEmpty());
      }
      f.assertHealthy();
    }
  }
}
