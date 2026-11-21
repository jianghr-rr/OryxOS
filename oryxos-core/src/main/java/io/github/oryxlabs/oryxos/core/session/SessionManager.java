package io.github.oryxlabs.oryxos.core.session;

import io.github.oryxlabs.oryxos.core.agent.AgentProfile;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.RecordingScope;
import io.github.oryxlabs.oryxos.core.model.ChatMessage;
import io.github.oryxlabs.oryxos.core.runtime.RuntimeTiming;
import io.github.oryxlabs.oryxos.core.runtime.TimeSource;
import io.github.oryxlabs.oryxos.core.runtime.Uuids;
import io.github.oryxlabs.oryxos.core.tool.ToolResult;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class SessionManager implements SessionHistoryPort {
  private static final int MAX_HOT_SESSIONS = 1024;
  private static final int MAX_CACHED_TURNS_PER_SESSION = 32;

  private final SessionStore store;
  private final TimeSource time;
  private final Map<UUID, Lease> occupied = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, HotSession> hotSessions = new ConcurrentHashMap<>();
  private final AtomicLong mutationEpoch = new AtomicLong();
  private final ThreadLocal<LookupHint> lookupHint = new ThreadLocal<>();

  private record LookupHint(UUID id, Session session, long epoch, long atNanos) {}

  private record CompletedTurn(UUID turnId, List<ChatMessage> messages) {}

  private static final class HotSession {
    final ReentrantLock lock = new ReentrantLock();
    long nextSequence = -1;
    boolean fullHistoryKnown;
    int loadedMaxTurns;
    final ArrayDeque<CompletedTurn> completedTurns = new ArrayDeque<>(8);
  }

  // Reference counts include waiters; remove idle locks without racing a new admission.
  private static final class SessionLock {
    final ReentrantLock lock = new ReentrantLock();
    int users;
  }

  private final ConcurrentHashMap<UUID, SessionLock> lifecycle = new ConcurrentHashMap<>();

  private RuntimeTiming.Scope lock(UUID id) {
    var entry =
        lifecycle.compute(
            id,
            (key, existing) -> {
              var value = existing == null ? new SessionLock() : existing;
              value.users++;
              return value;
            });
    RuntimeTiming.run("session.lock", entry.lock::lock);
    return () -> {
      entry.lock.unlock();
      lifecycle.compute(
          id,
          (key, existing) -> {
            if (--entry.users == 0) return null;
            return entry;
          });
    };
  }

  public SessionManager(SessionStore store, TimeSource time) {
    this.store = store;
    this.time = time;
  }

  /** Current durable metadata; acquiring a lease still performs identity and model checks. */
  public Session lookup(UUID id) {
    long epoch = mutationEpoch.get();
    var session = RuntimeTiming.call("session.find", () -> store.find(id));
    if (session != null) lookupHint.set(new LookupHint(id, session, epoch, System.nanoTime()));
    else lookupHint.remove();
    return session;
  }

  public Lease acquire(
      UUID id, String channel, String user, AgentProfile agent, Session.Kind kind) {
    return guarded(id, channel, user, agent, kind, false);
  }

  public Lease acquireForExecution(
      UUID id, String channel, String user, AgentProfile agent, Session.Kind kind) {
    return guarded(id, channel, user, agent, kind, store.atomicBegin());
  }

  private Lease guarded(
      UUID id,
      String channel,
      String user,
      AgentProfile agent,
      Session.Kind kind,
      boolean deferred) {
    UUID key = id == null ? Uuids.v4() : id;
    try (var ignored = lock(key)) {
      return acquire(key, channel, user, agent, kind, deferred, id == null);
    }
  }

  private Lease acquire(
      UUID id,
      String channel,
      String user,
      AgentProfile agent,
      Session.Kind kind,
      boolean deferred,
      boolean fresh) {
    var hint = lookupHint.get();
    lookupHint.remove();
    Session session;
    try {
      if (fresh) {
        session =
            new Session(
                id,
                channel,
                user,
                agent.name(),
                agent.providerName(),
                agent.model(),
                kind,
                Session.Status.ACTIVE,
                time.instant());
        if (!deferred) {
          store.create(session);
          markFreshHotSession(session.id(), 1L);
        }
      } else {
        if (hint != null
            && hint.id().equals(id)
            && hint.epoch() == mutationEpoch.get()
            && System.nanoTime() - hint.atNanos() <= 50_000_000L) {
          session = hint.session();
        } else {
          session = RuntimeTiming.call("session.find", () -> store.find(id));
        }
        if (session == null) throw new OryxError("NOT_FOUND", "Session does not exist");
        if (!session.channel().equals(channel)
            || !session.userId().equals(user)
            || !session.agentName().equals(agent.name()))
          throw new OryxError(
              "SESSION_IDENTITY_MISMATCH", "Session channel, user and Agent must match");
        if (!session.providerName().equals(agent.providerName())
            || !session.model().equals(agent.model()))
          throw new OryxError(
              "SESSION_MODEL_CHANGED", "Provider/model snapshot changed; create a new session");
        if (session.status() != Session.Status.ACTIVE)
          throw new OryxError("SESSION_ARCHIVED", "Session is archived");
      }
      var lease = new Lease(session, fresh && deferred);
      if (occupied.putIfAbsent(session.id(), lease) != null)
        throw new OryxError("SESSION_BUSY", "Session already has an in-flight execution");
      return lease;
    } catch (OryxError e) {
      throw e;
    } catch (RuntimeException e) {
      throw recording();
    }
  }

  public void archive(UUID id, String channel, String user, String agent) {
    try (var ignored = lock(id)) {
      var session = RuntimeTiming.call("session.find", () -> store.find(id));
      if (session == null) throw new OryxError("NOT_FOUND", "Session does not exist");
      if (!session.channel().equals(channel)
          || !session.userId().equals(user)
          || !session.agentName().equals(agent))
        throw new OryxError("SESSION_IDENTITY_MISMATCH", "Session identity must match");
      if (occupied.containsKey(id))
        throw new OryxError("SESSION_BUSY", "Session has an in-flight execution");
      if (session.status() != Session.Status.ARCHIVED) {
        mutationEpoch.incrementAndGet();
        hotSessions.remove(id);
        store.archive(id, time.instant());
      }
    } catch (OryxError e) {
      throw e;
    } catch (RuntimeException e) {
      hotSessions.remove(id);
      throw recording();
    }
  }

  private void evictIdleHotSessionsIfNeeded(UUID keep) {
    if (hotSessions.size() <= MAX_HOT_SESSIONS) return;
    for (var key : hotSessions.keySet()) {
      if (!key.equals(keep) && !occupied.containsKey(key)) {
        hotSessions.remove(key);
        if (hotSessions.size() <= MAX_HOT_SESSIONS) break;
      }
    }
  }

  private HotSession hotSessionForWrite(UUID id) {
    evictIdleHotSessionsIfNeeded(id);
    return hotSessions.computeIfAbsent(id, k -> new HotSession());
  }

  private void markFreshHotSession(UUID id, long nextSeq) {
    var hot = hotSessionForWrite(id);
    hot.lock.lock();
    try {
      hot.nextSequence = nextSeq;
      hot.fullHistoryKnown = true;
      hot.loadedMaxTurns = Integer.MAX_VALUE;
      hot.completedTurns.clear();
    } finally {
      hot.lock.unlock();
    }
  }

  private void updateHotSequence(UUID id, long minNextSeq) {
    var hot = hotSessionForWrite(id);
    hot.lock.lock();
    try {
      if (hot.nextSequence < minNextSeq) hot.nextSequence = minNextSeq;
    } finally {
      hot.lock.unlock();
    }
  }

  private static List<CompletedTurn> splitCompletedTurns(List<ChatMessage> recent) {
    if (recent.isEmpty()) return List.of();
    var result = new ArrayList<CompletedTurn>();
    List<ChatMessage> bucket = null;
    for (var message : recent) {
      if (message.role() == ChatMessage.Role.USER || bucket == null) {
        if (bucket != null) result.add(new CompletedTurn(Uuids.v4(), List.copyOf(bucket)));
        bucket = new ArrayList<>(4);
      }
      bucket.add(message);
    }
    if (bucket != null) result.add(new CompletedTurn(Uuids.v4(), List.copyOf(bucket)));
    return result;
  }

  public History history(UUID sessionId, UUID currentTurn, int maxTurns) {
    try {
      var lease = occupied.get(sessionId);
      if (lease != null) return lease.historyForActiveTurn(currentTurn, maxTurns);
      return RuntimeTiming.call(
          "session.history", () -> store.history(sessionId, currentTurn, maxTurns));
    } catch (OryxError e) {
      throw e;
    } catch (RuntimeException e) {
      throw recording();
    }
  }

  public final class Lease implements AutoCloseable {
    private record PendingMessage(UUID turnId, ChatMessage message) {}

    private final ReentrantLock leaseLock = new ReentrantLock();
    private final Session session;
    private boolean closed;
    private boolean pending;
    private long nextSequence;
    private UUID trackedTurn;
    private List<ChatMessage> trackedMessages;
    private final List<PendingMessage> pendingMessages = new ArrayList<>(4);
    private int cachedMaxTurns = -1;
    private List<ChatMessage> cachedRecentTurns;

    private Lease(Session session, boolean pending) {
      this.session = session;
      this.pending = pending;
      nextSequence = pending ? 1 : -1;
    }

    private long nextSequence() {
      if (nextSequence < 0) {
        var hot = hotSessions.get(session.id());
        if (hot != null) {
          hot.lock.lock();
          try {
            if (hot.nextSequence >= 1) {
              nextSequence = hot.nextSequence;
              return nextSequence;
            }
          } finally {
            hot.lock.unlock();
          }
        }
        long loaded =
            RuntimeTiming.call("session.sequence", () -> store.lastSequence(session.id())) + 1;
        nextSequence = loaded;
        updateHotSequence(session.id(), loaded);
      }
      return nextSequence;
    }

    public boolean isPending() {
      return pending;
    }

    public void begin(
        io.github.oryxlabs.oryxos.core.execution.Execution.Snapshot execution,
        String content,
        io.github.oryxlabs.oryxos.core.execution.ExecutionStore executions) {
      boolean wasPending = pending;
      long seq = nextSequence();
      var userMessage = ChatMessage.user(content);
      try {
        store.begin(
            session,
            wasPending,
            execution,
            new SessionMessage(
                Uuids.v4(),
                session.id(),
                execution.id(),
                execution.turnId(),
                seq,
                userMessage,
                null,
                time.instant()),
            executions,
            () -> {
              if (wasPending) markFreshHotSession(session.id(), seq + 1);
              else updateHotSequence(session.id(), seq + 1);
            },
            this::invalidateCache);
      } catch (RuntimeException e) {
        invalidateCache();
        throw e;
      }
      leaseLock.lock();
      try {
        pending = false;
        nextSequence = seq + 1;
        trackedTurn = execution.turnId();
        trackedMessages = new ArrayList<>(4);
        trackedMessages.add(userMessage);
        pendingMessages.clear();
        cachedMaxTurns = -1;
        cachedRecentTurns = null;
      } finally {
        leaseLock.unlock();
      }
    }

    public Session session() {
      return session;
    }

    public void append(UUID execution, UUID turn, ChatMessage message, ToolResult.Outcome outcome) {
      if (closed) throw new IllegalStateException("Closed session lease");
      try {
        long seq = nextSequence();
        store.append(
            new SessionMessage(
                Uuids.v4(), session.id(), execution, turn, seq, message, outcome, time.instant()));
        var pendingEntry = new PendingMessage(turn, message);
        // Advance sequence optimistically across multi-append coalesced windows, rolling back
        // immediately if the buffered transaction rolls back or is discarded.
        boolean registered;
        leaseLock.lock();
        try {
          registered =
              RecordingScope.onCoalescedOutcome(
                  () -> {
                    leaseLock.lock();
                    try {
                      pendingMessages.remove(pendingEntry);
                      if (trackedMessages != null && Objects.equals(trackedTurn, turn))
                        trackedMessages.add(message);
                    } finally {
                      leaseLock.unlock();
                    }
                    updateHotSequence(session.id(), seq + 1);
                  },
                  () -> {
                    leaseLock.lock();
                    try {
                      pendingMessages.remove(pendingEntry);
                      if (nextSequence > seq) nextSequence = seq;
                    } finally {
                      leaseLock.unlock();
                    }
                    invalidateCache();
                  });
          nextSequence = seq + 1;
          if (registered) {
            pendingMessages.add(pendingEntry);
          } else {
            if (trackedMessages != null && Objects.equals(trackedTurn, turn)) {
              trackedMessages.add(message);
            }
          }
        } finally {
          leaseLock.unlock();
        }
        if (!registered) updateHotSequence(session.id(), seq + 1);
      } catch (RuntimeException e) {
        invalidateCache();
        throw recording();
      }
    }

    private List<ChatMessage> snapshotTrackedTurnLocked(UUID turn) {
      if (trackedMessages == null || !Objects.equals(trackedTurn, turn)) return null;
      if (pendingMessages.isEmpty()) return List.copyOf(trackedMessages);
      var combined = new ArrayList<ChatMessage>(trackedMessages.size() + pendingMessages.size());
      combined.addAll(trackedMessages);
      for (var entry : pendingMessages) {
        if (Objects.equals(entry.turnId(), turn)) combined.add(entry.message());
      }
      return combined;
    }

    private History historyForActiveTurn(UUID currentTurn, int maxTurns) {
      if (maxTurns < 1) throw new IllegalArgumentException("Positive turn limit required");
      leaseLock.lock();
      try {
        if (cachedRecentTurns != null && cachedMaxTurns == maxTurns) {
          var active = snapshotTrackedTurnLocked(currentTurn);
          if (active != null) {
            return RuntimeTiming.call(
                "session.history",
                () -> {
                  HistorySelector.paired(active);
                  return new History(cachedRecentTurns, active);
                });
          }
        }
        var active = snapshotTrackedTurnLocked(currentTurn);
        if (active != null) {
          var hot = hotSessions.get(session.id());
          if (hot != null) {
            List<ChatMessage> hotRecent = null;
            hot.lock.lock();
            try {
              if (hot.fullHistoryKnown
                  || (hot.loadedMaxTurns > 0 && maxTurns <= hot.loadedMaxTurns)) {
                int skip = Math.max(0, hot.completedTurns.size() - maxTurns);
                var list =
                    new ArrayList<ChatMessage>(Math.min(maxTurns, hot.completedTurns.size()) * 4);
                int idx = 0;
                for (var t : hot.completedTurns) {
                  if (idx++ >= skip) list.addAll(t.messages());
                }
                hotRecent = List.copyOf(list);
              }
            } finally {
              hot.lock.unlock();
            }
            if (hotRecent != null) {
              cachedMaxTurns = maxTurns;
              cachedRecentTurns = hotRecent;
              final var recentSnapshot = hotRecent;
              return RuntimeTiming.call(
                  "session.history",
                  () -> {
                    HistorySelector.paired(active);
                    return new History(recentSnapshot, active);
                  });
            }
          }
        }
      } finally {
        leaseLock.unlock();
      }
      History loaded;
      try {
        loaded =
            RuntimeTiming.call(
                "session.history", () -> store.history(session.id(), currentTurn, maxTurns));
      } catch (RuntimeException e) {
        invalidateCache();
        throw e;
      }
      var splitTurns = splitCompletedTurns(loaded.recent());
      var hot = hotSessionForWrite(session.id());
      hot.lock.lock();
      try {
        hot.completedTurns.clear();
        int keep = Math.min(splitTurns.size(), MAX_CACHED_TURNS_PER_SESSION);
        for (int i = splitTurns.size() - keep; i < splitTurns.size(); i++) {
          hot.completedTurns.addLast(splitTurns.get(i));
        }
        hot.fullHistoryKnown = splitTurns.size() < maxTurns;
        hot.loadedMaxTurns = Math.min(maxTurns, MAX_CACHED_TURNS_PER_SESSION);
      } finally {
        hot.lock.unlock();
      }
      leaseLock.lock();
      try {
        if (trackedMessages != null && Objects.equals(trackedTurn, currentTurn)) {
          cachedMaxTurns = maxTurns;
          cachedRecentTurns = loaded.recent();
        }
      } finally {
        leaseLock.unlock();
      }
      return loaded;
    }

    public boolean verifyPairedInMemory(UUID turn) {
      List<ChatMessage> snapshot;
      leaseLock.lock();
      try {
        snapshot = snapshotTrackedTurnLocked(turn);
      } finally {
        leaseLock.unlock();
      }
      if (snapshot == null) return false;
      HistorySelector.paired(snapshot);
      return true;
    }

    public void recordTurnCompleted(UUID turn) {
      List<ChatMessage> completedMessages;
      leaseLock.lock();
      try {
        if (trackedMessages == null || !Objects.equals(trackedTurn, turn)) return;
        completedMessages = List.copyOf(trackedMessages);
      } finally {
        leaseLock.unlock();
      }
      var hot = hotSessionForWrite(session.id());
      hot.lock.lock();
      try {
        for (var existing : hot.completedTurns) {
          if (Objects.equals(existing.turnId(), turn)) return;
        }
        hot.completedTurns.addLast(new CompletedTurn(turn, completedMessages));
        while (hot.completedTurns.size() > MAX_CACHED_TURNS_PER_SESSION) {
          hot.completedTurns.removeFirst();
          hot.fullHistoryKnown = false;
          hot.loadedMaxTurns = Math.min(hot.loadedMaxTurns, MAX_CACHED_TURNS_PER_SESSION);
        }
      } finally {
        hot.lock.unlock();
      }
    }

    public void invalidateCache() {
      hotSessions.remove(session.id());
      leaseLock.lock();
      try {
        cachedMaxTurns = -1;
        cachedRecentTurns = null;
      } finally {
        leaseLock.unlock();
      }
    }

    public void finish(UUID turn) {
      try {
        if (verifyPairedInMemory(turn)) {
          RuntimeTiming.run("session.finish", () -> store.finishTurnVerified(session.id(), turn));
          if (!RecordingScope.onCoalescedOutcome(
              () -> recordTurnCompleted(turn), this::invalidateCache)) {
            recordTurnCompleted(turn);
          }
          return;
        }
        HistorySelector.paired(
            RuntimeTiming.call("session.turn", () -> store.turnMessages(session.id(), turn))
                .stream()
                .map(SessionMessage::message)
                .toList());
        RuntimeTiming.run("session.finish", () -> store.finishTurn(session.id(), turn));
      } catch (OryxError e) {
        invalidateCache();
        throw e;
      } catch (RuntimeException e) {
        invalidateCache();
        throw recording();
      }
    }

    public void close() {
      try (var ignored = lock(session.id())) {
        if (closed) return;
        try {
          if (!pending && session.kind() == Session.Kind.ONE_SHOT) {
            mutationEpoch.incrementAndGet();
            hotSessions.remove(session.id());
            store.archive(session.id(), time.instant());
          }
        } catch (RuntimeException e) {
          hotSessions.remove(session.id());
          throw recording();
        } finally {
          closed = true;
          occupied.remove(session.id(), this);
        }
      }
    }
  }

  private static OryxError recording() {
    return new OryxError("RECORDING_FAILURE", "Session storage operation failed");
  }
}
