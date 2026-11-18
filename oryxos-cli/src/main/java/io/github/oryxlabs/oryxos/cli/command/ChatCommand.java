package io.github.oryxlabs.oryxos.cli.command;

import io.github.oryxlabs.oryxos.cli.OryxCommandLine;
import io.github.oryxlabs.oryxos.core.runtime.ChatOptions;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = "chat",
    mixinStandardHelpOptions = true,
    description = "Chat with an Agent; use /quit to exit")
public final class ChatCommand implements Callable<Integer> {
  private final OryxCommandLine parent;
  @CommandLine.Spec private CommandLine.Model.CommandSpec spec;

  @CommandLine.Option(names = "--profile", required = true)
  private String profile;

  @CommandLine.Option(names = "--user", required = true)
  private String user;

  @CommandLine.Option(names = "--session")
  private UUID session;

  public ChatCommand(OryxCommandLine parent) {
    this.parent = parent;
  }

  public Integer call() {
    return parent.chat(
        new ChatOptions(profile, user, session, parent.json()),
        parent.input(),
        spec.commandLine().getOut());
  }
}
