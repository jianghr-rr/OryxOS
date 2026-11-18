package io.github.oryxlabs.oryxos.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.tool.mcp.*;
import io.github.oryxlabs.oryxos.tool.support.ToolFixture;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("us4-tools")
class McpContractTest {
  @TempDir Path root;

  McpClientManager.Definition definition(String id, String mode) throws Exception {
    var script = root.resolve("fixture.py");
    if (!Files.exists(script))
      Files.copy(getClass().getResourceAsStream("/mcp/fixture-server.py"), script);
    return new McpClientManager.Definition(
        id,
        "/usr/bin/python3",
        List.of(script.toString(), mode, root.resolve(id + ".jsonl").toString()),
        Map.of("EXPLICIT_SECRET", "fixture-mcp-secret"),
        2,
        1);
  }

  @Test
  void actualStdioPaginatesSeparatesNamesAndCallsOnlyRemoteName() throws Exception {
    try (var f = new ToolFixture(root)) {
      var redactor = new Redactor();
      redactor.register("fixture-mcp-secret");
      var validator = new ToolArgumentValidator();
      var manager =
          new McpClientManager(
              root,
              List.of(definition("first", "normal"), definition("second", "normal")),
              redactor,
              validator);
      try (manager) {
        assertTrue(manager.available("first"), manager.states().toString());
        assertEquals(2, manager.tools("first").size());
        var tools = new McpToolDiscovery().discover(manager);
        assertEquals(4, tools.size());
        f.servers = List.of("first", "second");
        var ctx = f.context("caller");
        for (String id : List.of("first", "second")) {
          var tool =
              tools.stream()
                  .filter(t -> t.descriptor().name().equals("mcp." + id + ".echo"))
                  .findFirst()
                  .orElseThrow();
          tool.authorize(Map.of("text", "value"), ctx);
          assertEquals("echo:value", tool.execute(Map.of("text", "value"), ctx).content());
          String log = Files.readString(root.resolve(id + ".jsonl"));
          assertTrue(log.contains("\"name\": \"echo\""));
          assertFalse(log.contains("mcp."));
          assertTrue(log.contains("\"foreign\": null"));
          assertTrue(log.contains("\"provider\": null"));
        }
      }
      assertTrue(manager.states().stream().allMatch(s -> s.code().equals("CLOSED")));
      assertFalse(manager.safeStderr("first").contains("fixture-mcp-secret"));
    }
  }

  @Test
  void remoteSchemaIsRejectedOfflineAndDoesNotBreakOtherServer() throws Exception {
    var receiver =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var fetches = new java.util.concurrent.atomic.AtomicInteger();
    receiver.createContext(
        "/",
        e -> {
          fetches.incrementAndGet();
          e.sendResponseHeaders(200, -1);
          e.close();
        });
    receiver.start();
    try (var f = new ToolFixture(root)) {
      var original = definition("bad", "remote-ref");
      var args = new ArrayList<>(original.args());
      args.add("http://127.0.0.1:" + receiver.getAddress().getPort() + "/schema");
      var bad =
          new McpClientManager.Definition(
              "bad", original.command(), args, original.environment(), 2, 1);
      try (var manager =
          new McpClientManager(
              root,
              List.of(bad, definition("good", "normal"), definition("secret", "secret-name")),
              new Redactor(),
              new ToolArgumentValidator())) {
        assertFalse(manager.available("bad"));
        assertFalse(manager.available("secret"));
        assertTrue(manager.available("good"));
        assertTrue(manager.tools("bad").isEmpty());
        assertFalse(Files.readString(root.resolve("bad.jsonl")).contains("tools/call"));
        assertEquals(0, fetches.get());
      }
    } finally {
      receiver.stop(0);
    }
  }

  @Test
  void serverErrorIsErrorButDisconnectIsUnknownAndNeverReplayed() throws Exception {
    try (var f = new ToolFixture(root);
        var manager =
            new McpClientManager(
                root,
                List.of(definition("failed", "is-error"), definition("lost", "disconnect")),
                new Redactor(),
                new ToolArgumentValidator())) {
      var ctx = f.context("caller");
      assertEquals(
          ToolResult.Outcome.ERROR,
          manager.call("failed", "echo", Map.of("text", "x"), ctx).outcome());
      assertEquals(
          ToolResult.Outcome.UNKNOWN,
          manager.call("lost", "echo", Map.of("text", "x"), ctx).outcome());
      assertEquals(
          ToolResult.Outcome.ERROR,
          manager.call("lost", "echo", Map.of("text", "again"), ctx).outcome());
      assertEquals(
          1,
          Files.readAllLines(root.resolve("lost.jsonl")).stream()
              .filter(s -> s.contains("tools/call"))
              .count());
    }
  }

  @Test
  void remainingDeadlineBoundsCallAndPreventsFurtherWireRequests() throws Exception {
    try (var f = new ToolFixture(root);
        var manager =
            new McpClientManager(
                root,
                List.of(definition("slow", "slow")),
                new Redactor(),
                new ToolArgumentValidator())) {
      var c = f.context("caller");
      var deadline = new Deadline(c.time(), Duration.ofMillis(150));
      var permit = f.gate.execution(deadline);
      var ctx =
          new ExecutionContext(
              c.workspace(),
              c.agent(),
              c.execution(),
              deadline,
              c.gate(),
              permit,
              new ActionDispatcher(permit),
              c.time(),
              c.redactor());
      var result =
          assertTimeoutPreemptively(
              Duration.ofSeconds(4), () -> manager.call("slow", "echo", Map.of("text", "x"), ctx));
      assertEquals(ToolResult.Outcome.UNKNOWN, result.outcome());
      assertEquals(
          1,
          Files.readAllLines(root.resolve("slow.jsonl")).stream()
              .filter(s -> s.contains("tools/call"))
              .count());
    }
  }

  @Test
  void forcedShutdownIsReportedAsFailedGracefulClose() throws Exception {
    try (var f = new ToolFixture(root)) {
      var manager =
          new McpClientManager(
              root,
              List.of(definition("stubborn", "stubborn")),
              new Redactor(),
              new ToolArgumentValidator());
      assertTrue(manager.available("stubborn"));
      manager.close();
      assertEquals("MCP_CLOSE_FAILED", manager.states().getFirst().code());
      manager.close();
      assertEquals("MCP_CLOSE_FAILED", manager.states().getFirst().code());
    }
  }
}
