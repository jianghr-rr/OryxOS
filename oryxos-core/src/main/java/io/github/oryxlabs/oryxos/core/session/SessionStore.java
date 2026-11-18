package io.github.oryxlabs.oryxos.core.session;

import java.util.*;

/** Storage only; identity checks, occupancy and turn selection live in SessionManager. */
public interface SessionStore {
  void create(Session session);

  /** Whether a new session is inserted by begin together with the execution and USER message. */
  default boolean atomicBegin() {
    return false;
  }

  /**
   * Persistent adapters override this with one transaction; early test stores retain simple
   * semantics.
   */
  default void begin(
      Session session,
      boolean newSession,
      io.github.oryxlabs.oryxos.core.execution.Execution.Snapshot execution,
      SessionMessage user,
      io.github.oryxlabs.oryxos.core.execution.ExecutionStore executions) {
    if (newSession) create(session);
    executions.save(execution);
    append(user);
  }

  default void begin(
      Session session,
      boolean newSession,
      io.github.oryxlabs.oryxos.core.execution.Execution.Snapshot execution,
      SessionMessage user,
      io.github.oryxlabs.oryxos.core.execution.ExecutionStore executions,
      Runnable onCommit,
      Runnable onRollback) {
    try {
      begin(session, newSession, execution, user, executions);
      if (onCommit != null) onCommit.run();
    } catch (RuntimeException error) {
      if (onRollback != null) onRollback.run();
      throw error;
    }
  }

  default void archive(UUID id, java.time.Instant at) {
    throw new UnsupportedOperationException("Archive storage is not configured");
  }

  Session find(UUID id);

  List<SessionMessage> messages(UUID id);

  Set<UUID> completedTurns(UUID id);

  default long lastSequence(UUID id) {
    return messages(id).stream().mapToLong(SessionMessage::seq).max().orElse(0);
  }

  default List<SessionMessage> turnMessages(UUID id, UUID turn) {
    return messages(id).stream().filter(m -> m.turnId().equals(turn)).toList();
  }

  default SessionHistoryPort.History history(UUID id, UUID current, int maxTurns) {
    return new HistorySelector().select(messages(id), completedTurns(id), current, maxTurns);
  }

  void append(SessionMessage message);

  void finishTurn(UUID sessionId, UUID turnId);

  default void finishTurnVerified(UUID sessionId, UUID turnId) {
    finishTurn(sessionId, turnId);
  }
}
