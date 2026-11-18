package io.github.oryxlabs.oryxos.storage.runtime;

import io.github.oryxlabs.oryxos.core.runtime.IncidentDetail;
import io.github.oryxlabs.oryxos.storage.audit.*;
import io.github.oryxlabs.oryxos.storage.execution.ExecutionEntity;
import io.github.oryxlabs.oryxos.storage.transaction.WorkspaceWriteCoordinator;
import java.util.*;

/**
 * Queries evidence only. Missing/unreadable data is never interpreted as proof that no action
 * happened.
 */
public final class IncidentEvidenceReader {
  public record Evidence(
      Set<UUID> affected,
      List<IncidentDetail.Action> known,
      List<IncidentDetail.Action> unknown,
      boolean readable,
      boolean unfinished) {}

  private final WorkspaceWriteCoordinator writes;

  public IncidentEvidenceReader(WorkspaceWriteCoordinator writes) {
    this.writes = writes;
  }

  public Evidence read(Collection<UUID> affected) {
    try {
      return writes.read(
          em -> {
            var ids = new LinkedHashSet<>(affected);
            boolean unfinished = false;
            // Acknowledged gaps remain queryable but must not pause every subsequent clean restart.
            var acknowledged = new HashSet<UUID>();
            for (var e :
                em.createQuery(
                        "select i from IncidentEntity i where i.state='ACKNOWLEDGED'",
                        IncidentEntity.class)
                    .getResultList())
              acknowledged.addAll(
                  io.github.oryxlabs.oryxos.storage.transaction.StoredJson.read(
                          e.detailJson, IncidentDetail.class)
                      .affectedExecutionIds());
            for (var e :
                em.createQuery(
                        "select e from ExecutionEntity e where e.status='RUNNING' or"
                            + " e.inFlightCount>0 or e.recordingStatus<>'COMPLETE'",
                        ExecutionEntity.class)
                    .getResultList())
              if (!acknowledged.contains(UUID.fromString(e.id))) {
                ids.add(UUID.fromString(e.id));
                unfinished = true;
              }
            var known = new ArrayList<IncidentDetail.Action>();
            var unknown = new ArrayList<IncidentDetail.Action>();
            for (var e :
                em.createQuery("select c from LlmCallEntity c", LlmCallEntity.class)
                    .getResultList()) {
              UUID id = UUID.fromString(e.executionId);
              if (e.status.equals("STARTED")) {
                ids.add(id);
                unfinished = true;
              }
              if (!ids.contains(id)) continue;
              boolean uncertain = Set.of("STARTED", "UNKNOWN").contains(e.status);
              (uncertain ? unknown : known)
                  .add(
                      new IncidentDetail.Action(
                          id,
                          UUID.fromString(e.id),
                          null,
                          "MODEL",
                          e.provider + "/" + e.model,
                          uncertain
                              ? "UNKNOWN"
                              : e.status.equals("SUCCEEDED")
                                  ? "SUCCESS"
                                  : e.status.equals("NOT_EXECUTED") ? "NOT_EXECUTED" : "ERROR",
                          "NONE",
                          "Database contains the model attempt; no response body is exposed"));
            }
            for (var e :
                em.createQuery("select t from ToolInvocationEntity t", ToolInvocationEntity.class)
                    .getResultList()) {
              UUID id = UUID.fromString(e.executionId);
              if (e.status.equals("STARTED")) {
                ids.add(id);
                unfinished = true;
              }
              if (!ids.contains(id)) continue;
              boolean uncertain = Set.of("STARTED", "UNKNOWN").contains(e.status);
              (uncertain ? unknown : known)
                  .add(
                      new IncidentDetail.Action(
                          id,
                          UUID.fromString(e.id),
                          e.toolCallId,
                          "TOOL",
                          e.toolName,
                          uncertain ? "UNKNOWN" : e.status,
                          uncertain ? "UNKNOWN" : e.sideEffectState,
                          "Database contains the tool attempt; an unclosed intent does not prove"
                              + " receipt"));
            }
            return new Evidence(
                Set.copyOf(ids), List.copyOf(known), List.copyOf(unknown), true, unfinished);
          });
    } catch (RuntimeException e) {
      return new Evidence(Set.copyOf(affected), List.of(), List.of(), false, true);
    }
  }
}
