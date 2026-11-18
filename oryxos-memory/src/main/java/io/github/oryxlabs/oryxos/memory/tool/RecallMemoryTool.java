package io.github.oryxlabs.oryxos.memory.tool;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.util.*;
import java.util.stream.Collectors;

public final class RecallMemoryTool implements OryxTool {
  private final LongTermMemoryStore store;

  public RecallMemoryTool(LongTermMemoryStore store) {
    this.store = store;
  }

  public ToolDescriptor descriptor() {
    return staticDescriptor();
  }

  public static ToolDescriptor staticDescriptor() {
    return new ToolDescriptor(
        "recall_memory",
        "Search ARCHIVAL memory only; results may be empty",
        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"minLength\":1,\"pattern\":\"\\\\S\"}},\"required\":[\"query\"],\"additionalProperties\":false}",
        false,
        false,
        null,
        null);
  }

  public void authorize(Map<String, Object> args, ExecutionContext ctx) {
    if (store == null) throw new OryxError("MEMORY_UNAVAILABLE", "Memory backend is not ready");
    store.authorize(ctx);
  }

  public ToolResult execute(Map<String, Object> args, ExecutionContext ctx) {
    var matches = store.recall((String) args.get("query"), ctx);
    return ToolResult.success(
        matches.isEmpty()
            ? "No matching archival memory"
            : matches.stream()
                .map(e -> e.id() + ": " + e.rawContent())
                .collect(Collectors.joining("\n")));
  }
}
