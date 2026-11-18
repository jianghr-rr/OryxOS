package io.github.oryxlabs.oryxos.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.boot.support.RealToolProbe;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import io.github.oryxlabs.oryxos.tool.mcp.*;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("real-integration")
@Tag("real-mcp")
class ConfiguredMcpIT {
  @Test
  void pinnedConfiguredServerDiscoversCallsOnceAndCloses() throws Exception {
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("kind", "REAL_CONFIGURED_STDIO_MCP");
    evidence.put("time", Instant.now().toString());
    evidence.put("sdkVersion", "0.18.3");
    evidence.put("status", "PREREQUISITES_MISSING");
    try {
      assertEquals("true", RealToolProbe.required("oryxos.real.mcp.confirmCall"));
      String server = RealToolProbe.required("oryxos.real.mcp.server");
      String remote = RealToolProbe.required("oryxos.real.mcp.tool");
      String version = RealToolProbe.required("oryxos.real.mcp.version");
      String expectedHash = RealToolProbe.required("oryxos.real.mcp.entrypointSha256");
      Path argumentsFile = Path.of(RealToolProbe.required("oryxos.real.mcp.argumentsFile"));
      assertTrue(argumentsFile.isAbsolute() && Files.size(argumentsFile) <= 65536);
      String arguments = Files.readString(argumentsFile);
      assertTrue(
          new ObjectMapper().readTree(arguments).isObject(),
          "Arguments file must contain a JSON object");
      try (var probe = new RealToolProbe()) {
        assertTrue(probe.agent.mcpServers().contains(server));
        String name = "mcp." + server + "." + remote;
        assertTrue(probe.agent.tools().contains(name));
        var definitions =
            McpClientManager.definitions(probe.workspace.root(), Set.of(server), probe.secrets());
        var definition = definitions.getFirst();
        Path entrypoint =
            definition.args().isEmpty()
                ? Path.of(definition.command())
                : Path.of(definition.args().getFirst());
        assertTrue(
            entrypoint.isAbsolute() && Files.isRegularFile(entrypoint),
            "Use a pinned absolute executable or a first absolute script argument; no package"
                + " runner/download");
        try (var input = Files.newInputStream(entrypoint)) {
          var digest = MessageDigest.getInstance("SHA-256");
          byte[] block = new byte[8192];
          int count;
          while ((count = input.read(block)) != -1) digest.update(block, 0, count);
          assertEquals(
              expectedHash, HexFormat.of().formatHex(digest.digest()), "Pinned entrypoint differs");
        }
        evidence.put("entrypointSha256", expectedHash);
        evidence.put(
            "environment",
            "PATH/HOME/TMPDIR plus explicit secret references; parent environment cleared");
        evidence.put("status", "STARTED");
        var manager =
            new McpClientManager(
                probe.workspace.root(), definitions, probe.redactor, new ToolArgumentValidator());
        try (manager) {
          assertTrue(
              manager.available(server), "Configured server failed initialization/discovery");
          assertEquals(version, manager.serverVersion(server), "Configured MCP version differs");
          evidence.put("serverVersion", version);
          evidence.put("discoveredToolCount", manager.tools(server).size());
          var result = probe.execute(new McpToolDiscovery().discover(manager), name, arguments);
          evidence.put("executionId", result.record().executionId().toString());
          evidence.put("toolCallId", result.call().id());
          evidence.put("outcome", result.result().outcome());
          evidence.put("attempts", result.result().attemptCount());
          assertEquals(
              ToolResult.Outcome.SUCCESS,
              result.result().outcome(),
              "Inspect external effects before rerunning an uncertain call");
          assertEquals(1, result.result().attemptCount());
        } finally {
          evidence.put("closeStates", manager.states());
        }
        assertTrue(
            manager.states().stream()
                .allMatch(s -> s.code().equals("CLOSED") && s.closeConfirmed()));
        evidence.put("status", "SUCCEEDED");
      }
    } finally {
      RealToolProbe.evidence("mcp", evidence);
    }
  }
}
