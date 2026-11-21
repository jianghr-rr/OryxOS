package io.github.oryxlabs.oryxos.cli.command;

import io.github.oryxlabs.oryxos.cli.OryxCommandLine;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "status",
    mixinStandardHelpOptions = true,
    description = "Inspect files and process lock without starting the runtime")
public final class StatusCommand implements Callable<Integer> {
  private final OryxCommandLine parent;
  @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

  public StatusCommand(OryxCommandLine parent) {
    this.parent = parent;
  }

  public Integer call() {
    var workspace = parent.workspace();
    var status = parent.queries().query(workspace);
    var data =
        io.github.oryxlabs.oryxos.channel.cli.CliOutput.fields(
            "workspaceRoot",
            workspace.root().toString(),
            "backend",
            status.backend(),
            "timezone",
            status.timezone(),
            "process",
            status.process(),
            "fault",
            status.fault(),
            "unavailableProviders",
            status.unavailableProviders(),
            "agents",
            status.agents(),
            "databaseHealth",
            "NOT_CHECKED",
            "executionReadiness",
            "NOT_CHECKED",
            "api",
            "HTTP_API_IMPLEMENTED",
            "admin",
            "IMPLEMENTED");
    parent.result(
        data,
        "Workspace: "
            + workspace.root()
            + "\nBackend: "
            + status.backend()
            + " (only selected backend visible; switching does not migrate data)\nTimezone: "
            + status.timezone()
            + "\nProcess: "
            + status.process()
            + "\nFault markers: "
            + status.fault()
            + "\nProvider accounts NOT_PROBED: "
            + status.unavailableProviders()
            + "\nAgent configurations (current files; running process retains startup snapshot):\n"
            + status.agents().stream()
                .map(a ->
                    a.name()
                        + ": "
                        + a.code()
                        + " — "
                        + a.safeMessage()
                        + "; notify channels: "
                        + a.notifyChannels())
                .collect(java.util.stream.Collectors.joining("\n"))
            + "\n"
            + "Execution/database health: NOT_CHECKED by this light query; inspect /api/v1/health"
            + " on the running service. HTTP API and /admin/ implemented; runtime health is not"
            + " checked here.");
    return 0;
  }
}
