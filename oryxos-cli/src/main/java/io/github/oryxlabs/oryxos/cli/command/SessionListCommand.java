package io.github.oryxlabs.oryxos.cli.command;

import io.github.oryxlabs.oryxos.channel.cli.CliOutput;
import io.github.oryxlabs.oryxos.cli.OryxCommandLine;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "Read stored sessions without creating or migrating the database")
public final class SessionListCommand implements Callable<Integer> {
  private final OryxCommandLine parent;

  @CommandLine.Option(names = "--profile")
  String profile;

  @CommandLine.Option(names = "--limit", defaultValue = "50")
  int limit;

  @CommandLine.Option(names = "--cursor")
  UUID cursor;

  public SessionListCommand(OryxCommandLine parent) {
    this.parent = parent;
  }

  public Integer call() {
    if (profile != null) ProfileFiles.name(profile);
    if (limit < 1 || limit > 200)
      throw new OryxError("INVALID_INPUT", "Limit must be between 1 and 200");
    var current = ProfileFiles.current(parent.workspace());
    var page = parent.queries().sessions(parent.workspace(), profile, limit, cursor);
    var items = page.items().stream().map(s -> CliOutput.session(s, current.redactor())).toList();
    parent.result(
        CliOutput.fields("items", items, "nextCursor", page.nextCursor()),
        "Stored sessions (read only):\n" + items + "\nnextCursor=" + page.nextCursor());
    return 0;
  }
}
