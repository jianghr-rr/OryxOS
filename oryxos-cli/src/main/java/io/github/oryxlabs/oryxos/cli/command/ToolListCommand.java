package io.github.oryxlabs.oryxos.cli.command;

import io.github.oryxlabs.oryxos.channel.cli.CliOutput;
import io.github.oryxlabs.oryxos.cli.OryxCommandLine;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceProcessLock;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description =
        "List registered tools while running, static descriptors offline (MCP not discovered)")
public final class ToolListCommand implements Callable<Integer> {
  private final OryxCommandLine parent;

  @CommandLine.Option(names = "--profile")
  String profile;

  public ToolListCommand(OryxCommandLine parent) {
    this.parent = parent;
  }

  public Integer call() {
    if (profile != null) ProfileFiles.name(profile);
    var workspace = parent.workspace();
    var current = ProfileFiles.current(workspace);
    var view = parent.queries().runtimeView(workspace);
    boolean running = WorkspaceProcessLock.isRunning(workspace);
    if (running && view == null)
      throw new OryxError(
          "QUERY_UNAVAILABLE",
          "Running registry snapshot is not available yet; retry after startup");
    java.util.List<String> selected = null;
    if (profile != null) {
      if (view == null) selected = current.agents().require(profile).tools();
      else {
        var loaded = view.profiles().get(profile);
        if (loaded == null || !loaded.available())
          throw new OryxError(
              "AGENT_UNAVAILABLE", "Profile is not available in the running snapshot");
        selected = loaded.tools();
      }
    }
    var selectedNames = selected;
    var tools = view == null ? parent.queries().staticTools() : view.tools();
    var items =
        tools.stream()
            .filter(t -> selectedNames == null || selectedNames.contains(t.name()))
            .map(t ->
                CliOutput.fields(
                    "name",
                    current.redactor().redact(t.name()),
                    "description",
                    current.redactor().redact(t.description()),
                    "source",
                    t.source(),
                    "available",
                    view != null,
                    "inputSchema",
                    CliOutput.schema(t.inputSchemaJson(), current.redactor()),
                    "safeToRetry",
                    t.safeToRetry()))
            .toList();
    String source = view == null ? "OFFLINE_STATIC" : "RUNNING_REGISTRY_SNAPSHOT",
        mcp = view == null ? "NOT_DISCOVERED" : "STARTUP_DISCOVERY";
    parent.result(
        CliOutput.fields(
            "source",
            source,
            "mcpDiscovery",
            mcp,
            "items",
            items,
            "note",
            view == null
                ? "Static descriptors only; runtime availability and MCP not discovered"
                : "Actual registered tools at this process startup; no live connectivity probe"),
        source + "; MCP=" + mcp + "\n" + items);
    return 0;
  }
}
