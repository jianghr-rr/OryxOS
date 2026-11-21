package io.github.oryxlabs.oryxos.core.model;

import java.util.List;

public record ChatMessage(
    Role role,
    String content,
    List<ToolCall> toolCalls,
    String toolCallId,
    String toolName,
    ProviderOpaque providerOpaque) {
  public enum Role {
    USER,
    ASSISTANT,
    TOOL
  }

  public ChatMessage {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public static ChatMessage user(String text) {
    return new ChatMessage(Role.USER, text, List.of(), null, null, null);
  }

  public static ChatMessage tool(ToolCall call, String text) {
    return new ChatMessage(Role.TOOL, text, List.of(), call.id(), call.name(), null);
  }

  /** Private continuation data, never a public history/audit/long-term-memory projection. */
  public record ProviderOpaque(String provider, String model, String reasoningContent) {
    @Override
    public String toString() {
      return "ProviderOpaque[withheld]";
    }
  }

  @Override
  public String toString() {
    return "ChatMessage[role=" + role + ", content withheld]";
  }
}
