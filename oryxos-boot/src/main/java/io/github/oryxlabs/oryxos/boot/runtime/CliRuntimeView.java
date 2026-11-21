package io.github.oryxlabs.oryxos.boot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.boot.config.*;
import io.github.oryxlabs.oryxos.channel.cli.CliOutput;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.runtime.QueryPort;
import io.github.oryxlabs.oryxos.core.security.Redactor;
import io.github.oryxlabs.oryxos.core.tool.*;
import io.github.oryxlabs.oryxos.core.workspace.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Disposable startup projection of the actual registry, never configuration authority or health.
 */
final class CliRuntimeView {
  private static final ObjectMapper JSON = new ObjectMapper();

  record Published(UUID workspaceId, long pid, String processStarted, QueryPort.RuntimeView view) {}

  private static Path path(WorkspaceHandle workspace) {
    if (Files.isSymbolicLink(workspace.runtime()))
      throw OryxError.configuration("runtime directory boundary");
    return workspace.runtime().resolve("cli-view.json");
  }

  static void clear(WorkspaceHandle workspace) {
    try {
      Files.deleteIfExists(path(workspace));
    } catch (java.io.IOException e) {
      throw new OryxError("QUERY_UNAVAILABLE", "Cannot clear stale CLI runtime view");
    }
  }

  static void publish(WorkspaceHandle workspace, ConfigurableApplicationContext context) {
    try {
      var redactor = context.getBean(Redactor.class);
      var loaded = context.getBean(ProviderConfiguration.LoadedAgents.class).catalog();
      var checked = context.getBean(ToolConfiguration.CheckedAgents.class).catalog();
      var profiles = new LinkedHashMap<String, QueryPort.LoadedProfile>();
      if (loaded != null)
        for (var entry : loaded.entries())
          if (entry.available()) {
            var p = entry.profile();
            boolean available =
                checked != null
                    && checked.entries().stream()
                        .anyMatch(e -> e.name().equals(p.name()) && e.available());
            profiles.put(
                redactor.redact(p.name()),
                new QueryPort.LoadedProfile(
                    p.configRevision(),
                    p.bodyRevision(),
                    p.tools().stream().map(redactor::redact).toList(),
                    available));
          }
      var tools = new ArrayList<ToolDescriptor>();
      for (var t : context.getBean(ToolRegistryPort.class).all())
        tools.add(
            new ToolDescriptor(
                redactor.redact(t.name()),
                redactor.redact(t.description()),
                JSON.writeValueAsString(CliOutput.schema(t.inputSchemaJson(), redactor)),
                t.safeToRetry(),
                t.sideEffecting(),
                redactor.redact(t.serverId()),
                redactor.redact(t.remoteName()),
                t.source()));
      var process = ProcessHandle.current();
      var published =
          new Published(
              WorkspaceInitializer.readId(workspace),
              process.pid(),
              process.info().startInstant().orElseThrow().toString(),
              new QueryPort.RuntimeView(profiles, tools));
      Path target = path(workspace),
          temporary = Files.createTempFile(workspace.runtime(), ".cli-view-", ".tmp");
      try {
        Files.writeString(temporary, JSON.writeValueAsString(published));
        Files.move(
            temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
      context.addApplicationListener(event -> {
        if (event instanceof ContextClosedEvent) clear(workspace);
      });
    } catch (Exception error) {
      throw new OryxError("QUERY_UNAVAILABLE", "Cannot publish safe CLI registry snapshot");
    }
  }

  static QueryPort.RuntimeView read(WorkspaceHandle workspace) {
    if (!WorkspaceProcessLock.isRunning(workspace)) return null;
    try (var in = Files.newInputStream(path(workspace), LinkOption.NOFOLLOW_LINKS)) {
      byte[] data = in.readNBytes(4 * 1024 * 1024 + 1);
      if (data.length > 4 * 1024 * 1024) return null;
      var published = JSON.readValue(data, Published.class);
      if (!published.workspaceId().equals(WorkspaceInitializer.readId(workspace))) return null;
      var process = ProcessHandle.of(published.pid());
      if (process.isEmpty()
          || !process.get().isAlive()
          || !process
              .get()
              .info()
              .startInstant()
              .map(Object::toString)
              .orElse("")
              .equals(published.processStarted())) return null;
      return WorkspaceProcessLock.isRunning(workspace) ? published.view() : null;
    } catch (Exception error) {
      return null;
    }
  }
}
