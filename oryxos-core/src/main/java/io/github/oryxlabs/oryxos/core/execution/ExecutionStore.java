package io.github.oryxlabs.oryxos.core.execution;

import java.util.UUID;

public interface ExecutionStore {
  void save(Execution.Snapshot execution);

  default void completeTurn(Execution.Snapshot execution) {
    save(execution);
  }

  Execution.Snapshot find(UUID executionId);

  default RecordingScope.Coalescer coalescer() {
    return null;
  }
}
