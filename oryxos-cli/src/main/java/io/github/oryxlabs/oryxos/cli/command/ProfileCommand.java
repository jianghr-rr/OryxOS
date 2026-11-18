package io.github.oryxlabs.oryxos.cli.command;

import io.github.oryxlabs.oryxos.channel.cli.CliOutput;
import io.github.oryxlabs.oryxos.cli.OryxCommandLine;
import io.github.oryxlabs.oryxos.core.agent.AgentDefinitionReader;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceProcessLock;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "profile",
    mixinStandardHelpOptions = true,
    description =
        "Manage authoritative agents/<name>/AGENT.md files; configuration changes require restart")
public final class ProfileCommand {
  private final OryxCommandLine parent;

  public ProfileCommand(OryxCommandLine parent) {
    this.parent = parent;
  }

  public CommandLine commandLine() {
    return new CommandLine(this)
        .addSubcommand("list", new ListProfiles())
        .addSubcommand("create", new Create())
        .addSubcommand("show", new Show())
        .addSubcommand("delete", new Delete());
  }

  @CommandLine.Command(
      name = "list",
      mixinStandardHelpOptions = true,
      description = "List current file definitions and parse state")
  final class ListProfiles implements Callable<Integer> {
    public Integer call() {
      var status = parent.queries().query(parent.workspace());
      parent.result(
          CliOutput.fields("source", "CURRENT_FILES", "items", status.agents()),
          "Current files (running processes retain their startup snapshot):\n"
              + status.agents().stream()
                  .map(a -> a.name() + ": " + a.code() + " — " + a.safeMessage())
                  .collect(java.util.stream.Collectors.joining("\n")));
      return 0;
    }
  }

  @CommandLine.Command(
      name = "create",
      mixinStandardHelpOptions = true,
      description = "Create a template without overwriting a profile")
  final class Create implements Callable<Integer> {
    @CommandLine.Parameters(index = "0")
    String name;

    public Integer call() {
      ProfileFiles.create(parent.workspace(), name);
      parent.result(
          CliOutput.fields("name", name, "created", true, "restartRequired", true),
          "Created " + name + ". Configure its provider/model and restart to load this profile.");
      return 0;
    }
  }

  @CommandLine.Command(
      name = "show",
      mixinStandardHelpOptions = true,
      description = "Show redacted current configuration/body and compare loaded snapshot")
  final class Show implements Callable<Integer> {
    @CommandLine.Parameters(index = "0")
    String name;

    public Integer call() {
      var workspace = parent.workspace();
      var file = ProfileFiles.path(workspace, name, true);
      var current = ProfileFiles.current(workspace);
      var definition = new AgentDefinitionReader().read(file);
      var entry =
          current.agents().entries().stream()
              .filter(e -> e.name().equals(name))
              .findFirst()
              .orElseThrow();
      var view = parent.queries().runtimeView(workspace);
      var loaded = view == null ? null : view.profiles().get(name);
      String comparison =
          !WorkspaceProcessLock.isRunning(workspace)
              ? "NOT_RUNNING"
              : view == null
                  ? "SNAPSHOT_UNAVAILABLE"
                  : loaded == null
                      ? "NOT_LOADED"
                      : !entry.available()
                              || !loaded.configRevision().equals(entry.profile().configRevision())
                          ? "RESTART_REQUIRED"
                          : !loaded.bodyRevision().equals(entry.profile().bodyRevision())
                              ? "BODY_CHANGED"
                              : "MATCH";
      var data =
          CliOutput.fields(
              "name",
              name,
              "currentFile",
              CliOutput.fields(
                  "configuration",
                  publicConfiguration(definition.configuration(), current.redactor()),
                  "body",
                  current.redactor().redact(definition.body()),
                  "configured",
                  entry.available()),
              "loadedSnapshot",
              loaded,
              "snapshotComparison",
              comparison,
              "note",
              "Configuration uses the startup snapshot; restart to load changes. Body/bootstrap are"
                  + " read at the next task. No external service was probed.");
      parent.result(
          data,
          "Current file (redacted):\n"
              + data.get("currentFile")
              + "\nSnapshot: "
              + comparison
              + "\n"
              + data.get("note"));
      return 0;
    }
  }

  @CommandLine.Command(
      name = "delete",
      mixinStandardHelpOptions = true,
      description = "Archive a profile while stopped; keep sessions, executions and audits")
  final class Delete implements Callable<Integer> {
    @CommandLine.Parameters(index = "0")
    String name;

    public Integer call() {
      var target = ProfileFiles.archive(parent.workspace(), name);
      parent.result(
          CliOutput.fields(
              "name",
              name,
              "archivedTo",
              parent.workspace().root().relativize(target).toString(),
              "historyRetained",
              true),
          "Archived " + name + " to " + target + "; sessions, executions and audits retained.");
      return 0;
    }
  }

  private static Object publicConfiguration(
      Object v, io.github.oryxlabs.oryxos.core.security.Redactor r) {
    if (v instanceof Map<?, ?> m) {
      var result = new LinkedHashMap<String, Object>();
      m.forEach(
          (k, value) ->
              result.put(
                  r.redact(k.toString()),
                  k.toString()
                          .matches(
                              "(?i).*(secret|password|token|api.?key|authorization|headers|url).*")
                      ? "[REDACTED]"
                      : publicConfiguration(value, r)));
      return result;
    }
    if (v instanceof List<?> l) return l.stream().map(x -> publicConfiguration(x, r)).toList();
    return CliOutput.safe(v, r);
  }
}
