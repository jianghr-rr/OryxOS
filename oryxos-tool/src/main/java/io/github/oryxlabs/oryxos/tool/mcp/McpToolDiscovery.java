package io.github.oryxlabs.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.oryxlabs.oryxos.core.tool.OryxTool;
import java.util.*;

public final class McpToolDiscovery {
  public List<OryxTool> discover(McpClientManager manager) {
    var out = new ArrayList<OryxTool>();
    var json = new ObjectMapper();
    for (var state : manager.states())
      for (var tool : manager.tools(state.id()))
        try {
          out.add(
              new McpToolAdapter(
                  manager,
                  state.id(),
                  tool.name(),
                  tool.description() == null ? "Configured MCP tool" : tool.description(),
                  json.writeValueAsString(tool.inputSchema())));
        } catch (Exception e) {
          throw new IllegalStateException("MCP descriptor could not be encoded");
        }
    return List.copyOf(out);
  }
}
