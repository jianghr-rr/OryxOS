package io.github.oryxlabs.oryxos.tool.file;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.nio.file.*;
import java.util.Map;

public final class ListDirTool implements OryxTool {
  private final FileAccessPolicy policy;

  public ListDirTool(FileAccessPolicy policy) {
    this.policy = policy;
  }

  public ToolDescriptor descriptor() {
    return staticDescriptor();
  }

  public static ToolDescriptor staticDescriptor() {
    return new ToolDescriptor(
        "list_dir",
        "List at most 1000 direct entries in an allowed directory",
        ReadFileTool.SCHEMA,
        true,
        false,
        null,
        null);
  }

  public void authorize(Map<String, Object> a, ExecutionContext c) {
    policy.authorize((String) a.get("path"), false);
  }

  public ToolResult execute(Map<String, Object> a, ExecutionContext c) {
    var path = policy.authorize((String) a.get("path"), false);
    c.dispatcher().check();
    try (var entries = Files.list(path)) {
      var names = entries.limit(1001).map(p -> p.getFileName().toString()).toList();
      return ToolResult.success(
          "truncated="
              + (names.size() > 1000)
              + "\n"
              + String.join("\n", names.stream().limit(1000).sorted().toList()));
    } catch (java.io.IOException e) {
      return ToolResult.failure(
          ToolResult.Outcome.ERROR, "DIRECTORY_READ_FAILED", "Cannot list the allowed directory");
    }
  }
}
