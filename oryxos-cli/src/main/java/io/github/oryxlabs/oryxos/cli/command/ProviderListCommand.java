package io.github.oryxlabs.oryxos.cli.command;

import io.github.oryxlabs.oryxos.channel.cli.CliOutput;
import io.github.oryxlabs.oryxos.cli.OryxCommandLine;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = "List provider configurations without probing accounts or exposing credentials")
public final class ProviderListCommand implements Callable<Integer> {
  private final OryxCommandLine parent;

  public ProviderListCommand(OryxCommandLine parent) {
    this.parent = parent;
  }

  public Integer call() {
    var current = ProfileFiles.current(parent.workspace());
    var items =
        current.runtime().providers().values().stream()
            .sorted(java.util.Comparator.comparing(p -> p.name()))
            .map(
                p ->
                    CliOutput.fields(
                        "name",
                        current.redactor().redact(p.name()),
                        "protocol",
                        p.protocol(),
                        "configured",
                        p.available(),
                        "connectivity",
                        "NOT_PROBED",
                        "safeError",
                        p.available() ? null : current.redactor().redact(p.error().safeMessage())))
            .toList();
    parent.result(
        CliOutput.fields("source", "CURRENT_FILES", "items", items),
        "Provider configurations (current files; accounts NOT_PROBED):\n" + items);
    return 0;
  }
}
