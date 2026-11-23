package io.github.oryxlabs.oryxos.boot.support;

import io.github.oryxlabs.oryxos.core.session.*;
import java.util.*;

/** Explicit test storage only. All session behavior remains in the production SessionManager. */
public final class TransientSessionStore implements SessionStore {
  private final Map<UUID, Session> sessions = new LinkedHashMap<>();
  private final List<SessionMessage> messages = new ArrayList<>();
  private final Map<UUID, Set<UUID>> completed = new HashMap<>();

  public synchronized void create(Session s) {
    if (sessions.putIfAbsent(s.id(), s) != null)
      throw new IllegalStateException("Duplicate session");
  }

  public synchronized void archive(UUID id, java.time.Instant at) {
    sessions.put(id, sessions.get(id).archived(at));
  }

  public synchronized Session find(UUID id) {
    return sessions.get(id);
  }

  public synchronized List<SessionMessage> messages(UUID id) {
    return messages.stream().filter(m -> m.sessionId().equals(id)).toList();
  }

  public synchronized Set<UUID> completedTurns(UUID id) {
    return Set.copyOf(completed.getOrDefault(id, Set.of()));
  }

  public synchronized void append(SessionMessage m) {
    messages.add(m);
  }

  public synchronized void finishTurn(UUID session, UUID turn) {
    completed.computeIfAbsent(session, id -> new LinkedHashSet<>()).add(turn);
  }

  public synchronized List<Session> allSessions() {
    return List.copyOf(sessions.values());
  }
}
