package io.github.oryxlabs.oryxos.core.schedule;

import io.github.oryxlabs.oryxos.core.agent.*;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.runtime.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * One lock per configured schedule, shared by automatic and manual triggers until actual cleanup.
 */
public final class ScheduleCoordinator implements AutoCloseable {
  public static final String SYSTEM_USER = "oryxos-scheduler";

  public record Attempt(ScheduleEvent event, AgentService.Handle execution) {}

  public record View(
      ScheduleState state, ScheduleDefinition definition, Instant nextRunAt, String lastStatus) {}

  private final Map<String, ScheduleDefinition> definitions;
  private final ScheduleStore store;
  private final ExecutionStore executions;
  private final Supplier<AgentService> agents;
  private final RuntimeGate gate;
  private final TimeSource time;
  private final ScheduleTimer timer;
  private final boolean automatic;
  private final Set<String> busy = ConcurrentHashMap.newKeySet();
  private final Map<String, java.util.concurrent.locks.ReentrantLock> admission =
      new ConcurrentHashMap<>();
  private final Map<String, ScheduleTimer.Registration> registrations = new HashMap<>();
  private final Map<String, Object> generations = new ConcurrentHashMap<>();
  private volatile boolean closed;

  public ScheduleCoordinator(
      List<ScheduleDefinition> definitions,
      ScheduleStore store,
      ExecutionStore executions,
      Supplier<AgentService> agents,
      RuntimeGate gate,
      TimeSource time,
      ScheduleTimer timer,
      boolean automatic) {
    var byId = new LinkedHashMap<String, ScheduleDefinition>();
    for (var d : definitions)
      if (byId.putIfAbsent(d.id(), d) != null)
        throw new IllegalArgumentException("Duplicate schedule id");
    this.definitions = Collections.unmodifiableMap(byId);
    this.store = store;
    this.executions = executions;
    this.agents = agents;
    this.gate = gate;
    this.time = time;
    this.timer = timer;
    this.automatic = automatic;
  }

  /** Called at startup and while recovery still holds admission closed after rebuilding storage. */
  public void initialize() {
    store.reconcile(List.copyOf(definitions.values()), time.instant());
    refreshTimers();
  }

  public List<View> list() {
    return store.states().stream()
        .map(
            s -> {
              var last = s.lastExecutionId() == null ? null : executions.find(s.lastExecutionId());
              var history = store.history(s.id(), 1, null);
              String status = history.isEmpty() ? null : history.getFirst().status().name();
              if (!history.isEmpty() && history.getFirst().executionId() != null) {
                var outcome = executions.find(history.getFirst().executionId());
                if (outcome != null) status = outcome.status().name();
              } else if (history.isEmpty() && last != null) status = last.status().name();
              return new View(s, definitions.get(s.id()), nextRunAt(s.id()), status);
            })
        .toList();
  }

  public List<ScheduleEvent> history(String id, int limit, UUID beforeId) {
    if (store.find(id) == null) throw new OryxError("NOT_FOUND", "Schedule history does not exist");
    return store.history(id, limit, beforeId);
  }

  public ScheduleState setEnabled(String id, boolean enabled) {
    requireDefinition(id);
    var lock =
        admission.computeIfAbsent(id, ignored -> new java.util.concurrent.locks.ReentrantLock());
    lock.lock();
    try {
      var state = store.setEnabled(id, enabled, time.instant());
      refreshTimers(); // A failed commit cannot update timer registration or be reported as
      // success.
      return state;
    } finally {
      lock.unlock();
    }
  }

  private void refreshTimers() {
    synchronized (registrations) {
      if (closed) return;
      // Read fresh state after completed control writes; this read does not invoke fault callbacks.
      var states = store.states();
      for (var s : states) {
        boolean wanted =
            automatic && s.enabled() && s.configuredPresent() && definitions.containsKey(s.id());
        if (!wanted) {
          generations.remove(s.id());
          var old = registrations.remove(s.id());
          if (old != null) old.close();
        } else if (!registrations.containsKey(s.id())) {
          var d = definitions.get(s.id());
          Object generation = new Object();
          generations.put(s.id(), generation);
          registrations.put(
              s.id(),
              timer.register(
                  d,
                  at ->
                      trigger(
                          d.id(),
                          "scheduler",
                          SYSTEM_USER,
                          ProcessRequest.Trigger.SCHEDULED,
                          at,
                          generation)));
        }
      }
    }
  }

  private Instant nextRunAt(String id) {
    synchronized (registrations) {
      var r = registrations.get(id);
      return r == null ? null : r.nextRunAt();
    }
  }

  public Attempt scheduled(String id, Instant scheduledFor) {
    return trigger(id, "scheduler", SYSTEM_USER, ProcessRequest.Trigger.SCHEDULED, scheduledFor);
  }

  public Attempt manual(String id, String channel, String user) {
    return manual(id, channel, user, time.instant(), time.nanoTime());
  }

  public Attempt manual(
      String id, String channel, String user, Instant entered, long enteredNanos) {
    return manual(id, channel, user, entered, enteredNanos, h -> {});
  }

  public Attempt manual(
      String id,
      String channel,
      String user,
      Instant entered,
      long enteredNanos,
      java.util.function.Consumer<AgentService.Handle> observer) {
    if (!Set.of("cli", "http").contains(channel == null ? "" : channel)
        || user == null
        || user.isBlank())
      throw new OryxError(
          "INVALID_INPUT", "Manual schedule execution requires cli/http and a user identifier");
    return trigger(
        id,
        channel,
        user,
        ProcessRequest.Trigger.MANUAL,
        null,
        null,
        entered,
        enteredNanos,
        observer);
  }

  private Attempt trigger(
      String id, String channel, String user, ProcessRequest.Trigger kind, Instant at) {
    return trigger(id, channel, user, kind, at, null);
  }

  private Attempt trigger(
      String id,
      String channel,
      String user,
      ProcessRequest.Trigger kind,
      Instant at,
      Object generation) {
    return trigger(id, channel, user, kind, at, generation, time.instant(), time.nanoTime());
  }

  private Attempt trigger(
      String id,
      String channel,
      String user,
      ProcessRequest.Trigger kind,
      Instant at,
      Object generation,
      Instant entered,
      long enteredNanos) {
    return trigger(id, channel, user, kind, at, generation, entered, enteredNanos, h -> {});
  }

  private Attempt trigger(
      String id,
      String channel,
      String user,
      ProcessRequest.Trigger kind,
      Instant at,
      Object generation,
      Instant entered,
      long enteredNanos,
      java.util.function.Consumer<AgentService.Handle> observer) {
    requireDefinition(id);
    if (generation != null && generations.get(id) != generation) return null;
    var lock =
        admission.computeIfAbsent(id, ignored -> new java.util.concurrent.locks.ReentrantLock());
    if (!lock.tryLock())
      return skipped(id, kind, at, ScheduleEvent.Status.SKIPPED_OVERLAP, "SCHEDULE_BUSY");
    try {
      // A cancelled callback may already have left the timer thread when disable/re-enable occurs.
      if (generation != null && generations.get(id) != generation) return null;
      return admit(id, channel, user, kind, at, entered, enteredNanos, observer);
    } finally {
      lock.unlock();
    }
  }

  private Attempt admit(
      String id,
      String channel,
      String user,
      ProcessRequest.Trigger kind,
      Instant at,
      Instant entered,
      long enteredNanos,
      java.util.function.Consumer<AgentService.Handle> observer) {
    var definition = requireDefinition(id);
    if (closed) throw new OryxError("EXECUTION_PAUSED", "Scheduler is closed");
    ScheduleState state;
    try {
      state = store.find(id);
    } catch (RuntimeException error) {
      gate.recordingFailure(null);
      throw error;
    }
    if (state == null || !state.configuredPresent())
      throw new OryxError("NOT_FOUND", "Schedule is not configured");
    if (kind == ProcessRequest.Trigger.SCHEDULED && !state.enabled()) return null;
    if (gate.state() != RuntimeGate.State.READY)
      return skipped(id, kind, at, ScheduleEvent.Status.SKIPPED_PAUSED, "EXECUTION_PAUSED");
    if (!busy.add(id))
      return skipped(id, kind, at, ScheduleEvent.Status.SKIPPED_OVERLAP, "SCHEDULE_BUSY");
    var recorded = new AtomicReference<ScheduleEvent>();
    try {
      var request =
          new ProcessRequest(
              channel, user, definition.agentName(), null, definition.message(), kind, id);
      var handle =
          agents
              .get()
              .start(
                  request,
                  snapshot -> {
                    var event =
                        event(id, kind, at, ScheduleEvent.Status.DISPATCHED, null, snapshot.id());
                    store.record(event);
                    recorded.set(event);
                  },
                  () -> busy.remove(id),
                  entered,
                  enteredNanos,
                  observer);
      if (recorded.get() == null)
        throw new OryxError(
            "RECORDING_FAILURE", "Schedule dispatch was not recorded; workspace paused");
      return new Attempt(recorded.get(), handle);
    } catch (RuntimeException error) {
      busy.remove(id);
      if (error instanceof OryxError e && e.code().equals("RECORDING_FAILURE")) throw error;
      String code = error instanceof OryxError e ? e.code() : "EXECUTION_FAILED";
      return skipped(
          id,
          kind,
          at,
          code.equals("EXECUTION_PAUSED")
              ? ScheduleEvent.Status.SKIPPED_PAUSED
              : ScheduleEvent.Status.FAILED,
          code);
    }
  }

  private Attempt skipped(
      String id,
      ProcessRequest.Trigger kind,
      Instant at,
      ScheduleEvent.Status status,
      String reason) {
    var event = event(id, kind, at, status, reason, null);
    store.record(event);
    return new Attempt(event, null);
  }

  private ScheduleEvent event(
      String id,
      ProcessRequest.Trigger kind,
      Instant at,
      ScheduleEvent.Status status,
      String reason,
      UUID execution) {
    return new ScheduleEvent(
        UUID.randomUUID(), id, execution, kind, at, status, reason, time.instant());
  }

  private ScheduleDefinition requireDefinition(String id) {
    var d = definitions.get(id);
    if (d == null) throw new OryxError("NOT_FOUND", "Schedule has no valid file definition");
    return d;
  }

  public void close() {
    closed = true;
    synchronized (registrations) {
      generations.clear();
      registrations.values().forEach(ScheduleTimer.Registration::close);
      registrations.clear();
    }
  }
}
