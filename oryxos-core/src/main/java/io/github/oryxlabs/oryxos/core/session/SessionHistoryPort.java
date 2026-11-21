package io.github.oryxlabs.oryxos.core.session;

import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import java.util.*;

public interface SessionHistoryPort {
  record History(List<ChatMessage> recent, List<ChatMessage> current) {
    public History {
      recent = List.copyOf(recent);
      current = List.copyOf(current);
    }
  }

  History history(UUID sessionId, UUID currentTurn, int maxHistoryTurns);
}
