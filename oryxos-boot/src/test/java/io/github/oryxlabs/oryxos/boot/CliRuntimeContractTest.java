package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.PersistentFixture;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us5-cli")
class CliRuntimeContractTest {
  @TempDir Path root;
  StringWriter output;
  final ObjectMapper json = new ObjectMapper();

  int run(String input, String... args) {
    output = new StringWriter();
    var all = new ArrayList<>(List.of("--root", root.toString()));
    all.addAll(List.of(args));
    return OryxOsApplication.execute(
        all.toArray(String[]::new),
        new BootRuntimeLauncher(),
        new StringReader(input),
        new PrintWriter(output),
        new PrintWriter(output));
  }

  JsonNode data() throws Exception {
    return json.readTree(output.toString()).path("data");
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void offlineToolsNeverDiscoverMcpAndMissingDatabaseIsNotAnEmptyList() throws Exception {
    try (var f = new PersistentFixture(root)) {
      assertEquals(0, run("", "tool", "list", "--json"), output.toString());
      assertEquals("NOT_DISCOVERED", data().path("mcpDiscovery").asText());
      assertEquals(10, data().path("items").size());
      assertEquals(0, f.models.get());
      assertFalse(Files.exists(f.workspace.database()));
      assertEquals(3, run("", "session", "list", "--json"), output.toString());
      assertFalse(Files.exists(f.workspace.database()));
      Files.writeString(f.workspace.database(), "corrupt bytes");
      assertEquals(3, run("", "session", "list", "--json"));
      assertEquals("corrupt bytes", Files.readString(f.workspace.database()));
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void liveToolsAndProfileSnapshotsFollowLoadedDefinitionsThenRefreshAfterRestart()
      throws Exception {
    try (var f = new PersistentFixture(root)) {
      Path agent = root.resolve("agents/local/AGENT.md");
      try (var c = f.start()) {
        assertEquals(0, run("", "tool", "list", "--profile", "local", "--json"), output.toString());
        assertEquals("RUNNING_REGISTRY_SNAPSHOT", data().path("source").asText());
        assertEquals(1, data().path("items").size());
        assertEquals("http_post", data().path("items").get(0).path("name").asText());
        String changed = Files.readString(agent).replace("tools: [http_post]", "tools: []");
        assertNotEquals(Files.readString(agent), changed);
        Files.writeString(agent, changed);
        assertEquals(0, run("", "profile", "show", "local", "--json"), output.toString());
        assertEquals("RESTART_REQUIRED", data().path("snapshotComparison").asText());
        assertFalse(output.toString().contains("LOCAL_SECRET_CANARY"));
        assertEquals(4, run("", "profile", "delete", "local"));
        assertEquals(4, run("hello\n", "chat", "--profile", "local", "--user", "u", "--json"));
      }
      try (var c = f.start()) {
        assertEquals(0, run("", "tool", "list", "--profile", "local", "--json"));
        assertEquals(0, data().path("items").size());
        assertEquals(0, run("", "profile", "show", "local", "--json"));
        assertEquals("MATCH", data().path("snapshotComparison").asText());
      }
      assertEquals(0, run("", "profile", "show", "local", "--json"));
      assertEquals("NOT_RUNNING", data().path("snapshotComparison").asText());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void jsonChatAndReadonlyPaginationKeepHttpIdentityAndHistoricalRowsAfterDelete()
      throws Exception {
    try (var f = new PersistentFixture(root)) {
      for (int i = 0; i < 2; i++) {
        assertEquals(
            0,
            run("hello\n/quit\n", "chat", "--profile", "local", "--user", "cli-owner", "--json"),
            output.toString());
        var e = data();
        assertEquals("SUCCEEDED", e.path("status").asText());
        assertEquals("COMPLETE", e.path("recordingStatus").asText());
        assertTrue(e.path("content").asText().startsWith("reply-"));
        assertEquals("cli", e.path("identity").path("channel").asText());
        assertFalse(e.path("hasToolErrors").asBoolean());
        assertFalse(output.toString().contains("PRIVATE_OPAQUE_CANARY"));
      }
      assertEquals(0, run("", "session", "list", "--limit", "1", "--json"));
      String cursor = data().path("nextCursor").asText();
      assertFalse(cursor.isBlank());
      assertEquals(1, data().path("items").size());
      String id = data().path("items").get(0).path("id").asText();
      assertEquals(0, run("", "session", "list", "--limit", "1", "--cursor", cursor, "--json"));
      assertNotEquals(id, data().path("items").get(0).path("id").asText());
      assertTrue(data().path("nextCursor").isNull());
      assertEquals(0, run("", "profile", "delete", "local", "--json"));
      assertEquals(0, run("", "session", "list", "--profile", "local", "--json"));
      assertEquals(2, data().path("items").size());
      assertEquals(2, f.models.get());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void failureAndPauseExitCodesRemainStructured() throws Exception {
    try (var f = new PersistentFixture(root)) {
      f.modelStatus = 500;
      assertEquals(
          5,
          run("hello\n/quit\n", "chat", "--profile", "local", "--user", "u", "--json"),
          output.toString());
      assertEquals("FAILED", data().path("status").asText());
      Files.createDirectories(root.resolve("runtime/runs"));
      Files.writeString(root.resolve("runtime/runs/abandoned.json"), "{}");
      assertEquals(
          7, run("", "chat", "--profile", "local", "--user", "u", "--json"), output.toString());
      assertEquals("EXECUTION_PAUSED", json.readTree(output.toString()).path("code").asText());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void actualProcessJsonOutputIsSeparateFromBootLogsAndLightQueriesStayOffline() throws Exception {
    try (var f = new PersistentFixture(root)) {
      var result = process("hello\n/quit\n", "chat", "--profile", "local", "--user", "u", "--json");
      assertEquals(0, result.code(), result.error());
      assertEquals(1, result.output().lines().count());
      assertEquals(
          "SUCCEEDED", json.readTree(result.output()).path("data").path("status").asText());
      assertFalse(result.output().contains("Starting OryxOsApplication"));
      result = process("", "tool", "list", "--json");
      assertEquals(0, result.code(), result.error());
      assertEquals(
          "NOT_DISCOVERED",
          json.readTree(result.output()).path("data").path("mcpDiscovery").asText());
      assertFalse(result.error().contains("Starting OryxOsApplication"));
      assertEquals(1, f.models.get());
    }
  }

  record ProcessResult(int code, String output, String error) {}

  ProcessResult process(String input, String... args) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + System.getProperty("user.home"),
                "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"),
                "-cp",
                System.getProperty("java.class.path"),
                OryxOsApplication.class.getName(),
                "--root",
                root.toString()));
    command.addAll(List.of(args));
    Path stdout = root.resolve("cli.stdout"), stderr = root.resolve("cli.stderr");
    var process =
        new ProcessBuilder(command)
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();
    try {
      try (var in = process.getOutputStream()) {
        in.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      }
      assertTrue(
          process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
          "CLI process did not terminate");
      return new ProcessResult(
          process.exitValue(), Files.readString(stdout), Files.readString(stderr));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor();
      }
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void actualSixtySecondCliDeadlineReportsUnknownAndNeverDispatchesLateTool() throws Exception {
    try (var f = new PersistentFixture(root);
        var c = f.start();
        var worker = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var release = new java.util.concurrent.CountDownLatch(1);
      f.useTool = true;
      f.beforeModel =
          n -> {
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          };
      var service =
          c.getBean(
                  io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration.RuntimeAgents
                      .class)
              .require();
      var out = new StringWriter();
      long started = System.nanoTime();
      try {
        var task =
            worker.submit(
                () ->
                    new io.github.oryxlabs.oryxos.channel.cli.CliChannelAdapter(service)
                        .chat(
                            new io.github.oryxlabs.oryxos.core.runtime.ChatOptions(
                                "local", "u", null, true),
                            new StringReader("hello\n/quit\n"),
                            new PrintWriter(out)));
        assertEquals(6, task.get(65, java.util.concurrent.TimeUnit.SECONDS));
        long millis =
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(millis >= 59000 && millis < 65000, "wait ms=" + millis);
        var result = json.readTree(out.toString());
        assertEquals("EXECUTION_TIMEOUT", result.path("code").asText());
        assertEquals("TIMED_OUT", result.path("data").path("status").asText());
        assertFalse(result.path("data").path("id").asText().isBlank());
      } finally {
        release.countDown();
      }
      long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      while (service.activeCount() > 0 && System.nanoTime() < until) Thread.sleep(10);
      assertEquals(0, service.activeCount());
      assertEquals(1, f.models.get());
      assertEquals(0, f.receipts.get());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void actualRecordFailureIsExitSevenAndPublicOutputRetainsUncertaintyAndCorrectionFlags()
      throws Exception {
    try (var f = new PersistentFixture(root);
        var c = f.start()) {
      RecordingFailureIT.sql(
          c,
          "CREATE TRIGGER fail_intent BEFORE INSERT ON llm_calls BEGIN SELECT"
              + " RAISE(ABORT,'injected'); END");
      var service =
          c.getBean(
                  io.github.oryxlabs.oryxos.boot.config.AgentRuntimeConfiguration.RuntimeAgents
                      .class)
              .require();
      var out = new StringWriter();
      assertEquals(
          7,
          new io.github.oryxlabs.oryxos.channel.cli.CliChannelAdapter(service)
              .chat(
                  new io.github.oryxlabs.oryxos.core.runtime.ChatOptions("local", "u", null, true),
                  new StringReader("hello\n/quit\n"),
                  new PrintWriter(out)));
      assertEquals(
          "INCOMPLETE",
          json.readTree(out.toString()).path("data").path("recordingStatus").asText());
      assertEquals(0, f.models.get());
    }
    var service = org.mockito.Mockito.mock(io.github.oryxlabs.oryxos.core.agent.AgentService.class);
    var handle =
        org.mockito.Mockito.mock(io.github.oryxlabs.oryxos.core.agent.AgentService.Handle.class);
    org.mockito.Mockito.when(
            service.start(
                org.mockito.ArgumentMatchers.any(
                    io.github.oryxlabs.oryxos.core.execution.ProcessRequest.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(handle);
    var request =
        io.github.oryxlabs.oryxos.core.execution.ProcessRequest.manual(
            "cli", "u", "local", null, "hello");
    var execution =
        new io.github.oryxlabs.oryxos.core.execution.Execution(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            request,
            "rev",
            java.time.Instant.now(),
            java.time.Instant.now().plusSeconds(60),
            java.time.ZoneId.of("Asia/Shanghai"),
            io.github.oryxlabs.oryxos.core.model.ToolNameMap.empty());
    execution.toolErrors(true, false);
    execution.finish(
        io.github.oryxlabs.oryxos.core.execution.Execution.Status.SUCCEEDED,
        "FINAL_RESPONSE",
        "done",
        null,
        java.time.Instant.now());
    execution.recordingPending();
    org.mockito.Mockito.when(handle.await()).thenReturn(execution.snapshot(0));
    org.mockito.Mockito.when(handle.waitExpired()).thenReturn(true);
    var out = new StringWriter();
    assertEquals(
        6,
        new io.github.oryxlabs.oryxos.channel.cli.CliChannelAdapter(service)
            .chat(
                new io.github.oryxlabs.oryxos.core.runtime.ChatOptions("local", "u", null),
                new StringReader("hello\n/quit\n"),
                new PrintWriter(out)));
    assertTrue(out.toString().contains("recording=UNKNOWN"));
    assertTrue(out.toString().contains("hasToolErrors=true"));
    assertTrue(out.toString().contains("hasUnresolvedToolErrors=false"));
    assertTrue(out.toString().contains("corrected tool errors"));
    execution.recordingComplete();
    var mapper =
        new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    var redactor = new io.github.oryxlabs.oryxos.core.security.Redactor();
    assertEquals(
        mapper.valueToTree(
            new io.github.oryxlabs.oryxos.web.api.PublicDtoMapper(redactor)
                .execution(execution.snapshot(0), null, null)),
        mapper.valueToTree(
            io.github.oryxlabs.oryxos.channel.cli.CliOutput.execution(
                execution.snapshot(0), redactor)));
    org.mockito.Mockito.when(handle.await()).thenReturn(execution.snapshot(0));
    org.mockito.Mockito.when(handle.waitExpired()).thenReturn(false);
    out = new StringWriter();
    assertEquals(
        0,
        new io.github.oryxlabs.oryxos.channel.cli.CliChannelAdapter(service)
            .chat(
                new io.github.oryxlabs.oryxos.core.runtime.ChatOptions("local", "u", null, true),
                new StringReader("hello\n/quit\n"),
                new PrintWriter(out)));
    assertTrue(json.readTree(out.toString()).path("data").path("hasToolErrors").asBoolean());
    assertFalse(
        json.readTree(out.toString()).path("data").path("hasUnresolvedToolErrors").asBoolean());
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void mcpToolsComeFromActualRegistrationWithoutRediscoveryOrStaleSnapshots() throws Exception {
    var fixture = new ToolIntegrationTest();
    fixture.root = root;
    try (var receiver = new ToolIntegrationTest.Receiver()) {
      fixture.setup(receiver, true, true);
      assertEquals(0, run("", "tool", "list", "--profile", "demo", "--json"), output.toString());
      assertEquals(10, data().path("items").size());
      assertFalse(Files.exists(root.resolve("mcp-calls.jsonl")));
      var workspace = new io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle(root);
      try (var c =
          new BootRuntimeLauncher()
              .start(workspace, Map.of("spring.main.web-application-type", "none"))) {
        String calls = Files.readString(root.resolve("mcp-calls.jsonl"));
        assertEquals(0, run("", "tool", "list", "--profile", "demo", "--json"), output.toString());
        assertEquals(11, data().path("items").size());
        assertTrue(output.toString().contains("mcp.first.echo"));
        assertFalse(output.toString().contains("mcp.first.other"));
        assertEquals(
            calls,
            Files.readString(root.resolve("mcp-calls.jsonl")),
            "Light query must not launch or rediscover MCP");
        Path view = root.resolve("runtime/cli-view.json");
        String original = Files.readString(view);
        assertFalse(original.contains("fixture-local-key"));
        assertFalse(original.contains(receiver.url("/notify")));
        var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original);
        invalid.put("workspaceId", UUID.randomUUID().toString());
        Files.writeString(view, invalid.toString());
        assertEquals(
            3, run("", "tool", "list", "--json"), "Foreign runtime view must not be trusted");
        Files.writeString(view, original);
      }
      assertFalse(Files.exists(root.resolve("runtime/cli-view.json")));
      assertEquals(0, run("", "tool", "list", "--json"));
      assertEquals("NOT_DISCOVERED", data().path("mcpDiscovery").asText());
    }
  }

  @Test
  @org.junit.jupiter.api.Tag("us5-http")
  void callerDeadlineIncludesBlockedAdmissionAndFinalRecord() throws Exception {
    for (boolean admission : List.of(true, false)) {
      var entered = new java.util.concurrent.CountDownLatch(1);
      var release = new java.util.concurrent.CountDownLatch(1);
      var settled = new java.util.concurrent.CountDownLatch(1);
      var service =
          org.mockito.Mockito.mock(io.github.oryxlabs.oryxos.core.agent.AgentService.class);
      var handle =
          org.mockito.Mockito.mock(io.github.oryxlabs.oryxos.core.agent.AgentService.Handle.class);
      var execution =
          new io.github.oryxlabs.oryxos.core.execution.Execution(
              UUID.randomUUID(),
              UUID.randomUUID(),
              UUID.randomUUID(),
              io.github.oryxlabs.oryxos.core.execution.ProcessRequest.manual(
                  "cli", "u", "local", null, "hello"),
              "rev",
              java.time.Instant.now(),
              java.time.Instant.now().plusSeconds(60),
              java.time.ZoneId.of("Asia/Shanghai"),
              io.github.oryxlabs.oryxos.core.model.ToolNameMap.empty());
      if (!admission)
        execution.finish(
            io.github.oryxlabs.oryxos.core.execution.Execution.Status.SUCCEEDED,
            "FINAL_RESPONSE",
            "known result",
            null,
            java.time.Instant.now());
      execution.recordingPending();
      org.mockito.Mockito.when(handle.current()).thenReturn(execution.snapshot(0));
      org.mockito.Mockito.when(handle.waitExpired()).thenReturn(true);
      org.mockito.Mockito.when(
              service.start(
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.any(),
                  org.mockito.ArgumentMatchers.anyLong(),
                  org.mockito.ArgumentMatchers.any()))
          .thenAnswer(
              call -> {
                java.util.function.Consumer<
                        io.github.oryxlabs.oryxos.core.agent.AgentService.Handle>
                    observer = call.getArgument(5);
                observer.accept(handle);
                if (admission) {
                  entered.countDown();
                  assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                }
                return handle;
              });
      org.mockito.Mockito.when(handle.await())
          .thenAnswer(
              call -> {
                try {
                  if (!admission) {
                    entered.countDown();
                    assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                  }
                  return execution.snapshot(0);
                } finally {
                  settled.countDown();
                }
              });
      var time =
          new io.github.oryxlabs.oryxos.core.runtime.TimeSource() {
            int reads;

            public java.time.Instant instant() {
              return java.time.Instant.parse("2026-11-13T00:00:00Z");
            }

            public long nanoTime() {
              if (reads++ == 0) return 0;
              try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
              return java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            }
          };
      var out = new StringWriter();
      try {
        assertEquals(
            6,
            new io.github.oryxlabs.oryxos.channel.cli.CliChannelAdapter(
                    service, new io.github.oryxlabs.oryxos.core.security.Redactor(), time)
                .chat(
                    new io.github.oryxlabs.oryxos.core.runtime.ChatOptions(
                        "local", "u", null, true),
                    new StringReader("hello\n/quit\n"),
                    new PrintWriter(out)));
        var response = json.readTree(out.toString());
        assertEquals("EXECUTION_TIMEOUT", response.path("code").asText());
        assertEquals("UNKNOWN", response.path("data").path("recordingStatus").asText());
        assertEquals(
            admission ? "RUNNING" : "SUCCEEDED", response.path("data").path("status").asText());
        org.mockito.Mockito.verify(handle).expireIfDue();
      } finally {
        release.countDown();
        assertTrue(settled.await(5, java.util.concurrent.TimeUnit.SECONDS));
      }
    }
  }
}
