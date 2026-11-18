package io.github.oryxlabs.oryxos.core.tool;

public record ToolDescriptor(
    String name,
    String description,
    String inputSchemaJson,
    boolean safeToRetry,
    boolean sideEffecting,
    String serverId,
    String remoteName,
    String source) {
  public ToolDescriptor(
      String name,
      String description,
      String schema,
      boolean safe,
      boolean sideEffect,
      String server,
      String remote) {
    this(
        name,
        description,
        schema,
        safe,
        sideEffect,
        server,
        remote,
        server == null ? "BUILTIN" : "MCP");
  }
}
