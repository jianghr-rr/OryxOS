package io.github.oryxlabs.oryxos.core.runtime;

import java.util.UUID;

public interface RecoveryPort {
  record Request(
      UUID incidentId, boolean acknowledgeMissingRecords, String notes, String operator) {}

  record State(
      UUID workspaceId,
      String state,
      boolean storageWritable,
      UUID incidentId,
      String safeReason,
      int inFlightCount,
      boolean recoveryRequired,
      IncidentDetail currentIncident) {}

  State state();

  State recover(Request request);
}
