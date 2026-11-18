package io.github.oryxlabs.oryxos.memory.backend;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.workspace.WorkspaceInitializer;
import io.github.oryxlabs.oryxos.memory.mem0.Mem0Client;
import java.util.*;

public final class Mem0MemoryStore implements LongTermMemoryStore {
  private final UUID workspace;
  private final MemoryRecordStore records;
  private final Mem0Client remote;

  public Mem0MemoryStore(UUID workspace, MemoryRecordStore records, Mem0Client remote) {
    this.workspace = workspace;
    this.records = records;
    this.remote = remote;
  }

  public UUID workspaceId() {
    return workspace;
  }

  public String backend() {
    return "mem0";
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
    UUID id = UUID.randomUUID();
    boolean core = scope == MemoryEntry.Scope.CORE;
    var entry =
        new MemoryEntry(
            id,
            workspace,
            scope,
            text,
            ctx.time().instant(),
            ctx.execution().id(),
            ctx.agent().name(),
            backend(),
            core ? MemoryEntry.WriteStatus.CONFIRMED : MemoryEntry.WriteStatus.PREPARED,
            List.of());
    ctx.dispatcher()
        .call(
            () -> {
              records.insert(entry);
              return null;
            });
    if (core)
      return new WriteReceipt(
          id,
          true,
          WriteReceipt.ProjectionState.NOT_REQUIRED,
          MemoryEntry.WriteStatus.CONFIRMED,
          "CORE original saved locally");
    boolean posted = false, confirming = false;
    try {
      ctx.dispatcher().check();
      posted = true;
      var result = remote.add(workspace, id, text, ctx);
      var items = result.get("results");
      if (items == null || !items.isArray() || items.isEmpty())
        throw new OryxError("MEM0_EMPTY_RESULT", "No projection was confirmed");
      var ids = new LinkedHashSet<String>();
      for (var item : items) {
        String remoteId = item.path("id").asText("");
        if (remoteId.isBlank() || !ids.add(remoteId))
          throw new OryxError("MEM0_PROTOCOL", "Missing or duplicate projection identifiers");
      }
      confirming = true;
      for (String remoteId : ids) {
        var projected = remote.get(remoteId, ctx);
        if (!belongs(projected, id) || !remoteId.equals(projected.path("id").asText()))
          throw new OryxError("MEM0_UNCERTAIN", "Projection ownership could not be confirmed");
      }
      // Confirmation is a local record operation, not another remote side effect; still check
      // expiry before declaring success.
      ctx.dispatcher().check();
      records.finish(
          workspace, id, MemoryEntry.WriteStatus.CONFIRMED, List.copyOf(ids), ctx.time().instant());
      return new WriteReceipt(
          id,
          true,
          WriteReceipt.ProjectionState.CONFIRMED,
          MemoryEntry.WriteStatus.CONFIRMED,
          "Source and projection confirmed; semantic coverage NOT_PROVEN");
    } catch (OryxError e) {
      if (e.code().equals("RECORDING_FAILURE")) {
        ctx.recordingFailed();
        throw e;
      }
      boolean unknown =
          posted
              && (confirming || !Set.of("MEM0_REJECTED", "MEM0_EMPTY_RESULT").contains(e.code()));
      var state = unknown ? MemoryEntry.WriteStatus.UNKNOWN : MemoryEntry.WriteStatus.FAILED;
      records.finish(workspace, id, state, List.of(), ctx.time().instant());
      return new WriteReceipt(
          id,
          true,
          unknown ? WriteReceipt.ProjectionState.UNKNOWN : WriteReceipt.ProjectionState.FAILED,
          state,
          "Source retained; projection is " + state + "; do not automatically replay");
    }
  }

  public List<MemoryEntry> recall(String query, ExecutionContext ctx) {
    check(ctx);
    if (query == null || query.isBlank())
      throw new OryxError("INVALID_ARGUMENTS", "Memory query must not be blank");
    var response = remote.search(workspace, query, ctx);
    var results = response.get("results");
    if (results == null || !results.isArray())
      throw new OryxError("MEM0_PROTOCOL", "Invalid search result");
    var accepted = new LinkedHashMap<UUID, MemoryEntry>();
    for (var item : results) {
      if (!item.path("score").isNumber()
          || !Double.isFinite(item.path("score").asDouble())
          || item.path("score").asDouble() < remote.threshold()) continue;
      UUID source;
      try {
        source = UUID.fromString(item.path("metadata").path("source_entry_id").asText());
      } catch (RuntimeException e) {
        continue;
      }
      if (!belongs(item, source) || !records.maps(workspace, source, item.path("id").asText()))
        continue;
      var entry = records.find(workspace, source);
      if (entry != null
          && entry.backend().equals(backend())
          && entry.scope() == MemoryEntry.Scope.ARCHIVAL
          && entry.writeStatus() == MemoryEntry.WriteStatus.CONFIRMED)
        accepted.putIfAbsent(source, entry);
    }
    return List.copyOf(accepted.values());
  }

  private boolean belongs(JsonNode value, UUID source) {
    return workspace.toString().equals(value.path("user_id").asText())
        && "ARCHIVAL".equals(value.path("metadata").path("oryx_scope").asText())
        && source.toString().equals(value.path("metadata").path("source_entry_id").asText());
  }

  private void check(ExecutionContext ctx) {
    if (!workspace.equals(WorkspaceInitializer.readId(ctx.workspace())))
      throw new OryxError("TARGET_DENIED", "Memory workspace differs");
  }

  public void close() {
    remote.close();
  }
}
