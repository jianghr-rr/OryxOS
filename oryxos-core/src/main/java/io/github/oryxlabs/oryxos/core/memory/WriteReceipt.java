package io.github.oryxlabs.oryxos.core.memory;

import java.util.UUID;

public record WriteReceipt(
    UUID sourceEntryId,
    Boolean sourceStored,
    ProjectionState projectionState,
    MemoryEntry.WriteStatus writeStatus,
    String safeMessage) {
  public enum ProjectionState {
    NOT_REQUIRED,
    CONFIRMED,
    UNKNOWN,
    FAILED
  }

  public String semanticCoverage() {
    return "NOT_PROVEN";
  }

  public boolean confirmed() {
    return Boolean.TRUE.equals(sourceStored) && writeStatus == MemoryEntry.WriteStatus.CONFIRMED;
  }
}
