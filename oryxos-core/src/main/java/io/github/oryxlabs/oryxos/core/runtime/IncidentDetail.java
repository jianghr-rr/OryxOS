package io.github.oryxlabs.oryxos.core.runtime;

import java.time.Instant;
import java.util.*;

public record IncidentDetail(
    UUID id,
    String state,
    Instant detectedAt,
    String safeReason,
    List<UUID> affectedExecutionIds,
    List<Action> knownActions,
    List<Action> unknownActions,
    String missingRecordSummary,
    Set<String> evidenceSources,
    boolean detailsComplete) {
  public record Action(
      UUID executionId,
      UUID attemptId,
      String toolCallId,
      String kind,
      String name,
      String outcome,
      String sideEffectState,
      String safeSummary) {}

  public IncidentDetail {
    affectedExecutionIds = List.copyOf(affectedExecutionIds);
    knownActions = List.copyOf(knownActions);
    unknownActions = List.copyOf(unknownActions);
    evidenceSources = Set.copyOf(evidenceSources);
  }

  public IncidentDetail acknowledged() {
    return new IncidentDetail(
        id,
        "ACKNOWLEDGED",
        detectedAt,
        safeReason,
        affectedExecutionIds,
        knownActions,
        unknownActions,
        missingRecordSummary,
        evidenceSources,
        detailsComplete);
  }
}
