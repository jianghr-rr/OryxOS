package io.github.oryxlabs.oryxos.memory.backend;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceInitializer;
import java.util.*;

public final class SqliteMemoryStore implements LongTermMemoryStore {
  private final UUID workspace;
  private final MemoryRecordStore records;

  public SqliteMemoryStore(UUID workspace, MemoryRecordStore records) {
    this.workspace = workspace;
    this.records = records;
  }

  public UUID workspaceId() {
    return workspace;
  }

  public String backend() {
    return "sqlite";
  }

  public List<MemoryEntry> browse(MemoryEntry.Scope scope) {
    return records.list(workspace, backend(), scope, true);
  }

  public List<MemoryEntry> load(MemoryEntry.Scope scope, ExecutionContext ctx) {
    check(ctx);
    return records.list(workspace, backend(), scope, true);
  }

  public WriteReceipt save(String text, MemoryEntry.Scope scope, ExecutionContext ctx) {
    check(ctx);
    MemoryTextCodec.validateContent(text);
    var entry =
        new MemoryEntry(
            UUID.randomUUID(),
            workspace,
            scope,
            text,
            ctx.time().instant(),
            ctx.execution().id(),
            ctx.agent().name(),
            backend(),
            MemoryEntry.WriteStatus.CONFIRMED,
            List.of());
    return ctx.dispatcher()
        .call(
            () -> {
              records.insert(entry);
              return new WriteReceipt(
                  entry.id(),
                  true,
                  WriteReceipt.ProjectionState.NOT_REQUIRED,
                  MemoryEntry.WriteStatus.CONFIRMED,
                  "Original memory saved");
            });
  }

  public List<MemoryEntry> recall(String query, ExecutionContext ctx) {
    String key = MemoryTextCodec.keyword(query);
    return load(MemoryEntry.Scope.ARCHIVAL, ctx).stream()
        .filter(e -> MemoryTextCodec.keyword(e.rawContent()).contains(key))
        .toList();
  }

  private void check(ExecutionContext ctx) {
    if (!workspace.equals(WorkspaceInitializer.readId(ctx.workspace())))
      throw new OryxError("TARGET_DENIED", "Memory workspace differs");
  }
}
