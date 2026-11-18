package io.github.oryxlabs.oryxos.core.workspace;

import io.github.oryxlabs.oryxos.core.agent.AgentCatalog;
import io.github.oryxlabs.oryxos.core.config.RuntimeConfigLoader;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.runtime.QueryPort;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import java.nio.file.*;
import java.util.Map;

/** Lightweight file/lock inspection: no Spring, database creation, migrations or external calls. */
public final class WorkspaceInspector implements QueryPort {
  private final Map<String, String> environment;

  public WorkspaceInspector(Map<String, String> environment) {
    this.environment = Map.copyOf(environment);
  }

  public Status query(WorkspaceHandle workspace) {
    WorkspaceInitializer.readId(workspace);
    var redactor = new Redactor();
    var config = new RuntimeConfigLoader(environment, redactor).load(workspace);
    var agents = AgentCatalog.load(workspace, config, environment, redactor);
    boolean running = WorkspaceProcessLock.isRunning(workspace);
    String fault = "NONE_OBSERVED";
    if (Files.exists(workspace.runtime().resolve("fault.json"))) fault = "FAULT_MARKER_PRESENT";
    Path runs = workspace.runtime().resolve("runs");
    if (Files.exists(runs)) {
      try (var files = Files.list(runs)) {
        if (!running && files.findAny().isPresent()) fault = "UNREVIEWED_RUN_MARKERS";
      } catch (java.io.IOException error) {
        throw new OryxError("WORKSPACE_UNAVAILABLE", "Cannot inspect runtime markers");
      }
    }
    return new Status(
        config.memoryBackend(),
        config.timezone().getId(),
        running ? "RUNNING" : "NOT_RUNNING",
        fault,
        config.providers().values().stream()
            .filter(p -> !p.available())
            .map(p -> redactor.redact(p.name() + ": " + p.error().safeMessage()))
            .toList(),
        agents.entries().stream()
            .map(
                e ->
                    new AgentStatus(
                        redactor.redact(e.name()),
                        e.available(),
                        e.available() ? "CONFIGURED" : e.error().code(),
                        e.available()
                            ? "Configuration valid; external services not probed"
                            : e.error().safeMessage(),
                        e.available()
                            ? e.profile().notifyChannelNames().stream()
                                .map(redactor::redact)
                                .sorted()
                                .toList()
                            : java.util.List.of()))
            .toList());
  }
}
