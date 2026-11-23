package io.github.oryxlabs.oryxos.cli;

import io.github.oryxlabs.oryxos.channel.cli.CliOutput;
import io.github.oryxlabs.oryxos.cli.command.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;

/** Parser and light commands precede construction of any Spring context. */
@CommandLine.Command(
    name = "oryxos",
    mixinStandardHelpOptions = true,
    versionProvider = OryxCommandLine.Version.class,
    description =
        "OryxOS persistent runtime. No arguments starts serve. CLI chat, serve and gateway share"
            + " one exclusive workspace lock.")
public final class OryxCommandLine implements Callable<Integer> {
  @CommandLine.Option(
      names = "--root",
      scope = CommandLine.ScopeType.INHERIT,
      description = "Workspace root")
  private String root;

  @CommandLine.Option(
      names = "--json",
      scope = CommandLine.ScopeType.INHERIT,
      description = "Machine-readable JSON envelopes (chat: one per turn)")
  private boolean json;

  @CommandLine.Spec private CommandLine.Model.CommandSpec spec;
  private final RuntimeLauncher launcher;
  private final QueryPort queries;
  private java.io.Reader input =
      new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8);

  public OryxCommandLine(RuntimeLauncher launcher, QueryPort queries) {
    this.launcher = launcher;
    this.queries = queries;
  }

  public OryxCommandLine(RuntimeLauncher launcher) {
    this(launcher, new WorkspaceInspector(System.getenv()));
  }

  public WorkspaceHandle workspace() {
    return WorkspaceResolver.resolve(root);
  }

  public QueryPort queries() {
    return queries;
  }

  public boolean json() {
    return json;
  }

  public OryxCommandLine input(java.io.Reader input) {
    this.input = java.util.Objects.requireNonNull(input);
    return this;
  }

  public java.io.Reader input() {
    return input;
  }

  public void result(Object data, String human) {
    if (json) CliOutput.json(spec.commandLine().getOut(), "OK", "Operation completed", data);
    else spec.commandLine().getOut().println(human);
  }

  public int chat(ChatOptions options, java.io.Reader input, java.io.PrintWriter output) {
    if (WorkspaceProcessLock.isRunning(workspace()))
      throw new OryxError(
          "WORKSPACE_BUSY",
          "Workspace already running; use its HTTP API. Remote CLI chat is not supported");
    return launcher.chat(workspace(), options, input, output);
  }

  public int launch(LaunchMode mode) {
    int result = launcher.launch(mode, workspace());
    if (result == 0)
      result(
          CliOutput.fields(
              "mode",
              mode,
              "host",
              "HTTP",
              "scheduling",
              "CONFIGURED_SCHEDULES",
              "readiness",
              "CHECK_HEALTH",
              "inboundIM",
              false),
          mode
              + ": HTTP and configured schedules started; inspect /api/v1/health for readiness. No"
              + " inbound IM adapter; admin UI: /admin/.");
    return result;
  }

  public CommandLine commandLine() {
    var line =
        new CommandLine(this)
            .addSubcommand("init", new InitCommand(this))
            .addSubcommand("status", new StatusCommand(this))
            .addSubcommand("serve", new ServeCommand(this))
            .addSubcommand("gateway", new GatewayCommand(this))
            .addSubcommand("chat", new ChatCommand(this))
            .addSubcommand("profile", new ProfileCommand(this).commandLine())
            .addSubcommand("provider", group("provider", new ProviderListCommand(this)))
            .addSubcommand("tool", group("tool", new ToolListCommand(this)))
            .addSubcommand("session", group("session", new SessionListCommand(this)));
    line.setParameterExceptionHandler((error, args) -> {
      boolean jsonRequested = json || java.util.Arrays.asList(args).contains("--json");
      failure(
          line, "INVALID_INPUT", "Check --help for supported commands and options", jsonRequested);
      return 2;
    });
    line.setExecutionExceptionHandler((error, command, parsed) -> {
      if (error instanceof OryxError known) {
        failure(line, known.code(), known.getMessage(), json);
        return CliOutput.exitCode(known.code());
      }
      failure(
          line,
          "OPERATION_FAILED",
          "Operation could not complete; raw configuration and credentials are not printed",
          json);
      return 3;
    });
    return line;
  }

  private static CommandLine group(String name, Object list) {
    var spec = CommandLine.Model.CommandSpec.create().name(name).mixinStandardHelpOptions(true);
    return new CommandLine(spec).addSubcommand("list", list);
  }

  private void failure(CommandLine line, String code, String message, boolean machine) {
    if (machine) CliOutput.json(line.getOut(), code, message, null);
    else line.getErr().println(code + ": " + message);
  }

  public static int execute(String[] args, RuntimeLauncher launcher) {
    return new OryxCommandLine(launcher).commandLine().execute(args);
  }

  @Override
  public Integer call() {
    return launch(LaunchMode.SERVE);
  }

  public static final class Version implements CommandLine.IVersionProvider {
    public String[] getVersion() {
      return new String[] {"OryxOS " + OryxOsCli.version()};
    }
  }
}
