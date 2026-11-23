package io.github.oryxlabs.oryxos.boot.support;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.session.SessionHistoryPort;

/** Only long-term memory is empty; history is always selected by the real SessionManager. */
public final class EmptyMemoryFacade implements MemoryPort {
  private final SessionHistoryPort sessions;

  public EmptyMemoryFacade(SessionHistoryPort sessions) {
    this.sessions = sessions;
  }

  public ContextMemory load(ExecutionContext context) {
    var history =
        sessions.history(
            context.execution().sessionId(),
            context.execution().turnId(),
            context.agent().maxHistoryTurns());
    return new ContextMemory(history.recent(), history.current(), "", "");
  }
}
