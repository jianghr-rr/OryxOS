package io.github.oryxlabs.oryxos.tool.mcp;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.util.Map;

public final class McpToolAdapter implements OryxTool {
  private final McpClientManager clients;
  private final ToolDescriptor descriptor;

  public McpToolAdapter(
      McpClientManager clients, String server, String remote, String description, String schema) {
    this.clients = clients;
    descriptor =
        new ToolDescriptor(
            "mcp." + server + "." + remote, description, schema, false, true, server, remote);
  }

  public ToolDescriptor descriptor() {
    return descriptor;
  }

  public void authorize(Map<String, Object> a, ExecutionContext c) {
    if (!c.agent().mcpServers().contains(descriptor.serverId()))
      throw new OryxError("TARGET_DENIED", "Agent must explicitly select the MCP server and tool");
  }

  public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
    return clients.call(descriptor.serverId(), descriptor.remoteName(), a, c);
  }
}
