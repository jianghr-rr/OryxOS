package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.oryxlabs.oryxos.boot.runtime.BootRuntimeLauncher;
import io.github.oryxlabs.oryxos.boot.support.EarlyRuntimeConfiguration;
import io.github.oryxlabs.oryxos.core.execution.Execution;
import io.github.oryxlabs.oryxos.core.model.*;
import io.github.oryxlabs.oryxos.core.provider.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

@Tag("us4-tools")
class ToolIntegrationTest {
  @TempDir Path root;
  static final List<ProviderRequest> REQUESTS = new CopyOnWriteArrayList<>();
  static volatile List<ToolCall> CALLS = List.of();

  @TestConfiguration(proxyBeanMethods = false)
  static class ScriptedModel {
    @Bean
    @Primary
    ProviderPort toolsFixtureProvider() {
      return request -> {
        REQUESTS.add(request);
        var users =
            request.orderedMessages().stream()
                .filter(m -> m.role() == ChatMessage.Role.USER)
                .toList();
        var calls =
            request.iteration() == 1 && !users.getLast().content().equals("healthy")
                ? CALLS
                : List.<ToolCall>of();
        return new ProviderReply(
            new ChatMessage(
                ChatMessage.Role.ASSISTANT,
                calls.isEmpty() ? "tools done" : null,
                calls,
                null,
                null,
                null),
            calls.isEmpty() ? "STOP" : "TOOL_CALLS",
            null,
            "fixture",
            "model");
      };
    }
  }

  static final class Receiver implements AutoCloseable {
    final HttpServer server;
    final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    volatile boolean drop;
    volatile String notifyBody;

    Receiver() throws Exception {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/",
          e -> {
            String path = e.getRequestURI().getPath();
            calls.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (path.equals("/notify")) {
              notifyBody = body;
              if (drop) {
                e.close();
                return;
              }
            }
            byte[] response =
                (path.equals("/get") ? "known HTTP fact" : "{\"errcode\":0}")
                    .getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(200, response.length);
            e.getResponseBody().write(response);
            e.close();
          });
      server.start();
    }

    String url(String path) {
      return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    int count(String path) {
      return calls.getOrDefault(path, new AtomicInteger()).get();
    }

    public void close() {
      server.stop(0);
    }
  }

  void setup(Receiver receiver, boolean allowed, boolean mcp) throws Exception {
    REQUESTS.clear();
    var workspace = new WorkspaceHandle(root);
    new WorkspaceInitializer()
        .initialize(
            workspace,
            Map.of(
                "runtime.yaml",
                "schema_version: 1\n"
                    + "providers:\n"
                    + "  local:\n"
                    + "    protocol: openai_chat_completions\n"
                    + "    endpoint: http://127.0.0.1:1\n"
                    + "    completions_path: /chat/completions\n"
                    + "    api_key: '${LOCAL_KEY}'\n"
                    + "tools:\n"
                    + "  file:\n"
                    + "    allowed_read_paths: [output, agents/demo/skills]\n"
                    + "    allowed_write_paths: [output]\n"
                    + "  shell:\n"
                    + "    allowed_commands: [/usr/bin/python3]\n"
                    + "    allowed_script_paths: [agents/demo/scripts]\n"
                    + "    timeout_seconds: 2\n"
                    + "  http:\n"
                    + "    allowed_targets: [{scheme: http, host: 127.0.0.1, port: "
                    + receiver.server.getAddress().getPort()
                    + "}]\n    read_timeout_seconds: 1\n"));
    Files.writeString(
        root.resolve("secrets.local.yaml"),
        "LOCAL_KEY: fixture-local-key\nWEBHOOK_URL: '" + receiver.url("/notify") + "'\n");
    Files.setPosixFilePermissions(
        root.resolve("secrets.local.yaml"),
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    Files.createDirectories(root.resolve("agents/demo/scripts"));
    Files.createDirectories(root.resolve("agents/demo/skills"));
    Files.writeString(
        root.resolve("agents/demo/scripts/probe.py"), "print('actual trusted script output')\n");
    Files.writeString(root.resolve("agents/demo/skills/reference.md"), "reference information");
    String toolNames =
        allowed
            ? "read_file, write_file, list_dir, shell, http_get, http_post, save_memory,"
                + " recall_memory, notify, echo"
                + (mcp ? ", mcp.first.echo" : "")
            : "";
    Files.writeString(
        root.resolve("agents/demo/AGENT.md"),
        "---\nschema_version: 1\nname: demo\nprovider: {name: local, model: model}\ntools: ["
            + toolNames
            + "]\nmcp_servers: ["
            + (mcp ? "first" : "")
            + "]\n"
            + "notify_channels:\n"
            + "  daily:\n"
            + "    type: webhook\n"
            + "    url: '${WEBHOOK_URL}'\n"
            + "    payload: {text: '$CONTENT'}\n"
            + "    acknowledgement: {mode: json_equals, pointer: /errcode, expected: 0}\n"
            + "---\n"
            + "Use tools only as explicitly requested.\n");
    if (mcp) {
      Path source =
          Path.of(System.getProperty("oryxos.test.evidenceDir"))
              .getParent()
              .getParent()
              .resolve("oryxos-tool/src/test/resources/mcp/fixture-server.py");
      Files.copy(source, root.resolve("fixture.py"));
      Files.writeString(
          root.resolve("mcp_servers.yaml"),
          "schema_version: 1\n"
              + "servers:\n"
              + "  first:\n"
              + "    transport: stdio\n"
              + "    command: /usr/bin/python3\n"
              + "    args: ['"
              + root.resolve("fixture.py")
              + "', normal, '"
              + root.resolve("mcp-calls.jsonl")
              + "']\n    initialization_timeout_seconds: 3\n    request_timeout_seconds: 2\n");
    }
  }

  ToolCall call(String name, Map<String, Object> args) throws Exception {
    return new ToolCall(
        "call-" + name,
        name,
        ToolNameMap.wireName(name),
        new ObjectMapper().writeValueAsString(args));
  }

  List<ToolCall> nine(Receiver r) throws Exception {
    return List.of(
        call("write_file", Map.of("path", "output/fact.txt", "content", "saved fact")),
        call("read_file", Map.of("path", "agents/demo/skills/reference.md")),
        call("list_dir", Map.of("path", "output")),
        call(
            "shell",
            Map.of(
                "command",
                "/usr/bin/python3",
                "args",
                List.of(root.resolve("agents/demo/scripts/probe.py").toString()))),
        call("http_get", Map.of("url", r.url("/get"))),
        call("http_post", Map.of("url", r.url("/post"), "jsonBody", Map.of("value", 1))),
        call("save_memory", Map.of("content", "local shared fact")),
        call("recall_memory", Map.of("query", "shared")),
        call("notify", Map.of("content", "local notification probe", "targetName", "daily")));
  }

  String cli(String input) {
    return cli(input, 0);
  }

  String cli(String input, int expectedCode) {
    var output = new StringWriter();
    var writer = new PrintWriter(output, true);
    var launcher =
        new BootRuntimeLauncher(
            Map.of("spring.profiles.active", "us2-local", "spring.main.banner-mode", "off"),
            EarlyRuntimeConfiguration.class,
            ScriptedModel.class);
    int code =
        OryxOsApplication.execute(
            new String[] {
              "--root", root.toString(), "chat", "--profile", "demo", "--user", "alice"
            },
            launcher,
            new StringReader(input),
            writer,
            writer);
    assertEquals(expectedCode, code, output.toString());
    return output.toString();
  }

  @Test
  void cliRunsNineToolsNativeExtensionAndRealStdioThroughOneExecutor() throws Exception {
    try (var receiver = new Receiver()) {
      setup(receiver, true, true);
      var all = new ArrayList<>(nine(receiver));
      all.add(call("echo", Map.of("text", "native result")));
      all.add(call("mcp.first.echo", Map.of("text", "stdio result")));
      CALLS = List.copyOf(all);
      assertTrue(cli("run\n/quit\n").contains("tools done"));
      assertEquals(2, REQUESTS.size());
      assertEquals(
          11,
          EarlyRuntimeConfiguration.lastAudit.tools.stream()
              .filter(t -> t.status().equals("SUCCESS"))
              .count());
      assertEquals("saved fact", Files.readString(root.resolve("output/fact.txt")));
      assertEquals(1, receiver.count("/get"));
      assertEquals(1, receiver.count("/post"));
      assertEquals(1, receiver.count("/notify"));
      assertTrue(receiver.notifyBody.contains("local notification probe"));
      assertEquals(
          1,
          Files.readAllLines(root.resolve("mcp-calls.jsonl")).stream()
              .filter(s -> s.contains("tools/call"))
              .count());
      assertTrue(
          REQUESTS.get(1).orderedMessages().stream()
              .anyMatch(m ->
                  m.role() == ChatMessage.Role.TOOL
                      && m.content().contains("actual trusted script output")));
      assertEquals(
          Execution.Status.SUCCEEDED,
          EarlyRuntimeConfiguration.lastExecutions.all().getFirst().status());
    }
  }

  @Test
  void allNineToolsDenyUnlistedAgentBeforeAnyExternalAction() throws Exception {
    try (var receiver = new Receiver()) {
      setup(receiver, false, false);
      CALLS = nine(receiver);
      cli("attempt\n/quit\n", 5);
      assertEquals(
          9,
          EarlyRuntimeConfiguration.lastAudit.tools.stream()
              .filter(t -> t.status().equals("DENIED"))
              .count());
      assertEquals(0, receiver.count("/get") + receiver.count("/post") + receiver.count("/notify"));
      assertFalse(Files.exists(root.resolve("output/fact.txt")));
      assertFalse(Files.exists(root.resolve("memory/MEMORY.md")));
    }
  }

  @Test
  void notificationReceiptLossStopsSameTaskAndLeavesLaterTaskUsable() throws Exception {
    try (var receiver = new Receiver()) {
      setup(receiver, true, false);
      receiver.drop = true;
      CALLS =
          List.of(
              call("notify", Map.of("content", "uncertain receipt")),
              call(
                  "write_file",
                  Map.of("path", "output/must-not-exist", "content", "forbidden continuation")));
      cli("uncertain\nhealthy\n/quit\n");
      assertEquals(1, receiver.count("/notify"));
      assertEquals(
          2,
          REQUESTS.size(),
          "One model request for uncertain task; one independent healthy task, no retry-model"
              + " request");
      assertFalse(Files.exists(root.resolve("output/must-not-exist")));
      assertTrue(
          EarlyRuntimeConfiguration.lastAudit.tools.stream()
              .anyMatch(
                  t -> t.toolName().equals("write_file") && t.status().equals("NOT_EXECUTED")));
      assertTrue(
          EarlyRuntimeConfiguration.lastExecutions.all().stream()
              .anyMatch(e -> e.stopReason().equals("SIDE_EFFECT_UNCERTAIN")));
      assertTrue(
          EarlyRuntimeConfiguration.lastExecutions.all().stream()
              .anyMatch(e -> e.status() == Execution.Status.SUCCEEDED));
    }
  }

  @Test
  void unavailableToolOrServerOnlyDisablesItsReferencingAgent() throws Exception {
    try (var receiver = new Receiver()) {
      setup(receiver, true, false);
      String healthy = Files.readString(root.resolve("agents/demo/AGENT.md"));
      for (String name : List.of("bad-tool", "bad-server")) {
        Files.createDirectories(root.resolve("agents/" + name));
        String definition = healthy.replace("name: demo", "name: " + name);
        definition =
            name.equals("bad-tool")
                ? definition.replace("tools: [", "tools: [missing_tool, ")
                : definition.replace("mcp_servers: []", "mcp_servers: [absent]");
        Files.writeString(root.resolve("agents/" + name + "/AGENT.md"), definition);
      }
      try (var context =
          new BootRuntimeLauncher(Map.of("spring.main.banner-mode", "off"))
              .start(
                  new WorkspaceHandle(root), Map.of("spring.main.web-application-type", "none"))) {
        var catalog =
            context
                .getBean(
                    io.github.oryxlabs.oryxos.boot.config.ToolConfiguration.CheckedAgents.class)
                .catalog();
        assertEquals("demo", catalog.require("demo").name());
        for (String name : List.of("bad-tool", "bad-server"))
          assertThrows(
              io.github.oryxlabs.oryxos.core.error.OryxError.class, () -> catalog.require(name));
        var registry = context.getBean(io.github.oryxlabs.oryxos.core.tool.ToolRegistryPort.class);
        for (String name : List.of("read_file", "list_dir", "http_get", "recall_memory", "echo"))
          assertTrue(registry.find(name).descriptor().safeToRetry());
        for (String name : List.of("write_file", "shell", "http_post", "save_memory", "notify"))
          assertFalse(registry.find(name).descriptor().safeToRetry());
        assertEquals(
            0, receiver.count("/notify") + receiver.count("/post") + receiver.count("/get"));
      }
    }
  }
}
