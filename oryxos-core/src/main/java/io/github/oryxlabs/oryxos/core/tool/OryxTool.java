package io.github.oryxlabs.oryxos.core.tool;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import java.util.Map;

public interface OryxTool {
  ToolDescriptor descriptor();

  /** Resource-level policy; this is mandatory in addition to the Agent's tool-name allowlist. */
  void authorize(Map<String, Object> arguments, ExecutionContext context);

  ToolResult execute(Map<String, Object> arguments, ExecutionContext context);
}
