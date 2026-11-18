package io.github.oryxlabs.oryxos.core.schedule;

import io.github.oryxlabs.oryxos.core.execution.ProcessRequest;
import java.time.Instant;
import java.util.UUID;

/**
 * A trigger decision. Execution outcome remains in the associated execution, including late facts.
 */
public record ScheduleEvent(
    UUID id,
    String scheduleId,
    UUID executionId,
    ProcessRequest.Trigger triggerKind,
    Instant scheduledFor,
    Status status,
    String reason,
    Instant createdAt) {
  public enum Status {
    DISPATCHED,
    SKIPPED_OVERLAP,
    SKIPPED_PAUSED,
    FAILED
  }
}
