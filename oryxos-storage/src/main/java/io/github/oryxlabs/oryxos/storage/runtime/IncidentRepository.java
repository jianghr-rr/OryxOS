package io.github.oryxlabs.oryxos.storage.runtime;

import io.github.oryxlabs.oryxos.core.runtime.*;
import io.github.oryxlabs.oryxos.storage.transaction.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.*;

public final class IncidentRepository {
  private final WorkspaceWriteCoordinator writes;
  private final UUID workspace;

  public IncidentRepository(UUID workspace, WorkspaceWriteCoordinator writes) {
    this.workspace = workspace;
    this.writes = writes;
  }

  public IncidentDetail current() {
    return writes.read(em -> {
      var state = em.find(WorkspaceStateEntity.class, workspace.toString());
      if (state == null || state.incidentId == null) return null;
      var e = em.find(IncidentEntity.class, state.incidentId);
      return e == null ? null : StoredJson.read(e.detailJson, IncidentDetail.class);
    });
  }

  public boolean acknowledged(UUID id) {
    return writes.read(em -> {
      var e = em.find(IncidentEntity.class, id.toString());
      return e != null
          && e.workspaceId.equals(workspace.toString())
          && e.state.equals("ACKNOWLEDGED");
    });
  }

  public void save(IncidentDetail detail, UUID run) {
    writes.write(em -> {
      put(em, detail, run, null);
      state(em, "PAUSED_RECORDING", detail.id());
      return null;
    });
  }

  public void acknowledge(IncidentDetail detail, UUID run, RecoveryPort.Request request) {
    writes.write(em -> {
      put(em, detail.acknowledged(), run, request);
      state(em, "READY", null);
      return null;
    });
  }

  public void ready() {
    writes.write(em -> {
      state(em, "READY", null);
      return null;
    });
  }

  private void state(EntityManager em, String gate, UUID incident) {
    var e = em.find(WorkspaceStateEntity.class, workspace.toString());
    boolean fresh = e == null;
    if (fresh) {
      e = new WorkspaceStateEntity();
      e.workspaceId = workspace.toString();
    }
    e.gateState = gate;
    e.incidentId = incident == null ? null : incident.toString();
    e.updatedAt = Instant.now().toString();
    if (fresh) em.persist(e);
  }

  private void put(
      EntityManager em, IncidentDetail detail, UUID run, RecoveryPort.Request request) {
    var e = em.find(IncidentEntity.class, detail.id().toString());
    boolean fresh = e == null;
    if (fresh) {
      e = new IncidentEntity();
      e.id = detail.id().toString();
      e.workspaceId = workspace.toString();
      e.runId = run.toString();
    }
    e.detectedAt = detail.detectedAt().toString();
    e.reason = detail.safeReason();
    e.affectedExecutionIds = StoredJson.write(detail.affectedExecutionIds());
    e.missingRecordSummary = detail.missingRecordSummary();
    e.state = detail.state();
    e.detailJson = StoredJson.write(detail);
    if (request != null) {
      e.acknowledgedBy = request.operator();
      e.acknowledgedAt = Instant.now().toString();
      e.recoveryNotes = request.notes();
    }
    if (fresh) em.persist(e);
  }

  public void interruptUnfinished() {
    writes.write(em -> {
      em.createQuery(
              "update ExecutionEntity e set"
                  + " e.status='INTERRUPTED',e.stopReason='UNCLEAN_SHUTDOWN',e.finishedAt=:now,e.recordingStatus='INCOMPLETE',e.inFlightCount=0"
                  + " where e.status='RUNNING'")
          .setParameter("now", Instant.now().toString())
          .executeUpdate();
      em.createQuery(
              "update LlmCallEntity c set c.status='UNKNOWN',c.errorCode='UNCLEAN_SHUTDOWN'"
                  + " where c.status='STARTED'")
          .executeUpdate();
      em.createQuery(
              "update ToolInvocationEntity t set"
                  + " t.status='UNKNOWN',t.sideEffectState='UNKNOWN',t.resolutionStatus='UNRESOLVED',t.errorCode='UNCLEAN_SHUTDOWN'"
                  + " where t.status='STARTED'")
          .executeUpdate();
      em.createQuery(
              "update SessionEntity s set s.status='ARCHIVED',s.archivedAt=:now where"
                  + " s.kind='ONE_SHOT' and s.status='ACTIVE'")
          .setParameter("now", Instant.now().toString())
          .executeUpdate();
      return null;
    });
  }
}
