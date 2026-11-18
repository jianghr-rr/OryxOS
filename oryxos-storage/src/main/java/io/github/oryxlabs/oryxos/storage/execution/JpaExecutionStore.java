package io.github.oryxlabs.oryxos.storage.execution;

import io.github.oryxlabs.oryxos.core.execution.*;
import io.github.oryxlabs.oryxos.core.model.ToolNameMap;
import io.github.oryxlabs.oryxos.storage.transaction.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;

public final class JpaExecutionStore implements ExecutionStore {
  private final WorkspaceWriteCoordinator writes;

  public JpaExecutionStore(WorkspaceWriteCoordinator writes) {
    this.writes = writes;
  }

  public RecordingScope.Coalescer coalescer() {
    return writes.coalescer();
  }

  public void save(Execution.Snapshot value) {
    var prepared = prepare(value);
    writes.deferOrWrite(em -> put(em, prepared), null, null);
  }

  @Override
  public void completeTurn(Execution.Snapshot v) {
    Object[] values =
        new Object[] {
          text(v.finishedAt()),
          v.status().name(),
          v.stopReason(),
          v.recordingStatus().name(),
          v.inFlightCount(),
          v.finalContent(),
          v.errorCode(),
          v.hasToolErrors() ? 1 : 0,
          v.hasUnresolvedToolErrors() ? 1 : 0,
          v.id().toString(),
          v.sessionId().toString(),
          v.turnId().toString(),
          v.status().name()
        };
    writes.deferOrWrite(
        em -> {
          int changed = StoredSql.update(em, COMPLETE_TURN, values);
          if (changed != 1)
            throw new IllegalStateException("Execution identity/terminal state is immutable");
        },
        null,
        null);
  }

  public Execution.Snapshot find(UUID id) {
    return writes.read(
        em -> {
          var e = em.find(ExecutionEntity.class, id.toString());
          return e == null ? null : snapshot(e);
        });
  }

  public List<Execution.Snapshot> list(UUID sessionId, int limit) {
    return writes.read(
        em -> {
          var query =
              em.createQuery(
                  "select e from ExecutionEntity e"
                      + (sessionId == null ? "" : " where e.sessionId=:session")
                      + " order by e.startedAt desc,e.id",
                  ExecutionEntity.class);
          if (sessionId != null) query.setParameter("session", sessionId.toString());
          return query.setMaxResults(Math.max(1, Math.min(100, limit))).getResultList().stream()
              .map(JpaExecutionStore::snapshot)
              .toList();
        });
  }

  public static final class Prepared {
    private final Object[] values;

    private Prepared(Object[] values) {
      this.values = values;
    }
  }

  public static Prepared prepare(Execution.Snapshot v) {
    return new Prepared(
        new Object[] {
          v.id().toString(),
          v.sessionId().toString(),
          v.turnId().toString(),
          v.agentName(),
          v.channel(),
          v.userId(),
          v.triggerType().name(),
          v.scheduleId(),
          v.configRevision(),
          v.startedAt().toString(),
          v.deadlineAt().toString(),
          text(v.finishedAt()),
          v.status().name(),
          v.stopReason(),
          v.recordingStatus().name(),
          v.inFlightCount(),
          v.finalContent(),
          v.errorCode(),
          v.hasToolErrors() ? 1 : 0,
          v.hasUnresolvedToolErrors() ? 1 : 0,
          v.timezone().getId(),
          StoredJson.write(v.toolNames())
        });
  }

  public static void put(EntityManager em, Prepared prepared) {
    int changed = StoredSql.update(em, PUT, prepared.values);
    if (changed != 1)
      throw new IllegalStateException("Execution identity/terminal state is immutable");
  }

  private static final String PUT =
      StoredSql.upsert(
          "executions",
          "id,session_id,turn_id,agent_name,channel,user_id,trigger_type,schedule_id,config_revision,started_at,deadline_at,finished_at,status,stop_reason,recording_status,in_flight_count,final_content,error_code,has_tool_errors,has_unresolved_tool_errors,timezone,tool_name_map_json",
          "executions.session_id=excluded.session_id AND executions.turn_id=excluded.turn_id AND"
              + " (executions.status='RUNNING' OR excluded.status<>'RUNNING')");
  private static final String COMPLETE_TURN =
      "UPDATE executions SET finished_at=?,status=?,stop_reason=?,recording_status=?,"
          + "in_flight_count=?,final_content=?,error_code=?,has_tool_errors=?,"
          + "has_unresolved_tool_errors=?,turn_complete=1 WHERE id=? AND session_id=? AND turn_id=?"
          + " AND (status='RUNNING' OR ?<>'RUNNING')";

  public static Execution.Snapshot snapshot(ExecutionEntity e) {
    return new Execution.Snapshot(
        UUID.fromString(e.id),
        UUID.fromString(e.sessionId),
        UUID.fromString(e.turnId),
        e.agentName,
        e.channel,
        e.userId,
        ProcessRequest.Trigger.valueOf(e.triggerType),
        e.scheduleId,
        e.configRevision,
        Instant.parse(e.startedAt),
        Instant.parse(e.deadlineAt),
        instant(e.finishedAt),
        Execution.Status.valueOf(e.status),
        e.stopReason,
        Execution.RecordingStatus.valueOf(e.recordingStatus),
        e.inFlightCount.intValue(),
        e.finalContent,
        e.errorCode,
        e.hasToolErrors == 1,
        e.hasUnresolvedToolErrors == 1,
        ZoneId.of(e.timezone),
        StoredJson.read(e.toolNameMapJson, ToolNameMap.class));
  }

  private static String text(Instant value) {
    return value == null ? null : value.toString();
  }

  private static Instant instant(String value) {
    return value == null ? null : Instant.parse(value);
  }
}
