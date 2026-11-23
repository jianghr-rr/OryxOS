package io.github.oryxlabs.oryxos.core.model;

/** Internal authorization name and the original protocol name are deliberately distinct. */
public record ToolCall(String id, String name, String wireName, String argumentsJson) {
  /** Null internal name is an unresolved proposal, never authority to execute wireName. */
  public String nameErrorCode() {
    return name == null ? "UNKNOWN_TOOL" : null;
  }

  @Override
  public String toString() {
    return "ToolCall[arguments withheld]";
  }
}
