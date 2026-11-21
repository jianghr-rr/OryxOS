package io.github.oryxlabs.oryxos.boot.support;

import io.github.oryxlabs.oryxos.core.execution.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class TransientExecutionStore implements ExecutionStore {
  private final Map<UUID, Execution.Snapshot> executions = new ConcurrentHashMap<>();

  public void save(Execution.Snapshot e) {
    executions.put(e.id(), e);
  }

  public Execution.Snapshot find(UUID id) {
    return executions.get(id);
  }

  public List<Execution.Snapshot> all() {
    return List.copyOf(executions.values());
  }
}
