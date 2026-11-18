package io.github.oryxlabs.oryxos.core.runtime;

import io.github.oryxlabs.oryxos.core.workspace.WorkspaceHandle;
import java.util.List;

@FunctionalInterface
public interface QueryPort {
  Status query(WorkspaceHandle workspace);

  default RuntimeView runtimeView(WorkspaceHandle workspace) {
    return null;
  }

  default java.util.List<io.github.oryxlabs.oryxos.core.tool.ToolDescriptor> staticTools() {
    return java.util.List.of();
  }

  default SessionPage sessions(
      WorkspaceHandle workspace, String profile, int limit, java.util.UUID cursor) {
    throw new io.github.oryxlabs.oryxos.core.error.OryxError(
        "QUERY_UNAVAILABLE", "Use the complete Boot distribution for stored session queries");
  }

  record SessionPage(
      java.util.List<io.github.oryxlabs.oryxos.core.session.Session> items, String nextCursor) {}

  record LoadedProfile(
      String configRevision,
      String bodyRevision,
      java.util.List<String> tools,
      boolean available) {}

  record RuntimeView(
      java.util.Map<String, LoadedProfile> profiles,
      java.util.List<io.github.oryxlabs.oryxos.core.tool.ToolDescriptor> tools) {}

  record AgentStatus(
      String name,
      boolean configured,
      String code,
      String safeMessage,
      List<String> notifyChannels) {}

  record Status(
      String backend,
      String timezone,
      String process,
      String fault,
      List<String> unavailableProviders,
      List<AgentStatus> agents) {}
}
