package io.github.oryxlabs.oryxos.boot.runtime;

import io.github.oryxlabs.oryxos.core.runtime.QueryPort;
import io.github.oryxlabs.oryxos.core.tool.ToolDescriptor;
import io.github.oryxlabs.oryxos.core.workspace.*;
import io.github.oryxlabs.oryxos.memory.tool.*;
import io.github.oryxlabs.oryxos.storage.query.SqliteReadOnlyQueries;
import io.github.oryxlabs.oryxos.tool.file.*;
import io.github.oryxlabs.oryxos.tool.http.*;
import io.github.oryxlabs.oryxos.tool.nativeext.*;
import io.github.oryxlabs.oryxos.tool.notify.NotifyTool;
import io.github.oryxlabs.oryxos.tool.policy.ToolRetryPolicy;
import io.github.oryxlabs.oryxos.tool.shell.ShellTool;
import java.util.*;

/** Injected before Spring. No executable tool, remote client, migration or container is started. */
public final class BootCliQueries implements QueryPort {
  public Status query(WorkspaceHandle workspace) {
    return new WorkspaceInspector(System.getenv()).query(workspace);
  }

  public RuntimeView runtimeView(WorkspaceHandle workspace) {
    return CliRuntimeView.read(workspace);
  }

  public SessionPage sessions(WorkspaceHandle workspace, String profile, int limit, UUID cursor) {
    var rows =
        new SqliteReadOnlyQueries(workspace).sessionPage(limit, cursor, profile, null, null, null);
    boolean more = rows.size() > limit;
    var page = rows.stream().limit(limit).toList();
    return new SessionPage(page, more ? page.getLast().id().toString() : null);
  }

  public List<ToolDescriptor> staticTools() {
    var descriptors =
        new ArrayList<>(
            List.of(
                ReadFileTool.staticDescriptor(),
                WriteFileTool.staticDescriptor(),
                ListDirTool.staticDescriptor(),
                ShellTool.staticDescriptor(),
                HttpGetTool.staticDescriptor(),
                HttpPostTool.staticDescriptor(),
                NotifyTool.staticDescriptor(),
                SaveMemoryTool.staticDescriptor(),
                RecallMemoryTool.staticDescriptor()));
    descriptors.addAll(
        new NativeToolRegistrar()
            .register(List.of(new EchoTool())).stream().map(t -> t.descriptor()).toList());
    return descriptors.stream()
        .map(ToolRetryPolicy::classify)
        .sorted(Comparator.comparing(ToolDescriptor::name))
        .toList();
  }
}
