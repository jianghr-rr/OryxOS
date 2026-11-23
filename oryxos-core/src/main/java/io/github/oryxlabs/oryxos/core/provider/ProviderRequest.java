package io.github.oryxlabs.oryxos.core.provider;

import io.github.oryxlabs.oryxos.core.model.*;
import java.time.Duration;
import java.util.*;

/**
 * Provider options are resolved exclusively from the registry's runtime configuration snapshot.
 * Runtime admission belongs to the caller; a request carries no admission capability.
 */
public record ProviderRequest(
    String providerName,
    String model,
    String systemPrompt,
    List<ChatMessage> orderedMessages,
    List<ToolSchema> tools,
    ToolNameMap toolNames,
    Duration remainingDuration,
    UUID executionId,
    int iteration) {
  public record ToolSchema(String name, String description, String inputSchemaJson) {}

  public ProviderRequest {
    orderedMessages = List.copyOf(orderedMessages);
    tools = List.copyOf(tools);
    Objects.requireNonNull(toolNames);
    Objects.requireNonNull(remainingDuration);
    Objects.requireNonNull(executionId);
  }

  public ProviderRequest withRemainingDuration(Duration remaining) {
    return new ProviderRequest(
        providerName,
        model,
        systemPrompt,
        orderedMessages,
        tools,
        toolNames,
        remaining,
        executionId,
        iteration);
  }

  @Override
  public String toString() {
    return "ProviderRequest[executionId=" + executionId + ", iteration=" + iteration + "]";
  }
}
