package io.github.oryxlabs.oryxos.core.memory;

import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import java.util.List;

public record ContextMemory(
    List<ChatMessage> recent, List<ChatMessage> current, String core, String archival) {
  public ContextMemory {
    recent = List.copyOf(recent);
    current = List.copyOf(current);
  }
}
