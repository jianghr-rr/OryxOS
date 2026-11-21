package io.github.oryxlabs.oryxos.storage.schedule;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ProcessRequest;
import io.github.oryxlabs.oryxos.core.schedule.*;
import io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator;
import java.time.Instant;
import java.util.*;

public final class JpaScheduleStore implements ScheduleStore {
  private static final java.time.format.DateTimeFormatter STORED_INSTANT =
      new java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter();
  private final WorkspaceWriteCoordinator writes;

  public JpaScheduleStore(WorkspaceWriteCoordinator writes) {
    this.writes = writes;
  }

  public void reconcile(List<ScheduleDefinition> definitions, Instant now) {
    writes.write(em -> {
      var existing =
          em.createQuery("from ScheduleStateEntity", ScheduleStateEntity.class).getResultList();
      var current = new HashMap<String, ScheduleStateEntity>();
      for (var e : existing) {
        current.put(e.id, e);
        e.configuredPresent = 0L;
        e.updatedAt = text(now);
      }
      for (var d : definitions) {
        var e = current.get(d.id());
        boolean fresh = e == null;
        if (fresh) {
          e = new ScheduleStateEntity();
          e.id = d.id();
          e.agentName = d.agentName();
          e.enabled = d.initiallyEnabled() ? 1L : 0L;
        }
        e.definitionHash = d.definitionHash();
        e.configuredPresent = 1L;
        e.updatedAt = text(now);
        if (fresh) em.persist(e);
      }
      return null;
    });
  }

  public List<ScheduleState> states() {
    return writes.read(em ->
        em
            .createQuery("from ScheduleStateEntity order by id", ScheduleStateEntity.class)
            .getResultList()
            .stream()
            .map(JpaScheduleStore::state)
            .toList());
  }

  public ScheduleState find(String id) {
    return writes.read(em -> state(em.find(ScheduleStateEntity.class, id)));
  }

  public ScheduleState setEnabled(String id, boolean enabled, Instant now) {
    return writes.write(em -> {
      var e = em.find(ScheduleStateEntity.class, id);
      if (e == null) throw new IllegalStateException("Schedule state is missing");
      e.enabled = enabled ? 1L : 0L;
      e.updatedAt = text(now);
      return state(e);
    });
  }

  public void record(ScheduleEvent event) {
    writes.write(em -> {
      var e = new ScheduleEventEntity();
      e.id = event.id().toString();
      e.scheduleId = event.scheduleId();
      e.executionId = event.executionId() == null ? null : event.executionId().toString();
      e.triggerKind = event.triggerKind().name();
      e.scheduledFor = text(event.scheduledFor());
      e.status = event.status().name();
      e.reason = event.reason();
      e.createdAt = text(event.createdAt());
      em.persist(e);
      if (e.executionId != null) {
        var s = em.find(ScheduleStateEntity.class, e.scheduleId);
        s.lastExecutionId = e.executionId;
        s.updatedAt = e.createdAt;
      }
      return null;
    });
  }

  public List<ScheduleEvent> history(String id, int limit, UUID beforeId) {
    if (limit < 1 || limit > 100)
      throw new OryxError("INVALID_INPUT", "History limit must be 1–100");
    var before =
        beforeId == null
            ? null
            : writes.read(em -> {
              var found = em.find(ScheduleEventEntity.class, beforeId.toString());
              return found == null ? null : event(found);
            });
    if (beforeId != null && (before == null || !before.scheduleId().equals(id)))
      throw new OryxError("INVALID_INPUT", "History cursor does not belong to this schedule");
    return writes.read(em -> {
      var q =
          em.createQuery(
                  "from ScheduleEventEntity e where e.scheduleId=:id"
                      + (before == null
                          ? ""
                          : " and (e.createdAt<:before or (e.createdAt=:before and"
                              + " e.id<:cursor))")
                      + " order by e.createdAt desc,e.id desc",
                  ScheduleEventEntity.class)
              .setParameter("id", id);
      if (before != null) {
        q.setParameter("before", text(before.createdAt()));
        q.setParameter("cursor", before.id().toString());
      }
      return q.setMaxResults(limit).getResultList().stream().map(JpaScheduleStore::event).toList();
    });
  }

  private static ScheduleState state(ScheduleStateEntity e) {
    return e == null
        ? null
        : new ScheduleState(
            e.id,
            e.agentName,
            e.definitionHash,
            e.enabled == 1,
            e.configuredPresent == 1,
            e.lastExecutionId == null ? null : UUID.fromString(e.lastExecutionId),
            Instant.parse(e.updatedAt));
  }

  private static ScheduleEvent event(ScheduleEventEntity e) {
    return new ScheduleEvent(
        UUID.fromString(e.id),
        e.scheduleId,
        e.executionId == null ? null : UUID.fromString(e.executionId),
        ProcessRequest.Trigger.valueOf(e.triggerKind),
        e.scheduledFor == null ? null : Instant.parse(e.scheduledFor),
        ScheduleEvent.Status.valueOf(e.status),
        e.reason,
        Instant.parse(e.createdAt));
  }

  private static String text(Instant value) {
    return value == null ? null : STORED_INSTANT.format(value);
  }
}
