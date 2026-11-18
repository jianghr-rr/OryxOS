package io.github.oryxlabs.oryxos.tool;

import io.github.oryxlabs.oryxos.core.security.*;
import io.github.oryxlabs.oryxos.tool.mcp.*;
import io.github.oryxlabs.oryxos.tool.shell.*;
import io.github.oryxlabs.oryxos.tool.support.ToolFixture;
import io.github.oryxlabs.oryxos.tool.validation.ToolArgumentValidator;
import java.nio.file.*;
import java.util.*;

/** Separate JVM with a deliberately contaminated parent environment; no real credentials. */
public final class EnvironmentBoundaryProbe {
  public static void main(String[] args) throws Exception {
    if (!"fixture-parent-canary".equals(System.getenv("ORYXOS_PARENT_CANARY")))
      throw new AssertionError("Probe parent was not contaminated");
    try (var f = new ToolFixture(Path.of(args[0]))) {
      Path script = f.workspace.root().resolve("script.py"),
          mcp = f.workspace.root().resolve("mcp.py");
      Files.copy(
          EnvironmentBoundaryProbe.class.getResourceAsStream("/scripts/controlled-output.py"),
          script);
      Files.copy(EnvironmentBoundaryProbe.class.getResourceAsStream("/mcp/fixture-server.py"), mcp);
      var ctx = f.context("probe");
      var shell =
          new ShellTool(
              new ProcessPolicy(
                  f.workspace.root(),
                  Map.of(
                      "allowed_commands",
                      List.of("/usr/bin/python3"),
                      "allowed_script_paths",
                      List.of("script.py")),
                  new SecretResolver(
                      Map.of(), f.workspace.root().resolve("none"), ctx.redactor())));
      var result =
          shell.execute(
              Map.of("command", "/usr/bin/python3", "args", List.of(script.toString())), ctx);
      if (!result.content().contains("\"foreign\": null")
          || !result.content().contains("\"provider\": null"))
        throw new AssertionError("Shell inherited a foreign credential");
      var definition =
          new McpClientManager.Definition(
              "probe",
              "/usr/bin/python3",
              List.of(
                  mcp.toString(), "normal", f.workspace.root().resolve("calls.jsonl").toString()),
              Map.of(),
              2,
              1);
      try (var manager =
          new McpClientManager(
              f.workspace.root(),
              List.of(definition),
              new Redactor(),
              new ToolArgumentValidator())) {
        if (!manager.available("probe")) throw new AssertionError("MCP did not initialize");
        manager.call("probe", "echo", Map.of("text", "probe"), ctx);
      }
      String log = Files.readString(f.workspace.root().resolve("calls.jsonl"));
      if (!log.contains("\"foreign\": null") || !log.contains("\"provider\": null"))
        throw new AssertionError("MCP inherited a foreign credential");
      System.out.println("ENVIRONMENT_BOUNDARY_PASS");
    }
  }
}
