package io.github.oryxlabs.oryxos.cli.command;

import io.github.oryxlabs.oryxos.cli.OryxCommandLine;
import io.github.oryxlabs.oryxos.core.runtime.LaunchMode;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "gateway",
    mixinStandardHelpOptions = true,
    description =
        "Start persistent HTTP API and configured schedules; no inbound IM adapter. Admin UI"
            + ": /admin/.")
public final class GatewayCommand implements Callable<Integer> {
  private final OryxCommandLine parent;

  public GatewayCommand(OryxCommandLine parent) {
    this.parent = parent;
  }

  public Integer call() {
    return parent.launch(LaunchMode.GATEWAY);
  }
}
