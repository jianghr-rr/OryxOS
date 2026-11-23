package io.github.oryxlabs.oryxos.core.provider;

import io.github.oryxlabs.oryxos.core.model.*;
import java.util.List;

public record ProviderReply(
    ChatMessage assistantMessage,
    String finishReason,
    Usage usage,
    String actualProvider,
    String actualModel) {
  public record Usage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {}

  public List<ToolCall> toolCalls() {
    return assistantMessage.toolCalls();
  }

  @Override
  public String toString() {
    return "ProviderReply[content and continuation withheld]";
  }
}
