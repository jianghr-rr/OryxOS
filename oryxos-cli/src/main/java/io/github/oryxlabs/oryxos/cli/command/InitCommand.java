package io.github.oryxlabs.oryxos.cli.command;

import io.github.oryxlabs.oryxos.cli.OryxCommandLine;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceInitializer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "init",
    mixinStandardHelpOptions = true,
    description = "Create missing workspace files without overwriting existing content")
public final class InitCommand implements Callable<Integer> {
  private final OryxCommandLine parent;
  @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

  public InitCommand(OryxCommandLine parent) {
    this.parent = parent;
  }

  public Integer call() throws Exception {
    var templates = new LinkedHashMap<String, String>();
    for (String name : List.of("runtime.yaml", "AGENT.md", "AGENTS.md", "SOUL.md", "USER.md")) {
      try (var resource = InitCommand.class.getResourceAsStream("/templates/" + name)) {
        if (resource == null) throw new IllegalStateException("Missing bundled template");
        templates.put(
            name.equals("AGENT.md") ? "agents/default/AGENT.md" : name,
            new String(resource.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    var workspace = parent.workspace();
    var id = new WorkspaceInitializer().initialize(workspace, templates);
    parent.result(
        io.github.oryxlabs.oryxos.channel.cli.CliOutput.fields(
            "workspaceRoot",
            workspace.root().toString(),
            "workspaceId",
            id,
            "databaseCreated",
            false),
        "Workspace: "
            + workspace.root()
            + "\nWorkspace ID: "
            + id
            + "\n"
            + "Missing files initialized; existing files preserved. Configure provider credentials"
            + " and an Agent before chat. SQLite initializes when chat/serve/gateway starts.");
    return 0;
  }
}
