package io.github.oryxlabs.oryxos.core.memory;

import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import java.util.ArrayList;
import java.util.List;

public interface LongTermMemoryStore extends AutoCloseable {
  String backend();

  java.util.UUID workspaceId();

  default void authorize(ExecutionContext context) {
    if (!workspaceId()
        .equals(
            io.github.oryxlabs.oryxos.core.workspace.WorkspaceInitializer.readId(
                context.workspace())))
      throw new io.github.oryxlabs.oryxos.core.error.OryxError(
          "TARGET_DENIED", "Memory belongs to another workspace");
  }

  List<MemoryEntry> load(MemoryEntry.Scope scope, ExecutionContext context);

  default List<MemoryEntry> loadAll(ExecutionContext context) {
    var core = load(MemoryEntry.Scope.CORE, context);
    var archival = load(MemoryEntry.Scope.ARCHIVAL, context);
    var combined = new ArrayList<MemoryEntry>(core.size() + archival.size());
    combined.addAll(core);
    combined.addAll(archival);
    return combined;
  }

  /** Confirmed local sources only; diagnostic browsing never invokes a model or remote search. */
  default List<MemoryEntry> browse(MemoryEntry.Scope scope) {
    throw new io.github.oryxlabs.oryxos.core.error.OryxError(
        "MEMORY_UNAVAILABLE", "Memory browsing is unavailable");
  }

  WriteReceipt save(String content, MemoryEntry.Scope scope, ExecutionContext context);

  List<MemoryEntry> recall(String query, ExecutionContext context);

  default void close() {}
}
