package io.github.oryxlabs.oryxos.memory.tool;

import io.github.oryxlabs.oryxos.core.error.OryxError;
import io.github.oryxlabs.oryxos.core.execution.ExecutionContext;
import io.github.oryxlabs.oryxos.core.memory.*;
import io.github.oryxlabs.oryxos.core.tool.*;
import java.util.*;

public final class SaveMemoryTool implements OryxTool {
  private final LongTermMemoryStore store;

  public SaveMemoryTool(LongTermMemoryStore store) {
    this.store = store;
  }

  public ToolDescriptor descriptor() {
    return staticDescriptor();
  }

  public static ToolDescriptor staticDescriptor() {
    return new ToolDescriptor(
        "save_memory",
        "Explicitly save a fact to workspace-shared memory; default ARCHIVAL",
        "{\"type\":\"object\",\"properties\":{\"content\":{\"type\":\"string\",\"minLength\":1,\"pattern\":\"\\\\S\"},\"scope\":{\"type\":\"string\",\"enum\":[\"CORE\",\"ARCHIVAL\"]}},\"required\":[\"content\"],\"additionalProperties\":false}",
        false,
        true,
        null,
        null);
  }

  public void authorize(Map<String, Object> args, ExecutionContext ctx) {
    if (store == null) throw new OryxError("MEMORY_UNAVAILABLE", "Memory backend is not ready");
    store.authorize(ctx);
    String text = (String) args.get("content");
    if (ctx.redactor().containsSecret(text))
      throw new OryxError("TARGET_DENIED", "Known credentials cannot be saved as memory");
  }

  public ToolResult execute(Map<String, Object> args, ExecutionContext ctx) {
    WriteReceipt receipt;
    try {
      receipt =
          store.save(
              (String) args.get("content"),
              MemoryEntry.Scope.valueOf((String) args.getOrDefault("scope", "ARCHIVAL")),
              ctx);
    } catch (OryxError error) {
      if (Set.of("MEMORY_CORRUPT", "MEMORY_UNAVAILABLE", "TARGET_DENIED", "INVALID_ARGUMENTS")
          .contains(error.code()))
        return ToolResult.failure(
            error.code().equals("TARGET_DENIED")
                ? ToolResult.Outcome.DENIED
                : ToolResult.Outcome.ERROR,
            error.code(),
            error.getMessage());
      throw error;
    }
    String content =
        "sourceEntryId="
            + receipt.sourceEntryId()
            + "; sourceStored="
            + receipt.sourceStored()
            + "; projectionState="
            + receipt.projectionState()
            + "; semanticCoverage="
            + receipt.semanticCoverage()
            + "; "
            + receipt.safeMessage();
    var outcome =
        receipt.confirmed()
            ? ToolResult.Outcome.SUCCESS
            : receipt.writeStatus() == MemoryEntry.WriteStatus.UNKNOWN
                ? ToolResult.Outcome.UNKNOWN
                : ToolResult.Outcome.ERROR;
    return new ToolResult(
        outcome,
        content,
        receipt.confirmed()
            ? null
            : outcome == ToolResult.Outcome.UNKNOWN
                ? "MEMORY_WRITE_UNKNOWN"
                : "MEMORY_WRITE_FAILED",
        receipt.safeMessage(),
        ToolResult.RetryClass.NONE,
        outcome == ToolResult.Outcome.UNKNOWN
            ? ToolResult.SideEffectState.UNKNOWN
            : Boolean.TRUE.equals(receipt.sourceStored())
                ? ToolResult.SideEffectState.CONFIRMED
                : ToolResult.SideEffectState.NONE,
        null,
        1,
        0);
  }
}
